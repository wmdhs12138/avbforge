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
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use crate::device::{sha256_file, STANDARD_BOOT_PARTITIONS};
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

    // Load raw unsigned boot bytes from input_img
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
        // Inject into both `first_stage_ramdisk/avb/<name>` and `avb/<name>` (covers force_normal_boot & direct rootfs)
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

    if let Some(parent) = output_img.parent() {
        fs::create_dir_all(parent)?;
    }

    // Stage in tempdir with canonical `<partition_name>.img` name for verify_image
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

    Ok(PatchDsuKeyReport {
        output_path: output_img.display().to_string(),
        size: out_bytes.len() as u64,
        sha256,
        ramdisk_compression: fmt,
        injected_paths,
        has_kernelsu: re_info.has_kernelsu,
        avb_pubkey_sha1: pub_key.sha1_hex(),
    })
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

    let mut signed_partitions = Vec::new();
    let mut init_boot_has_ksu = false;

    // 1. Re-sign all hash-footer boot partitions present in stock_dir
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
                let rep = patch_dsu_key(
                    src_for_init_boot,
                    &out_img,
                    key_pem,
                    passphrase,
                    "init_boot",
                    dsu_key_name,
                    Some(&stock_img),
                    true,
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
        signed_partitions.push(part.to_string());
    }

    // 2. Re-sign vbmeta_system preserving all original descriptors (odm/product/system/system_ext/vendor/dlkm!)
    let stock_vbmeta_sys = stock_dir.join("vbmeta_system.img");
    if stock_vbmeta_sys.exists() {
        emit_log("info", "Re-signing vbmeta_system.img (preserving all logical partition hashtrees)");
        let loaded_sys = load_avb_image(&stock_vbmeta_sys)?;
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
        signed_partitions.push("vbmeta_system".to_string());
    }

    // 3. Rebuild top-level vbmeta.img replacing chain descriptor pubkeys with our custom pubkey
    let mut new_vbmeta_descs = Vec::new();
    for d in orig_vbmeta.vbmeta.descriptors {
        match d {
            AvbDescriptor::ChainPartition(mut cp) => {
                if output_dir.join(format!("{}.img", cp.partition_name)).exists() {
                    cp.public_key = pub_key.raw_avb_key.clone();
                }
                new_vbmeta_descs.push(AvbDescriptor::ChainPartition(cp));
            }
            other => new_vbmeta_descs.push(other),
        }
    }
    let out_vbmeta = output_dir.join("vbmeta.img");
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
    signed_partitions.push("vbmeta".to_string());

    // 4. Mandatory full-chain verification!
    emit_log("info", "Running full-chain AVB verification on forged bundle...");
    let verified = verify_image(&out_vbmeta, Some(&pub_key.raw_avb_key), true, None, true)
        .context("Forged bundle failed full-chain AVB verification")?;

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

/// Decompress (if .xz/.gz), unsparse (if sparse), strip old footer, and sign a GSI with AVB hashtree footer.
/// Automatically injects `com.android.build.system.security_patch` (>= host SPL) so locked Yellow
/// devices pass AOSP `IsStandaloneImageRollback()` during `first_stage_mount`!
pub fn sign_gsi(
    input_path: &Path,
    output_path: &Path,
    key_pem: &Path,
    passphrase: Option<&str>,
    partition_size: Option<u64>,
    security_patch_override: Option<&str>,
) -> Result<GsiSignReport> {
    let priv_key = AvbPrivateKey::from_pem_file(key_pem, passphrase)?;
    let pub_key = priv_key.public_key()?;
    let alg = priv_key.default_algorithm()?;

    if let Some(p) = output_path.parent() {
        fs::create_dir_all(p)?;
    }

    let tmp_dir = tempfile::tempdir_in(output_path.parent().unwrap_or_else(|| Path::new(".")))?;
    let work_img = tmp_dir.path().join("system.img");

    // Step 1: stream decompress (.img / .gz / .xz)
    emit_log("info", &format!("Preparing GSI from {}...", input_path.display()));
    let fmt = stream_decompress_file(input_path, &work_img, |bytes| {
        emit_progress("decompress", bytes, 0);
    })?;
    emit_log("info", &format!("Input compression detected: {fmt:?}"));

    // Step 2: unsparse if Android sparse image
    if is_sparse_image(&work_img)? {
        emit_log("info", "Detected Android sparse image, converting to raw...");
        let raw_img = tmp_dir.path().join("system_raw.img");
        unsparse_image(&work_img, &raw_img, |cur, tot| {
            emit_progress("unsparse", cur, tot);
        })?;
        fs::rename(&raw_img, &work_img)?;
    }

    // Step 3: preserve useful AVB properties (especially security_patch) before erasing old footer
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

    if erase_footer(&work_img)? {
        emit_log("info", "Stripped pre-existing AVB footer from GSI.");
    }

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
    emit_log(
        "info",
        &format!("Embedding AVB security_patch={effective_spl} (host={host_spl}) for locked DSU anti-rollback check."),
    );

    // Step 4: compute Merkle hashtree & sign
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

    // Step 5: verify generated GSI image
    emit_log("info", "Verifying signed GSI AVB footer & hashtree...");
    verify_image(&work_img, Some(&pub_key.raw_avb_key), false, None, false)?;

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
    })
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
    if let Some(pk_path) = expected_pubkey_path {
        let pk = fs::read(pk_path)?;
        verify_image(signed_gsi_path, Some(&pk), false, None, false)
            .context("Refusing DSU install: GSI failed AVB verification against selected key")?;
    }
    let gsi_size = fs::metadata(signed_gsi_path)?.len();

    // Clean up any stale/partial previous DSU state before installing
    let _ = Command::new("/system/bin/gsi_tool").arg("wipe").output();

    // Android's gsid SELinux policy denies reading FUSE (/storage/emulated/0) files directly,
    // while explicitly allowing gsi_data_file under /data/gsi/. Stage into /data/gsi/ and
    // auto-clean via TempFileGuard so gsid reads a properly-labeled gsi_data_file descriptor.
    let mut stage_guard = TempFileGuard(None);
    let feed_path: PathBuf = if signed_gsi_path.starts_with("/data/gsi") {
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
        staged
    } else {
        signed_gsi_path.to_path_buf()
    };

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
    drop(stage_guard);

    if !out.status.success() {
        let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
        let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
        bail!(
            "gsi_tool install failed: {}{}",
            stderr,
            if stdout.is_empty() { String::new() } else { format!(" | {stdout}") }
        );
    }

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
    let tmp_dir = tempfile::tempdir()?;
    let raw_dump = tmp_dir.path().join("init_boot_live.img");
    export_stock_init_boot(&raw_dump, slot_override)?;

    let backup_path_str = if let Some(bkp) = raw_backup_output {
        if let Some(parent) = bkp.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(&raw_dump, bkp)?;
        emit_log("info", &format!("Saved raw live init_boot backup to {}", bkp.display()));
        Some(bkp.display().to_string())
    } else {
        None
    };

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
        // Verify repacked image is valid and KSU-free
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
    Ok(ExportCleanInitBootReport {
        output_path: output_path.display().to_string(),
        backup_path: backup_path_str,
        size,
        sha256,
        had_kernelsu,
        stripped_kernelsu,
        existing_dsu_keys,
    })
}
