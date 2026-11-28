use anyhow::{bail, Context, Result};
use avb2::{
    add_hash_footer, add_hashtree_footer, erase_footer, load_avb_image, make_vbmeta_image,
    verify_image, AlgorithmType, AvbDescriptor, AvbPrivateKey, HashFooterOptions,
    HashtreeFooterOptions,
};
use bootimg::{
    is_sparse_image, stream_decompress_file, unsparse_image, BootImageV3V4, CompressionFormat,
};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use crate::device::{sha256_file, STANDARD_BOOT_PARTITIONS};
use crate::events::whitebox::{emit_plan, run_step, GateKind, PlannedStep};
use crate::events::{emit_log, emit_progress};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PatchDsuKeyReport {
    pub output_path: String,
    pub size: u64,
    pub sha256: String,
    pub ramdisk_compression: CompressionFormat,
    pub injected_paths: Vec<String>,
    pub has_kernelsu: bool,
    pub avb_pubkey_sha1: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ForgeBundleReport {
    pub output_dir: String,
    pub signed_partitions: Vec<String>,
    pub pubkey_sha1: String,
    pub dsu_key_injected: bool,
    pub init_boot_has_ksu: bool,
    pub chain_verified_count: usize,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct GsiSignReport {
    pub output_path: String,
    pub original_size: u64,
    pub final_size: u64,
    pub tree_size: u64,
    pub root_digest: String,
    pub pubkey_sha1: String,
    #[serde(default)]
    pub genfs_conflicts_patched: usize,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ExportCleanInitBootReport {
    pub output_path: String,
    pub backup_path: Option<String>,
    pub size: u64,
    pub sha256: String,
    pub had_kernelsu: bool,
    pub stripped_kernelsu: bool,
    pub existing_dsu_keys: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DsuInfoReport {
    pub installed: bool,
    pub enabled: bool,
    pub running: bool,
    pub one_shot: bool,
    pub slot_name: Option<String>,
    pub system_size_bytes: u64,
    pub userdata_size_bytes: u64,
    pub total_disk_bytes: u64,
    pub avb_pubkey_sha1: Option<String>,
    pub raw_status: String,
}

/// Inject an AVB public key (optional when `inject_dsu_key == false`) into `init_boot` (or `boot`) ramdisk and re-sign preserving original AVB metadata.
#[allow(clippy::too_many_arguments)]
pub fn patch_dsu_key(
    input_img: &Path,
    output_img: &Path,
    key_pem: &Path,
    passphrase: Option<&str>,
    partition_name: &str,
    target_key_name: &str,
    metadata_source_img: Option<&Path>,
    inject_dsu_key: bool,
) -> Result<PatchDsuKeyReport> {
    patch_dsu_key_inner(
        input_img,
        output_img,
        key_pem,
        passphrase,
        partition_name,
        target_key_name,
        metadata_source_img,
        inject_dsu_key,
        true,
    )
}

#[allow(clippy::too_many_arguments)]
fn patch_dsu_key_inner(
    input_img: &Path,
    output_img: &Path,
    key_pem: &Path,
    passphrase: Option<&str>,
    partition_name: &str,
    target_key_name: &str,
    metadata_source_img: Option<&Path>,
    inject_dsu_key: bool,
    emit_step_plan: bool,
) -> Result<PatchDsuKeyReport> {
    if emit_step_plan {
        emit_plan(vec![
            PlannedStep::new(
                "cpio_patch",
                if inject_dsu_key {
                    format!("解包 {partition_name} Ramdisk 并注入 DSU 公钥")
                } else {
                    format!("解析 {partition_name} 镜像与 AVB 元数据")
                },
                format!(
                    "avbforge-core patch-dsu-key --partition-name {partition_name} --inject-dsu-key {inject_dsu_key}"
                ),
            ),
            PlannedStep::new(
                "avb_sign_verify",
                format!("AVB 签名与离线回环验签 ({partition_name})"),
                format!("avbforge-core avb add-hash-footer --partition-name {partition_name} --verify-offline"),
            ),
        ]);
    }

    let priv_key = AvbPrivateKey::from_pem_file(key_pem, passphrase)?;
    let pub_key = priv_key.public_key()?;
    let alg = priv_key.default_algorithm()?;

    // Read AVB metadata (partition_size, rollback_index, flags, props) from metadata_source or input_img
    let meta_src = metadata_source_img.unwrap_or(input_img);
    let meta = load_avb_image(meta_src).ok();
    let partition_size = meta
        .as_ref()
        .map(|m| m.image_size)
        .unwrap_or_else(|| fs::metadata(input_img).map(|m| m.len()).unwrap_or(8 * 1024 * 1024));
    let rollback_index = meta.as_ref().map(|m| m.vbmeta.rollback_index).unwrap_or(0);
    let flags = meta.as_ref().map(|m| m.vbmeta.flags).unwrap_or(0);
    let props: Vec<(String, String)> = meta
        .as_ref()
        .map(|m| {
            m.vbmeta
                .descriptors
                .iter()
                .filter_map(|d| match d {
                    AvbDescriptor::Property(p) => Some((p.key.clone(), p.value.clone())),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default();

    let (fmt, injected_paths, repacked) = if emit_step_plan {
        run_step(
            "cpio_patch",
            if inject_dsu_key {
                format!("解包 {partition_name} Ramdisk 并注入 DSU 公钥")
            } else {
                format!("解析 {partition_name} 镜像与 AVB 元数据")
            },
            format!(
                "avbforge-core patch-dsu-key --partition-name {partition_name} --inject-dsu-key {inject_dsu_key}"
            ),
            None,
            if inject_dsu_key { "INJECTED" } else { "PARSED" },
            |step| {
                let in_bytes = fs::read(input_img)?;
                let raw_slice = match load_avb_image(input_img) {
                    Ok(loaded) => match loaded.footer {
                        Some(f) => &in_bytes[..f.original_image_size as usize],
                        None => &in_bytes[..],
                    },
                    Err(_) => &in_bytes[..],
                };

                let boot = BootImageV3V4::parse(raw_slice)?;
                let (fmt, mut cpio) = boot.unpack_ramdisk()?;
                let mut injected_paths = Vec::new();
                let repacked = if inject_dsu_key {
                    let key_basename = target_key_name.trim_start_matches('/').trim_start_matches("avb/");
                    let path1 = format!("first_stage_ramdisk/avb/{key_basename}");
                    let path2 = format!("avb/{key_basename}");
                    cpio.upsert_file(&path1, 0o100644, pub_key.raw_avb_key.clone());
                    cpio.upsert_file(&path2, 0o100644, pub_key.raw_avb_key.clone());
                    injected_paths.push(path1);
                    injected_paths.push(path2);
                    boot.repack_with_ramdisk(fmt, &cpio)?
                } else {
                    raw_slice.to_vec()
                };
                step.kv(
                    "ramdisk processed",
                    &[
                        ("compression", &format!("{fmt:?}")),
                        ("partition_size", &partition_size.to_string()),
                        ("injected_count", &injected_paths.len().to_string()),
                    ],
                );
                Ok((fmt, injected_paths, repacked))
            },
        )?
    } else {
        let in_bytes = fs::read(input_img)?;
        let raw_slice = match load_avb_image(input_img) {
            Ok(loaded) => match loaded.footer {
                Some(f) => &in_bytes[..f.original_image_size as usize],
                None => &in_bytes[..],
            },
            Err(_) => &in_bytes[..],
        };
        let boot = BootImageV3V4::parse(raw_slice)?;
        let (fmt, mut cpio) = boot.unpack_ramdisk()?;
        let mut injected_paths = Vec::new();
        let repacked = if inject_dsu_key {
            let key_basename = target_key_name.trim_start_matches('/').trim_start_matches("avb/");
            let path1 = format!("first_stage_ramdisk/avb/{key_basename}");
            let path2 = format!("avb/{key_basename}");
            cpio.upsert_file(&path1, 0o100644, pub_key.raw_avb_key.clone());
            cpio.upsert_file(&path2, 0o100644, pub_key.raw_avb_key.clone());
            injected_paths.push(path1);
            injected_paths.push(path2);
            boot.repack_with_ramdisk(fmt, &cpio)?
        } else {
            raw_slice.to_vec()
        };
        (fmt, injected_paths, repacked)
    };

    if let Some(parent) = output_img.parent() {
        fs::create_dir_all(parent)?;
    }

    let sign_and_verify = |step: Option<&crate::events::whitebox::StepGuard>| -> Result<PatchDsuKeyReport> {
        let tmp_dir = tempfile::tempdir()?;
        let staged = tmp_dir.path().join(format!("{partition_name}.img"));
        fs::write(&staged, &repacked)?;

        add_hash_footer(
            &staged,
            HashFooterOptions {
                partition_name,
                partition_size,
                algorithm: alg,
                key: Some(&priv_key),
                salt: None,
                rollback_index,
                flags,
                props,
                release_string: None,
            },
        )?;

        // Mandatory offline verification + round-trip ramdisk integrity check
        verify_image(&staged, Some(&pub_key.raw_avb_key), false, None, true)?;
        let out_bytes = fs::read(&staged)?;
        let loaded_out = load_avb_image(&staged)?;
        let raw_out = &out_bytes[..loaded_out.footer.unwrap().original_image_size as usize];
        let re_boot = BootImageV3V4::parse(raw_out)?;
        let re_info = re_boot.inspect()?;
        if inject_dsu_key {
            let (_, re_cpio) = re_boot.unpack_ramdisk()?;
            for p in &injected_paths {
                if re_cpio.get(p).map(|e| e.data.as_slice()) != Some(pub_key.raw_avb_key.as_slice()) {
                    bail!("Round-trip verification failed: injected avbpubkey mismatch at {p}");
                }
            }
        }

        fs::copy(&staged, output_img)?;
        let sha256 = sha256_file(output_img)?;
        if let Some(s) = step {
            s.kv(
                "signed & verified",
                &[
                    ("algorithm", alg.as_str()),
                    ("has_kernelsu", &re_info.has_kernelsu.to_string()),
                    ("sha256", &sha256),
                ],
            );
            s.ok_line("offline AVB & ramdisk round-trip verify passed");
        }
        Ok(PatchDsuKeyReport {
            output_path: output_img.display().to_string(),
            size: out_bytes.len() as u64,
            sha256,
            ramdisk_compression: fmt,
            injected_paths,
            has_kernelsu: re_info.has_kernelsu,
            avb_pubkey_sha1: pub_key.sha1_hex(),
        })
    };

    if emit_step_plan {
        run_step(
            "avb_sign_verify",
            format!("AVB 签名与离线回环验签 ({partition_name})"),
            format!("avbforge-core avb add-hash-footer --partition-name {partition_name} --verify-offline"),
            Some(GateKind::OfflineVerify),
            "PASS",
            |step| sign_and_verify(Some(step)),
        )
    } else {
        sign_and_verify(None)
    }
}

/// Forge a complete self-signed partition bundle from stock backup + optional user-patched KSU init_boot + optional DSU key injection.
#[allow(clippy::too_many_arguments)]
pub fn forge_bundle(
    stock_dir: &Path,
    output_dir: &Path,
    key_pem: &Path,
    passphrase: Option<&str>,
    patched_init_boot: Option<&Path>,
    inject_dsu_key: bool,
    dsu_key_name: &str,
) -> Result<ForgeBundleReport> {
    fs::create_dir_all(output_dir)?;
    let priv_key = AvbPrivateKey::from_pem_file(key_pem, passphrase)?;
    let pub_key = priv_key.public_key()?;
    let alg = priv_key.default_algorithm()?;

    let orig_vbmeta_path = stock_dir.join("vbmeta.img");
    if !orig_vbmeta_path.exists() {
        bail!("stock_dir missing vbmeta.img: {}", stock_dir.display());
    }
    let orig_vbmeta = load_avb_image(&orig_vbmeta_path)?;
    let vbmeta_padding = fs::metadata(&orig_vbmeta_path)?.len() as usize;
    let stock_vbmeta_sys = stock_dir.join("vbmeta_system.img");

    let mut plan_steps = vec![PlannedStep::new(
        "forge_partitions",
        "重签引导分区镜像并处理 init_boot",
        format!("avbforge-core forge-bundle --stock-dir {} --inject-dsu-key {inject_dsu_key}", stock_dir.display()),
    )];
    if stock_vbmeta_sys.exists() {
        plan_steps.push(PlannedStep::new(
            "forge_vbmeta_system",
            "重签 vbmeta_system（保留原厂逻辑分区 Hashtree）",
            "avbforge-core avb resign-vbmeta-system --preserve-descriptors",
        ));
    }
    plan_steps.push(PlannedStep::new(
        "forge_vbmeta",
        "重建顶层 vbmeta.img 信任链公钥",
        "avbforge-core avb make-vbmeta --replace-chain-pubkeys",
    ));
    plan_steps.push(PlannedStep::new(
        "verify_bundle_chain",
        "全链离线验签自签名镜像包",
        format!("avbforge-core avb verify --image {} --follow-chain", output_dir.join("vbmeta.img").display()),
    ));
    emit_plan(plan_steps);

    let mut signed_partitions = Vec::new();
    let mut init_boot_has_ksu = false;

    // 1. Re-sign all hash-footer boot partitions present in stock_dir
    run_step(
        "forge_partitions",
        "重签引导分区镜像并处理 init_boot",
        format!("avbforge-core forge-bundle --stock-dir {} --inject-dsu-key {inject_dsu_key}", stock_dir.display()),
        None,
        "SIGNED",
        |step| {
            for &part in STANDARD_BOOT_PARTITIONS {
                if part == "vbmeta" || part == "vbmeta_system" {
                    continue;
                }
                let stock_img = stock_dir.join(format!("{part}.img"));
                if !stock_img.exists() {
                    continue;
                }
                let out_img = output_dir.join(format!("{part}.img"));
                emit_log("info", &format!("Processing partition: {part}"));

                if part == "init_boot" {
                    let src_for_init_boot = patched_init_boot.unwrap_or(&stock_img);
                    if inject_dsu_key {
                        let rep = patch_dsu_key_inner(
                            src_for_init_boot,
                            &out_img,
                            key_pem,
                            passphrase,
                            "init_boot",
                            dsu_key_name,
                            Some(&stock_img),
                            true,
                            false,
                        )?;
                        init_boot_has_ksu = rep.has_kernelsu;
                    } else {
                        resign_hash_partition(src_for_init_boot, Some(&stock_img), &out_img, part, &priv_key, alg)?;
                        let loaded = load_avb_image(&out_img)?;
                        let bytes = fs::read(&out_img)?;
                        if let Some(f) = loaded.footer {
                            if let Ok(b) = BootImageV3V4::parse(&bytes[..f.original_image_size as usize]) {
                                if let Ok(i) = b.inspect() {
                                    init_boot_has_ksu = i.has_kernelsu;
                                }
                            }
                        }
                    }
                } else {
                    resign_hash_partition(&stock_img, Some(&stock_img), &out_img, part, &priv_key, alg)?;
                }
                step.kv("signed partition", &[("partition", part), ("algorithm", alg.as_str())]);
                signed_partitions.push(part.to_string());
            }
            step.ok_line(format!("{} boot partition(s) signed", signed_partitions.len()));
            Ok(())
        },
    )?;

    // 2. Re-sign vbmeta_system preserving all original descriptors (odm/product/system/system_ext/vendor/dlkm!)
    if stock_vbmeta_sys.exists() {
        run_step(
            "forge_vbmeta_system",
            "重签 vbmeta_system（保留原厂逻辑分区 Hashtree）",
            "avbforge-core avb resign-vbmeta-system --preserve-descriptors",
            None,
            "SIGNED",
            |step| {
                emit_log("info", "Re-signing vbmeta_system.img (preserving all logical partition hashtrees)");
                let loaded_sys = load_avb_image(&stock_vbmeta_sys)?;
                let desc_count = loaded_sys.vbmeta.descriptors.len();
                let sys_pad = fs::metadata(&stock_vbmeta_sys)?.len() as usize;
                let out_sys = output_dir.join("vbmeta_system.img");
                make_vbmeta_image(
                    &out_sys,
                    loaded_sys.vbmeta.descriptors,
                    alg,
                    Some(&priv_key),
                    loaded_sys.vbmeta.rollback_index,
                    loaded_sys.vbmeta.flags,
                    sys_pad,
                    None,
                )?;
                step.kv("vbmeta_system", &[("descriptors", &desc_count.to_string()), ("padding", &sys_pad.to_string())]);
                signed_partitions.push("vbmeta_system".to_string());
                Ok(())
            },
        )?;
    }

    // 3. Rebuild top-level vbmeta.img replacing chain descriptor pubkeys with our custom pubkey
    let out_vbmeta = output_dir.join("vbmeta.img");
    run_step(
        "forge_vbmeta",
        "重建顶层 vbmeta.img 信任链公钥",
        "avbforge-core avb make-vbmeta --replace-chain-pubkeys",
        None,
        "REBUILT",
        |step| {
            let mut new_vbmeta_descs = Vec::new();
            let mut replaced_chains = 0usize;
            for d in orig_vbmeta.vbmeta.descriptors {
                match d {
                    AvbDescriptor::ChainPartition(mut cp) => {
                        if output_dir.join(format!("{}.img", cp.partition_name)).exists() {
                            cp.public_key = pub_key.raw_avb_key.clone();
                            replaced_chains += 1;
                        }
                        new_vbmeta_descs.push(AvbDescriptor::ChainPartition(cp));
                    }
                    other => new_vbmeta_descs.push(other),
                }
            }
            make_vbmeta_image(
                &out_vbmeta,
                new_vbmeta_descs,
                alg,
                Some(&priv_key),
                orig_vbmeta.vbmeta.rollback_index,
                orig_vbmeta.vbmeta.flags,
                vbmeta_padding,
                None,
            )?;
            step.kv(
                "vbmeta rebuilt",
                &[("chains_updated", &replaced_chains.to_string()), ("pubkey_sha1", &pub_key.sha1_hex())],
            );
            signed_partitions.push("vbmeta".to_string());
            Ok(())
        },
    )?;

    // 4. Mandatory full-chain verification!
    let verified = run_step(
        "verify_bundle_chain",
        "全链离线验签自签名镜像包",
        format!("avbforge-core avb verify --image {} --follow-chain", out_vbmeta.display()),
        Some(GateKind::OfflineVerify),
        "CHAIN_PASS",
        |step| {
            emit_log("info", "Running full-chain AVB verification on forged bundle...");
            let verified = verify_image(&out_vbmeta, Some(&pub_key.raw_avb_key), true, None, true)
                .context("Forged bundle failed full-chain AVB verification")?;
            step.kv("chain_verified", &[("images", &verified.len().to_string())]);
            step.ok_line("full-chain offline AVB verification passed");
            Ok(verified)
        },
    )?;

    Ok(ForgeBundleReport {
        output_dir: output_dir.display().to_string(),
        signed_partitions,
        pubkey_sha1: pub_key.sha1_hex(),
        dsu_key_injected: inject_dsu_key,
        init_boot_has_ksu,
        chain_verified_count: verified.len(),
    })
}

fn resign_hash_partition(
    src_img: &Path,
    meta_src: Option<&Path>,
    out_img: &Path,
    partition_name: &str,
    priv_key: &AvbPrivateKey,
    alg: AlgorithmType,
) -> Result<()> {
    let meta = load_avb_image(meta_src.unwrap_or(src_img))?;
    let partition_size = meta.image_size;
    let rollback_index = meta.vbmeta.rollback_index;
    let flags = meta.vbmeta.flags;
    let props: Vec<(String, String)> = meta
        .vbmeta
        .descriptors
        .iter()
        .filter_map(|d| match d {
            AvbDescriptor::Property(p) => Some((p.key.clone(), p.value.clone())),
            _ => None,
        })
        .collect();

    fs::copy(src_img, out_img)?;
    let _ = erase_footer(out_img)?;
    add_hash_footer(
        out_img,
        HashFooterOptions {
            partition_name,
            partition_size,
            algorithm: alg,
            key: Some(priv_key),
            salt: None,
            rollback_index,
            flags,
            props,
            release_string: None,
        },
    )?;
    Ok(())
}

/// Decompress (if .xz/.gz), unsparse (if sparse), strip old footer, optionally auto-patch
/// SELinux `genfscon` conflicts against host `/vendor/etc/selinux/vendor_sepolicy.cil`, and sign
/// a GSI with AVB hashtree footer.
/// Automatically injects `com.android.build.system.security_patch` (>= host SPL) so locked Yellow
/// devices pass AOSP `IsStandaloneImageRollback()` during `first_stage_mount`!
#[allow(clippy::too_many_arguments)]
pub fn sign_gsi(
    input_path: &Path,
    output_path: &Path,
    key_pem: &Path,
    passphrase: Option<&str>,
    partition_size: Option<u64>,
    security_patch_override: Option<&str>,
    fix_vendor_genfs: bool,
) -> Result<GsiSignReport> {
    let priv_key = AvbPrivateKey::from_pem_file(key_pem, passphrase)?;
    let pub_key = priv_key.public_key()?;
    let alg = priv_key.default_algorithm()?;

    if let Some(p) = output_path.parent() {
        fs::create_dir_all(p)?;
    }

    let tmp_dir = tempfile::tempdir_in(output_path.parent().unwrap_or_else(|| Path::new(".")))?;
    let work_img = tmp_dir.path().join("system.img");

    let mut plan_steps = vec![PlannedStep::new(
        "gsi_prepare",
        "流式解压 / 展开 Sparse 并清理旧 AVB Footer",
        format!("avbforge-core gsi prepare --input {}", input_path.display()),
    )];
    if fix_vendor_genfs {
        plan_steps.push(PlannedStep::new(
            "gsi_selinux_fix",
            "检测并修复 SELinux genfscon 冲突",
            "avbforge-core gsi fix-vendor-genfs --host /vendor/etc/selinux/vendor_sepolicy.cil",
        ));
    }
    plan_steps.push(PlannedStep::new(
        "gsi_hashtree_sign",
        "计算 Merkle Hashtree 并签发 AVB Footer",
        format!("avbforge-core avb add-hashtree-footer --partition-name system --algorithm {}", alg.as_str()),
    ));
    plan_steps.push(PlannedStep::new(
        "gsi_verify",
        "离线全树验签 GSI",
        format!("avbforge-core avb verify --image {}", output_path.display()),
    ));
    emit_plan(plan_steps);

    // Step 1-3: stream decompress, unsparse, extract properties & strip old footer
    let (orig_spl, orig_os_ver) = run_step(
        "gsi_prepare",
        "流式解压 / 展开 Sparse 并清理旧 AVB Footer",
        format!("avbforge-core gsi prepare --input {}", input_path.display()),
        None,
        "READY",
        |step| {
            emit_log("info", &format!("Preparing GSI from {}...", input_path.display()));
            let fmt = stream_decompress_file(input_path, &work_img, |bytes| {
                emit_progress("decompress", bytes, 0);
            })?;
            emit_log("info", &format!("Input compression detected: {fmt:?}"));

            let mut was_sparse = false;
            if is_sparse_image(&work_img)? {
                was_sparse = true;
                emit_log("info", "Detected Android sparse image, converting to raw...");
                let raw_img = tmp_dir.path().join("system_raw.img");
                unsparse_image(&work_img, &raw_img, |cur, tot| {
                    emit_progress("unsparse", cur, tot);
                })?;
                fs::rename(&raw_img, &work_img)?;
            }

            let mut orig_spl: Option<String> = None;
            let mut orig_os_ver: Option<String> = None;
            if let Ok(loaded) = load_avb_image(&work_img) {
                for d in &loaded.vbmeta.descriptors {
                    if let AvbDescriptor::Property(p) = d {
                        if p.key == "com.android.build.system.security_patch" && !p.value.is_empty() {
                            orig_spl = Some(p.value.clone());
                        } else if p.key == "com.android.build.system.os_version" && !p.value.is_empty() {
                            orig_os_ver = Some(p.value.clone());
                        }
                    }
                }
            }

            let stripped = erase_footer(&work_img)?;
            if stripped {
                emit_log("info", "Stripped pre-existing AVB footer from GSI.");
            }
            step.kv(
                "gsi prepared",
                &[
                    ("compression", &format!("{fmt:?}")),
                    ("sparse", &was_sparse.to_string()),
                    ("footer_stripped", &stripped.to_string()),
                ],
            );
            Ok((orig_spl, orig_os_ver))
        },
    )?;

    // Step 3.5: optionally auto-detect & strip conflicting `plat_sepolicy_genfs_*.cil` rules
    let genfs_conflicts_patched = if fix_vendor_genfs {
        run_step(
            "gsi_selinux_fix",
            "检测并修复 SELinux genfscon 冲突",
            "avbforge-core gsi fix-vendor-genfs --host /vendor/etc/selinux/vendor_sepolicy.cil",
            None,
            "CHECKED",
            |step| {
                let short_parent = output_path.parent().unwrap_or_else(|| Path::new("."));
                let patched = patch_gsi_vendor_genfs_conflicts(&work_img, short_parent)?;
                step.kv("genfscon scan", &[("conflicts_patched", &patched.to_string())]);
                Ok(patched)
            },
        )?
    } else {
        0
    };

    // Compute a safe security_patch >= host SPL so locked Yellow devices never fail IsStandaloneImageRollback()
    let host_spl = crate::device::getprop("ro.build.version.security_patch");
    let effective_spl = if let Some(ov) = security_patch_override.filter(|s| !s.is_empty()) {
        ov.to_string()
    } else {
        let mut best = "2026-11-05".to_string();
        if !host_spl.is_empty() && host_spl > best {
            best = host_spl.clone();
        }
        if let Some(ref s) = orig_spl {
            if *s > best {
                best = s.clone();
            }
        }
        best
    };

    let mut props = vec![(
        "com.android.build.system.security_patch".to_string(),
        effective_spl.clone(),
    )];
    if let Some(osv) = orig_os_ver {
        props.push(("com.android.build.system.os_version".to_string(), osv));
    }

    // Step 4: compute Merkle hashtree & sign
    let (footer, ht_desc) = run_step(
        "gsi_hashtree_sign",
        "计算 Merkle Hashtree 并签发 AVB Footer",
        format!("avbforge-core avb add-hashtree-footer --partition-name system --algorithm {}", alg.as_str()),
        None,
        "SIGNED",
        |step| {
            emit_log(
                "info",
                &format!("Embedding AVB security_patch={effective_spl} (host={host_spl}) for locked DSU anti-rollback check."),
            );
            emit_log("info", &format!("Computing SHA-256 hashtree & signing ({})...", alg.as_str()));
            let (footer, ht_desc) = add_hashtree_footer(
                &work_img,
                HashtreeFooterOptions {
                    partition_name: "system",
                    partition_size,
                    algorithm: alg,
                    key: Some(&priv_key),
                    salt: None,
                    rollback_index: 0,
                    flags: 0,
                    props,
                    release_string: None,
                    block_size: 4096,
                },
                |cur, tot| emit_progress("hashtree", cur, tot),
            )?;
            step.kv(
                "hashtree signed",
                &[
                    ("security_patch", &effective_spl),
                    ("tree_bytes", &ht_desc.tree_size.to_string()),
                    ("root_digest", &hex::encode(&ht_desc.root_digest)),
                ],
            );
            Ok((footer, ht_desc))
        },
    )?;

    // Step 5: verify generated GSI image
    run_step(
        "gsi_verify",
        "离线全树验签 GSI",
        format!("avbforge-core avb verify --image {}", output_path.display()),
        Some(GateKind::OfflineVerify),
        "PASS",
        |step| {
            emit_log("info", "Verifying signed GSI AVB footer & hashtree...");
            verify_image(&work_img, Some(&pub_key.raw_avb_key), false, None, false)?;
            step.ok_line("GSI AVB footer & Merkle hashtree verified");
            Ok(())
        },
    )?;

    fs::rename(&work_img, output_path).or_else(|_| -> Result<()> {
        fs::copy(&work_img, output_path)?;
        Ok(())
    })?;
    let final_size = fs::metadata(output_path)?.len();

    Ok(GsiSignReport {
        output_path: output_path.display().to_string(),
        original_size: footer.original_image_size,
        final_size,
        tree_size: ht_desc.tree_size,
        root_digest: hex::encode(&ht_desc.root_digest),
        pubkey_sha1: pub_key.sha1_hex(),
        genfs_conflicts_patched,
    })
}

/// Extract `(filesystem, quoted_path)` from a CIL line like:
/// `(genfscon sysfs "/power/mem_sleep" (u object_r sysfs_power ((s0) (s0))))`
pub(crate) fn parse_genfscon_key(line: &str) -> Option<(String, String)> {
    let trimmed = line.trim();
    let rest = trimmed.strip_prefix("(genfscon")?;
    let first_ws = rest.chars().next()?;
    if !first_ws.is_whitespace() {
        return None;
    }
    let rest = rest.trim_start();
    let ws_idx = rest.find(char::is_whitespace)?;
    let fs_type = &rest[..ws_idx];
    let after_fs = rest[ws_idx..].trim_start();
    let after_quote = after_fs.strip_prefix('"')?;
    let end_quote = after_quote.find('"')?;
    let path = &after_quote[..end_quote];
    Some((fs_type.to_string(), path.to_string()))
}

pub(crate) fn collect_genfscon_keys(cil_text: &str) -> HashSet<(String, String)> {
    cil_text.lines().filter_map(parse_genfscon_key).collect()
}

/// Remove lines whose `(genfscon <fs> "<path>" ...)` collides with `vendor_keys`.
/// Returns `(filtered_content, removed_line_count, distinct_conflicts)`.
pub(crate) fn filter_conflicting_genfscon(
    cil_text: &str,
    vendor_keys: &HashSet<(String, String)>,
) -> (String, usize, Vec<(String, String)>) {
    let mut out = String::with_capacity(cil_text.len());
    let mut removed = 0usize;
    let mut hits = Vec::new();
    let mut seen = HashSet::new();
    for line in cil_text.split_inclusive('\n') {
        if let Some(key) = parse_genfscon_key(line) {
            if vendor_keys.contains(&key) {
                removed += 1;
                if seen.insert(key.clone()) {
                    hits.push(key);
                }
                continue;
            }
        }
        out.push_str(line);
    }
    (out, removed, hits)
}

fn is_ext4_image(path: &Path) -> Result<bool> {
    let mut f = fs::File::open(path)?;
    let len = f.metadata()?.len();
    if len < 2048 {
        return Ok(false);
    }
    f.seek(SeekFrom::Start(1024 + 56))?;
    let mut magic = [0u8; 2];
    f.read_exact(&mut magic)?;
    Ok(magic == [0x53, 0xEF])
}

fn resolve_tool(name: &str) -> PathBuf {
    for prefix in ["/system/bin", "/vendor/bin", "/sbin"] {
        let p = Path::new(prefix).join(name);
        if p.exists() {
            return p;
        }
    }
    PathBuf::from(name)
}

fn run_e2fsck(img: &Path, extra_args: &[&str]) -> Result<()> {
    let bin = resolve_tool("e2fsck");
    let mut cmd = Command::new(&bin);
    cmd.args(["-f", "-y"]);
    cmd.args(extra_args);
    cmd.arg(img);
    let out = cmd
        .output()
        .with_context(|| format!("Failed to execute {}", bin.display()))?;
    // e2fsck exit codes: 0 = no errors, 1 = corrected, 2 = corrected (reboot suggested)
    let code = out.status.code().unwrap_or(-1);
    if code != 0 && code != 1 && code != 2 {
        bail!(
            "e2fsck {:?} failed (exit {code}): {} {}",
            extra_args,
            String::from_utf8_lossy(&out.stdout).trim(),
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    Ok(())
}

fn run_resize2fs(img: &Path, shrink_min: bool) -> Result<()> {
    let bin = resolve_tool("resize2fs");
    let mut cmd = Command::new(&bin);
    if shrink_min {
        cmd.arg("-M");
    }
    cmd.arg(img);
    let out = cmd
        .output()
        .with_context(|| format!("Failed to execute {}", bin.display()))?;
    if !out.status.success() {
        bail!(
            "resize2fs (shrink={shrink_min}) failed: {} {}",
            String::from_utf8_lossy(&out.stdout).trim(),
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    Ok(())
}

struct RenameBackGuard {
    current: PathBuf,
    original: PathBuf,
    active: bool,
}

impl RenameBackGuard {
    fn move_to(original: &Path, temp_short: &Path) -> Result<Self> {
        fs::rename(original, temp_short).with_context(|| {
            format!(
                "Failed to move {} -> {} for short-path loop mount",
                original.display(),
                temp_short.display()
            )
        })?;
        Ok(Self {
            current: temp_short.to_path_buf(),
            original: original.to_path_buf(),
            active: true,
        })
    }

    fn restore(mut self) -> Result<()> {
        if self.active {
            self.active = false;
            fs::rename(&self.current, &self.original).with_context(|| {
                format!(
                    "Failed to restore {} -> {}",
                    self.current.display(),
                    self.original.display()
                )
            })?;
        }
        Ok(())
    }
}

impl Drop for RenameBackGuard {
    fn drop(&mut self) {
        if self.active {
            let _ = fs::rename(&self.current, &self.original);
            self.active = false;
        }
    }
}

struct LoopMountGuard {
    mnt_dir: PathBuf,
    mounted: bool,
}

impl LoopMountGuard {
    fn mount(img: &Path, mnt_dir: &Path, rw: bool) -> Result<Self> {
        fs::create_dir_all(mnt_dir)?;
        let opts = if rw { "rw,loop" } else { "ro,loop" };
        let mount_bin = resolve_tool("mount");
        let out = Command::new(&mount_bin)
            .args(["-o", opts])
            .arg(img)
            .arg(mnt_dir)
            .output()
            .with_context(|| format!("Failed to execute {}", mount_bin.display()))?;
        if !out.status.success() {
            let _ = fs::remove_dir(mnt_dir);
            bail!(
                "mount -o {opts} {} {} failed: {}",
                img.display(),
                mnt_dir.display(),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(Self {
            mnt_dir: mnt_dir.to_path_buf(),
            mounted: true,
        })
    }

    fn unmount(mut self) -> Result<()> {
        if self.mounted {
            let _ = Command::new(resolve_tool("sync")).status();
            let umount_bin = resolve_tool("umount");
            let out = Command::new(&umount_bin).arg(&self.mnt_dir).output()?;
            self.mounted = false;
            let _ = fs::remove_dir(&self.mnt_dir);
            if !out.status.success() {
                bail!(
                    "umount {} failed: {}",
                    self.mnt_dir.display(),
                    String::from_utf8_lossy(&out.stderr).trim()
                );
            }
        }
        Ok(())
    }
}

impl Drop for LoopMountGuard {
    fn drop(&mut self) {
        if self.mounted {
            let _ = Command::new(resolve_tool("sync")).status();
            let _ = Command::new(resolve_tool("umount")).arg(&self.mnt_dir).status();
            self.mounted = false;
        }
        let _ = fs::remove_dir(&self.mnt_dir);
    }
}

fn find_plat_genfs_cils(mnt: &Path) -> Vec<PathBuf> {
    let mut results = Vec::new();
    for rel in ["system/etc/selinux", "etc/selinux"] {
        let dir = mnt.join(rel);
        if let Ok(entries) = fs::read_dir(&dir) {
            for entry in entries.flatten() {
                let name = entry.file_name();
                let name_str = name.to_string_lossy();
                if name_str.starts_with("plat_sepolicy_genfs_") && name_str.ends_with(".cil") {
                    results.push(entry.path());
                }
            }
        }
    }
    results.sort();
    results
}

/// Inspect a raw GSI ext4 image for `plat_sepolicy_genfs_*.cil` rules colliding with host
/// `/vendor/etc/selinux/vendor_sepolicy.cil` (and `/odm/etc/selinux/odm_sepolicy.cil`).
/// - Zero-overhead fast path: mounts RO first; if 0 conflicts, returns `Ok(0)` immediately.
/// - Auto-patch path: expands +512 MiB, unshares ext4 `shared_blocks`, strips colliding lines,
///   restores SELinux file context, and shrinks ext4 back with `resize2fs -M`.
fn patch_gsi_vendor_genfs_conflicts(work_img: &Path, short_parent: &Path) -> Result<usize> {
    // 1. Collect host vendor genfscon rules
    let mut vendor_keys = HashSet::new();
    let mut loaded_host_files = 0usize;
    for host_cil in [
        Path::new("/vendor/etc/selinux/vendor_sepolicy.cil"),
        Path::new("/odm/etc/selinux/odm_sepolicy.cil"),
    ] {
        if let Ok(text) = fs::read_to_string(host_cil) {
            vendor_keys.extend(collect_genfscon_keys(&text));
            loaded_host_files += 1;
        }
    }
    if loaded_host_files == 0 || vendor_keys.is_empty() {
        emit_log(
            "info",
            "Host vendor_sepolicy.cil not present; skipping SELinux genfscon conflict check.",
        );
        return Ok(0);
    }

    if !is_ext4_image(work_img)? {
        emit_log(
            "info",
            "GSI image is not ext4; skipping automatic SELinux genfscon check.",
        );
        return Ok(0);
    }

    // 2. Stage at a <= 60 byte path (Android toybox losetup rejects paths > 64 bytes after symlink
    //    resolution, and FUSE /storage/emulated/0 avoids F2FS inlinecrypt RW loop I/O errors!)
    let canon_parent = fs::canonicalize(short_parent).unwrap_or_else(|_| short_parent.to_path_buf());
    let candidate = canon_parent.join(".g.img");
    let short_img = if candidate.to_string_lossy().len() <= 60 {
        candidate
    } else if Path::new("/storage/emulated/0/Download").is_dir() {
        PathBuf::from("/storage/emulated/0/Download/.g.img")
    } else {
        candidate
    };
    if short_img.exists() {
        let _ = fs::remove_file(&short_img);
    }
    let rename_guard = RenameBackGuard::move_to(work_img, &short_img)?;

    let mnt_dir = PathBuf::from(format!("/data/local/tmp/avbf_gmnt_{}", std::process::id()));

    // 3. Fast read-only pre-scan
    let ro_guard = match LoopMountGuard::mount(&short_img, &mnt_dir, false) {
        Ok(g) => g,
        Err(e) => {
            emit_log(
                "warn",
                &format!("Unable to loop-mount GSI for SELinux genfscon check (need root): {e}; skipping auto-patch."),
            );
            rename_guard.restore()?;
            return Ok(0);
        }
    };

    let mut total_conflicts = 0usize;
    let mut distinct_conflicts: Vec<(String, String)> = Vec::new();
    let mut seen_conflicts: HashSet<(String, String)> = HashSet::new();
    let cil_files = find_plat_genfs_cils(&mnt_dir);
    for cil in &cil_files {
        if let Ok(text) = fs::read_to_string(cil) {
            let (_, cnt, hits) = filter_conflicting_genfscon(&text, &vendor_keys);
            total_conflicts += cnt;
            for h in hits {
                if seen_conflicts.insert(h.clone()) {
                    distinct_conflicts.push(h);
                }
            }
        }
    }
    ro_guard.unmount()?;

    if total_conflicts == 0 {
        emit_log(
            "info",
            &format!(
                "SELinux genfscon check passed ({} plat_sepolicy_genfs file(s) scanned, 0 conflicts with host vendor).",
                cil_files.len()
            ),
        );
        rename_guard.restore()?;
        return Ok(0);
    }

    let conflict_summary = distinct_conflicts
        .iter()
        .map(|(fs_t, p)| format!("{fs_t}:{p}"))
        .collect::<Vec<_>>()
        .join(", ");
    emit_log(
        "info",
        &format!(
            "Detected {total_conflicts} SELinux genfscon conflict(s) with host vendor_sepolicy.cil ({conflict_summary}). Auto-patching GSI ext4..."
        ),
    );

    // 4. Expand +512 MiB and unshare ext4 shared_blocks so RW mount & edits succeed cleanly
    let cur_len = fs::metadata(&short_img)?.len();
    {
        let f = fs::OpenOptions::new().write(true).open(&short_img)?;
        f.set_len(cur_len + 512 * 1024 * 1024)?;
        f.sync_all()?;
    }
    run_e2fsck(&short_img, &[])?;
    run_resize2fs(&short_img, false)?;
    run_e2fsck(&short_img, &["-E", "unshare_blocks"])?;
    run_e2fsck(&short_img, &[])?;

    // 5. Mount RW and strip colliding genfscon lines
    let rw_guard = LoopMountGuard::mount(&short_img, &mnt_dir, true)?;
    let mut patched_lines = 0usize;
    let mut patched_files = 0usize;
    for cil in find_plat_genfs_cils(&mnt_dir) {
        let text = fs::read_to_string(&cil)?;
        let (filtered, removed, _) = filter_conflicting_genfscon(&text, &vendor_keys);
        if removed > 0 {
            fs::write(&cil, filtered.as_bytes())?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = fs::set_permissions(&cil, fs::Permissions::from_mode(0o644));
            }
            let _ = Command::new(resolve_tool("chcon"))
                .args(["u:object_r:system_file:s0"])
                .arg(&cil)
                .status();
            patched_lines += removed;
            patched_files += 1;
        }
    }
    rw_guard.unmount()?;

    // 6. Shrink ext4 back to minimal size before Merkle hashtree signing
    run_e2fsck(&short_img, &[])?;
    run_resize2fs(&short_img, true)?;

    rename_guard.restore()?;
    emit_log(
        "info",
        &format!(
            "Auto-patched {patched_lines} conflicting SELinux genfscon line(s) across {patched_files} file(s) and shrunk ext4 image."
        ),
    );
    Ok(patched_lines)
}

struct TempFileGuard(Option<PathBuf>);
impl Drop for TempFileGuard {
    fn drop(&mut self) {
        if let Some(p) = self.0.take() {
            let _ = fs::remove_file(p);
        }
    }
}

pub fn dsu_install(
    signed_gsi_path: &Path,
    userdata_size_bytes: u64,
    single_boot: bool,
    expected_pubkey_path: Option<&Path>,
) -> Result<String> {
    let gsi_size = fs::metadata(signed_gsi_path)?.len();
    let en_cmd = if single_boot { "gsi_tool enable -s && gsi_tool status" } else { "gsi_tool enable && gsi_tool status" };

    let mut plan_steps = Vec::new();
    if expected_pubkey_path.is_some() {
        plan_steps.push(PlannedStep::new(
            "dsu_verify_gsi",
            "安装前离线验签 GSI 镜像",
            format!("avbforge-core avb verify --image {}", signed_gsi_path.display()),
        ));
    }
    plan_steps.push(PlannedStep::new(
        "dsu_stage",
        "暂存镜像到 /data/gsi（适配 SELinux）",
        format!("avbforge-core dsu stage --bytes {gsi_size}"),
    ));
    plan_steps.push(PlannedStep::new(
        "dsu_gsi_install",
        "通过原生 gsi_tool 写入 DSU 分区",
        format!(
            "gsi_tool install --gsi-size {gsi_size} --userdata-size {userdata_size_bytes} --wipe --no-reboot"
        ),
    ));
    plan_steps.push(PlannedStep::new(
        "dsu_enable_verify",
        "启用 DSU 并回读 gsi_tool 状态",
        en_cmd,
    ));
    emit_plan(plan_steps);

    if let Some(pk_path) = expected_pubkey_path {
        run_step(
            "dsu_verify_gsi",
            "安装前离线验签 GSI 镜像",
            format!("avbforge-core avb verify --image {}", signed_gsi_path.display()),
            Some(GateKind::OfflineVerify),
            "PASS",
            |step| {
                let pk = fs::read(pk_path)?;
                verify_image(signed_gsi_path, Some(&pk), false, None, false)
                    .context("Refusing DSU install: GSI failed AVB verification against selected key")?;
                step.kv("gsi_verified", &[("bytes", &gsi_size.to_string())]);
                step.ok_line("GSI AVB signature matches selected key");
                Ok(())
            },
        )?;
    }

    // Clean up any stale/partial previous DSU state before installing
    let _ = Command::new("/system/bin/gsi_tool").arg("wipe").output();

    // Android's gsid SELinux policy denies reading FUSE (/storage/emulated/0) files directly,
    // while explicitly allowing gsi_data_file under /data/gsi/. Stage into /data/gsi/ and
    // auto-clean via TempFileGuard so gsid reads a properly-labeled gsi_data_file descriptor.
    let mut stage_guard = TempFileGuard(None);
    let feed_path: PathBuf = run_step(
        "dsu_stage",
        "暂存镜像到 /data/gsi（适配 SELinux）",
        format!("avbforge-core dsu stage --bytes {gsi_size}"),
        None,
        "STAGED",
        |step| {
            let p = if signed_gsi_path.starts_with("/data/gsi") {
                step.info("Image already under /data/gsi, zero-copy pass-through.");
                signed_gsi_path.to_path_buf()
            } else if Path::new("/data/gsi").is_dir() {
                let staged = PathBuf::from(format!("/data/gsi/.avbforge_stage_{}.img", std::process::id()));
                emit_log(
                    "info",
                    &format!("Staging signed GSI into /data/gsi/ for gsid ({gsi_size} B)..."),
                );
                stage_guard.0 = Some(staged.clone());
                let mut src = fs::File::open(signed_gsi_path)?;
                let mut dst = fs::File::create(&staged)?;
                let mut buf = vec![0u8; 4 * 1024 * 1024];
                let mut copied = 0u64;
                let mut last_rep = 0u64;
                loop {
                    let n = src.read(&mut buf)?;
                    if n == 0 {
                        break;
                    }
                    dst.write_all(&buf[..n])?;
                    copied += n as u64;
                    if copied - last_rep >= 32 * 1024 * 1024 || copied == gsi_size {
                        last_rep = copied;
                        emit_progress("stage", copied, gsi_size);
                    }
                }
                dst.sync_all()?;
                step.kv("staged", &[("path", &staged.display().to_string()), ("bytes", &copied.to_string())]);
                staged
            } else {
                signed_gsi_path.to_path_buf()
            };
            Ok(p)
        },
    )?;

    run_step(
        "dsu_gsi_install",
        "通过原生 gsi_tool 写入 DSU 分区",
        format!(
            "gsi_tool install --gsi-size {gsi_size} --userdata-size {userdata_size_bytes} --wipe --no-reboot"
        ),
        Some(GateKind::BlockFlash),
        "INSTALLED",
        |step| {
            let img_file = fs::File::open(&feed_path)?;
            emit_log(
                "info",
                &format!("Installing DSU via gsi_tool (gsi={gsi_size} B, userdata={userdata_size_bytes} B)..."),
            );

            let install_args = [
                "install",
                "--gsi-size",
                &gsi_size.to_string(),
                "--userdata-size",
                &userdata_size_bytes.to_string(),
                "--wipe",
                "--no-reboot",
            ];
            let out = Command::new("/system/bin/gsi_tool")
                .args(install_args)
                .stdin(Stdio::from(img_file))
                .output()
                .context("Failed to execute /system/bin/gsi_tool")?;

            if !out.status.success() {
                let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
                let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
                bail!(
                    "gsi_tool install failed: {}{}",
                    stderr,
                    if stdout.is_empty() { String::new() } else { format!(" | {stdout}") }
                );
            }
            step.kv(
                "gsi_tool install",
                &[("gsi_bytes", &gsi_size.to_string()), ("userdata_bytes", &userdata_size_bytes.to_string())],
            );
            step.ok_line("gsi_tool install completed");
            Ok(())
        },
    )?;
    drop(stage_guard);

    let status = run_step(
        "dsu_enable_verify",
        "启用 DSU 并回读 gsi_tool 状态",
        en_cmd,
        Some(GateKind::ReadbackVerify),
        "ENABLED",
        |step| {
            let en_args: &[&str] = if single_boot {
                &["enable", "-s"]
            } else {
                &["enable"]
            };
            let en = Command::new("/system/bin/gsi_tool").args(en_args).output()?;
            if !en.status.success() {
                bail!(
                    "gsi_tool {:?} failed: {}",
                    en_args,
                    String::from_utf8_lossy(&en.stderr)
                );
            }
            let status = dsu_cmd(&["status"])?;
            step.kv("dsu_status", &[("single_boot", &single_boot.to_string())]);
            step.ok_line(status.replace('\n', " · "));
            Ok(status)
        },
    )?;

    Ok(status)
}

pub fn dsu_cmd(args: &[&str]) -> Result<String> {
    let out = Command::new("/system/bin/gsi_tool")
        .args(args)
        .output()
        .context("Failed to execute /system/bin/gsi_tool")?;
    if !out.status.success() {
        bail!(
            "gsi_tool {:?} failed: {}",
            args,
            String::from_utf8_lossy(&out.stderr)
        );
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

pub(crate) fn parse_gsi_tool_status(raw: &str) -> DsuInfoReport {
    let mut installed = false;
    let mut enabled = false;
    let mut running = false;
    let mut slot_name: Option<String> = None;
    let mut avb_pubkey_sha1: Option<String> = None;
    let mut current_partition: Option<String> = None;

    for line in raw.lines().map(str::trim) {
        if line.is_empty() {
            continue;
        }
        match line {
            "installed" => installed = true,
            "enabled" => enabled = true,
            "disabled" => enabled = false,
            "running" => {
                running = true;
                installed = true;
            }
            _ => {}
        }
        if line.starts_with('[') {
            if let Some(idx) = line.find(']') {
                let s = line[idx + 1..].trim();
                if !s.is_empty() && slot_name.is_none() {
                    slot_name = Some(s.to_string());
                }
            }
        } else if let Some(rest) = line.strip_prefix("installed:") {
            current_partition = Some(rest.trim().to_string());
            installed = true;
        } else if let Some(rest) = line.strip_prefix("AVB public key (sha1):") {
            let val = rest.trim();
            if !val.is_empty()
                && !val.eq_ignore_ascii_case("[NONE]")
                && current_partition
                    .as_deref()
                    .map(|p| p.starts_with("system"))
                    .unwrap_or(true)
            {
                avb_pubkey_sha1 = Some(val.to_string());
            }
        }
    }

    DsuInfoReport {
        installed,
        enabled,
        running,
        one_shot: false,
        slot_name,
        system_size_bytes: 0,
        userdata_size_bytes: 0,
        total_disk_bytes: 0,
        avb_pubkey_sha1,
        raw_status: raw.trim().to_string(),
    }
}

fn scan_dsu_dir_sizes(dir: &Path, sys: &mut u64, usr: &mut u64, total: &mut u64) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        if meta.is_dir() {
            scan_dsu_dir_sizes(&path, sys, usr, total);
        } else if meta.is_file() {
            let len = meta.len();
            *total += len;
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with("system_gsi") {
                *sys += len;
            } else if name.starts_with("userdata_gsi") {
                *usr += len;
            }
        }
    }
}

pub fn dsu_info() -> Result<DsuInfoReport> {
    let raw = dsu_cmd(&["status"]).unwrap_or_else(|_| "unknown".to_string());
    let mut info = parse_gsi_tool_status(&raw);

    if info.installed {
        let mut sys = 0u64;
        let mut usr = 0u64;
        let mut total = 0u64;
        let base = Path::new("/data/gsi/dsu");
        if base.is_dir() {
            scan_dsu_dir_sizes(base, &mut sys, &mut usr, &mut total);
        }
        info.system_size_bytes = sys;
        info.userdata_size_bytes = usr;
        info.total_disk_bytes = total;
    }

    if info.enabled {
        let one_shot_path = Path::new("/metadata/gsi/dsu/one_shot_boot");
        let slot_one_shot = info
            .slot_name
            .as_deref()
            .map(|s| PathBuf::from(format!("/metadata/gsi/dsu/{s}/one_shot_boot")));
        info.one_shot = one_shot_path.exists()
            || slot_one_shot.as_ref().map(|p| p.exists()).unwrap_or(false);
    }

    Ok(info)
}

pub fn dsu_reboot() -> Result<String> {
    emit_log("info", "Issuing system reboot...");
    let status = Command::new("/system/bin/svc")
        .args(["power", "reboot"])
        .status();
    if matches!(status, Ok(s) if s.success()) {
        return Ok("rebooting".to_string());
    }
    let out = Command::new("/system/bin/reboot")
        .output()
        .context("Failed to execute /system/bin/reboot")?;
    if !out.status.success() {
        bail!("reboot failed: {}", String::from_utf8_lossy(&out.stderr));
    }
    Ok("rebooting".to_string())
}

pub fn export_stock_init_boot(output_path: &Path, slot_override: Option<&str>) -> Result<PathBuf> {
    let slot = match slot_override {
        Some(s) => s.to_string(),
        None => crate::device::getprop("ro.boot.slot_suffix"),
    };
    let dev = PathBuf::from(format!("/dev/block/by-name/init_boot{slot}"));
    if !dev.exists() {
        bail!("Device {} not found", dev.display());
    }
    if let Some(p) = output_path.parent() {
        fs::create_dir_all(p)?;
    }
    let sz = crate::device::blockdev_size(&dev)?;
    crate::device::copy_block_head(&dev, output_path, sz)?;
    Ok(output_path.to_path_buf())
}

/// Export active/specified slot `init_boot.img`, optionally saving a raw backup first and stripping
/// any pre-existing KernelSU LKM patch (`init.real -> init`, removing `kernelsu.ko` & `ksu_config`)
/// so the exported image is a clean stock base safe to feed into ANY KernelSU manager.
pub fn export_clean_init_boot(
    output_path: &Path,
    slot_override: Option<&str>,
    strip_ksu: bool,
    raw_backup_output: Option<&Path>,
) -> Result<ExportCleanInitBootReport> {
    emit_plan(vec![
        PlannedStep::new(
            "export_live_init_boot",
            "备份当前活动槽位 init_boot",
            format!("avbforge-core export-init-boot --output {}", output_path.display()),
        ),
        PlannedStep::new(
            "strip_ksu_ramdisk",
            "检测并剥离旧版 KernelSU 导出纯净底包",
            format!("avbforge-core export-clean-init-boot --strip-ksu {strip_ksu}"),
        ),
    ]);

    let tmp_dir = tempfile::tempdir()?;
    let raw_dump = tmp_dir.path().join("init_boot_live.img");
    let backup_path_str = run_step(
        "export_live_init_boot",
        "备份当前活动槽位 init_boot",
        format!("avbforge-core export-init-boot --output {}", output_path.display()),
        None,
        "EXPORTED",
        |step| {
            export_stock_init_boot(&raw_dump, slot_override)?;
            let bkp_str = if let Some(bkp) = raw_backup_output {
                if let Some(parent) = bkp.parent() {
                    fs::create_dir_all(parent)?;
                }
                fs::copy(&raw_dump, bkp)?;
                emit_log("info", &format!("Saved raw live init_boot backup to {}", bkp.display()));
                step.kv("raw_backup", &[("path", &bkp.display().to_string())]);
                Some(bkp.display().to_string())
            } else {
                None
            };
            Ok(bkp_str)
        },
    )?;

    run_step(
        "strip_ksu_ramdisk",
        "检测并剥离旧版 KernelSU 导出纯净底包",
        format!("avbforge-core export-clean-init-boot --strip-ksu {strip_ksu}"),
        Some(GateKind::OfflineVerify),
        "CLEAN_OK",
        |step| {
            let raw_bytes = fs::read(&raw_dump)?;
            let raw_slice = match load_avb_image(&raw_dump) {
                Ok(loaded) => match loaded.footer {
                    Some(f) => &raw_bytes[..f.original_image_size as usize],
                    None => &raw_bytes[..],
                },
                Err(_) => &raw_bytes[..],
            };

            let boot = BootImageV3V4::parse(raw_slice)?;
            let info = boot.inspect()?;
            let had_kernelsu = info.has_kernelsu;
            let existing_dsu_keys = info.avbpubkey_entries;

            if let Some(parent) = output_path.parent() {
                fs::create_dir_all(parent)?;
            }

            let stripped_kernelsu = if had_kernelsu && strip_ksu {
                emit_log(
                    "info",
                    "Detected existing KernelSU LKM in init_boot; stripping to clean stock init_boot...",
                );
                let (fmt, mut cpio) = boot.unpack_ramdisk()?;
                let stripped = cpio.strip_kernelsu();
                let repacked = boot.repack_with_ramdisk(fmt, &cpio)?;
                let verify_boot = BootImageV3V4::parse(&repacked)?;
                let verify_info = verify_boot.inspect()?;
                if verify_info.has_kernelsu {
                    bail!("Failed to strip KernelSU completely from init_boot ramdisk");
                }
                fs::write(output_path, &repacked)?;
                stripped
            } else {
                fs::copy(&raw_dump, output_path)?;
                false
            };

            let size = fs::metadata(output_path)?.len();
            let sha256 = sha256_file(output_path)?;
            step.kv(
                "clean init_boot ready",
                &[
                    ("had_ksu", &had_kernelsu.to_string()),
                    ("stripped_ksu", &stripped_kernelsu.to_string()),
                    ("sha256", &sha256),
                ],
            );
            step.ok_line(format!("exported {}", output_path.display()));
            Ok(ExportCleanInitBootReport {
                output_path: output_path.display().to_string(),
                backup_path: backup_path_str,
                size,
                sha256,
                had_kernelsu,
                stripped_kernelsu,
                existing_dsu_keys,
            })
        },
    )
}


pub fn dsu_backup(out_dir: &Path) -> Result<serde_json::Value> {
    fs::create_dir_all(out_dir)?;
    let status = dsu_cmd(&["status"]).unwrap_or_else(|_| "unknown".to_string());
    fs::write(out_dir.join("gsi_status.txt"), &status)?;

    let base = Path::new("/data/gsi/dsu");
    let mut copied_files = Vec::new();
    let mut total_bytes = 0u64;
    if base.is_dir() {
        for entry in fs::read_dir(base)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_file() {
                let name = entry.file_name().to_string_lossy().to_string();
                let dst = out_dir.join(&name);
                emit_log("info", &format!("Backing up DSU file {name}..."));
                let n = fs::copy(&path, &dst)?;
                total_bytes += n;
                copied_files.push(name);
            } else if path.is_dir() {
                for sub in fs::read_dir(&path)? {
                    let sub = sub?;
                    let sp = sub.path();
                    if sp.is_file() {
                        let name = sub.file_name().to_string_lossy().to_string();
                        if name.starts_with("system_gsi") || name.starts_with("userdata_gsi") {
                            let dst = out_dir.join(&name);
                            emit_log("info", &format!("Backing up DSU image {name}..."));
                            let n = fs::copy(&sp, &dst)?;
                            total_bytes += n;
                            copied_files.push(name);
                        }
                    }
                }
            }
        }
    }
    Ok(serde_json::json!({
        "output_dir": out_dir.display().to_string(),
        "files": copied_files,
        "total_bytes": total_bytes,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn genfscon_parser_and_conflict_filter() {
        let vendor_cil = r#"
(genfscon sysfs "/power/mem_sleep" (u object_r vendor_sysfs_suspend ((s0) (s0))))
(genfscon sysfs "/class/typec" (u object_r vendor_sysfs_usb_c ((s0) (s0))))
(typeattributeset base_typeattr_1 (vendor_sysfs_suspend))
"#;
        let vendor_keys = collect_genfscon_keys(vendor_cil);
        assert_eq!(vendor_keys.len(), 2);
        assert!(vendor_keys.contains(&("sysfs".into(), "/power/mem_sleep".into())));
        assert!(vendor_keys.contains(&("sysfs".into(), "/class/typec".into())));

        let plat_cil = r#"(genfscon sysfs "/class/udc" (u object_r sysfs_udc ((s0) (s0))))
(genfscon sysfs "/power/mem_sleep" (u object_r sysfs_power ((s0) (s0))))
(genfscon sysfs "/class/typec" (u object_r sysfs_typec ((s0) (s0))))
(genfscon sysfs "/kernel/firmware_config" (u object_r sysfs_fw ((s0) (s0))))
"#;
        let (filtered, removed, hits) = filter_conflicting_genfscon(plat_cil, &vendor_keys);
        assert_eq!(removed, 2);
        assert_eq!(hits.len(), 2);
        assert!(filtered.contains("\"/class/udc\""));
        assert!(filtered.contains("\"/kernel/firmware_config\""));
        assert!(!filtered.contains("\"/power/mem_sleep\""));
        assert!(!filtered.contains("\"/class/typec\""));
    }

    #[test]
    fn parse_gsi_tool_status_variants() {
        let normal = parse_gsi_tool_status("normal\n");
        assert!(!normal.installed);
        assert!(!normal.enabled);
        assert!(normal.avb_pubkey_sha1.is_none());

        let disabled = parse_gsi_tool_status(
            "installed\ndisabled\n[0] dsu\ninstalled: userdata_gsi\nAVB public key (sha1): [NONE]\ninstalled: system_gsi\nAVB public key (sha1): 1c69d097e111ed8ae814fc7bf8957ec0721c654d\n",
        );
        assert!(disabled.installed);
        assert!(!disabled.enabled);
        assert_eq!(disabled.slot_name.as_deref(), Some("dsu"));
        assert_eq!(
            disabled.avb_pubkey_sha1.as_deref(),
            Some("1c69d097e111ed8ae814fc7bf8957ec0721c654d")
        );

        let enabled = parse_gsi_tool_status("installed\nenabled\n[0] dsu\n");
        assert!(enabled.installed);
        assert!(enabled.enabled);
    }
}
