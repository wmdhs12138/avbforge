use anyhow::{bail, Context, Result};
use avb2::{load_avb_image, verify_image, AvbDescriptor};
use bootimg::{parse_avb_keys_from_fstab, BootImageV3V4};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

use crate::events::whitebox::{emit_plan, run_step, GateKind, PlannedStep};
use crate::events::{emit_log, emit_progress};

pub const STANDARD_BOOT_PARTITIONS: &[&str] = &[
    "boot",
    "init_boot",
    "vendor_boot",
    "dtbo",
    "recovery",
    "vbmeta",
    "vbmeta_system",
];

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PartitionSizeInfo {
    pub name: String,
    pub size_bytes: u64,
    pub in_vbmeta_chain: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DeviceSnapshot {
    pub model: String,
    pub device: String,
    pub manufacturer: String,
    pub android_release: String,
    pub sdk_int: u32,
    pub vndk_version: String,
    pub kernel_release: String,
    pub active_slot: String,
    pub verified_boot_state: String,
    pub device_state: String,
    pub sdk_compatibility: String,
    pub ksu_version: Option<String>,
    pub init_boot_has_ksu: Option<bool>,
    pub init_boot_avbpubkeys: Vec<String>,
    pub vbmeta_pubkey_sha1: Option<String>,
    pub vbmeta_chained_partitions: Vec<String>,
    pub fstab_avb_keys: Vec<String>,
    pub dsu_status: String,
    pub freezer_enabled: bool,
    pub app_in_doze_whitelist: bool,
    pub partition_sizes: Vec<PartitionSizeInfo>,
    #[serde(default)]
    pub firmware_version: String,
    #[serde(default)]
    pub security_patch: String,
    #[serde(default)]
    pub selinux_mode: String,
    #[serde(default)]
    pub widevine_level: String,
    #[serde(default)]
    pub vbmeta_rollback_index: Option<u64>,
    #[serde(default)]
    pub root_provider: Option<String>,
    #[serde(default)]
    pub root_is_temporary: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DevinfoWriteReport {
    pub device_path: String,
    pub backup_path: Option<String>,
    pub pkmd_len: usize,
    pub keep_locked: bool,
    pub sha256: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PartitionBackupEntry {
    pub partition: String,
    pub slot: String,
    pub filename: String,
    pub size: u64,
    pub sha256: String,
    pub avb_pubkey_sha1: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BackupManifest {
    pub model: String,
    pub slot: String,
    pub android_release: String,
    pub sdk_int: u32,
    pub created_unix: u64,
    pub partitions: Vec<PartitionBackupEntry>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FlashReport {
    pub partition: String,
    pub slot: String,
    pub device_path: String,
    pub size: u64,
    pub sha256: String,
    pub avb_verified: bool,
}

pub fn getprop(name: &str) -> String {
    Command::new("getprop")
        .arg(name)
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default()
}

pub fn inspect_device(app_package: Option<&str>) -> Result<DeviceSnapshot> {
    let model = getprop("ro.product.model");
    let device = getprop("ro.product.device");
    let manufacturer = getprop("ro.product.manufacturer");
    let android_release = getprop("ro.build.version.release");
    let sdk_int: u32 = getprop("ro.build.version.sdk").parse().unwrap_or(0);
    let vndk_version = getprop("ro.vndk.version");
    let active_slot = getprop("ro.boot.slot_suffix");
    let verified_boot_state = getprop("ro.boot.verifiedbootstate");
    let device_state = getprop("ro.boot.vbmeta.device_state");
    let kernel_release = Command::new("uname")
        .arg("-r")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default();

    let sdk_compatibility = match sdk_int {
        34 => "verified_a14".to_string(),
        35.. => "high_risk_a15_plus".to_string(),
        31..=33 => "caution_a12_a13".to_string(),
        _ => "unknown".to_string(),
    };

    let ksu_version = Command::new("/data/adb/ksu/bin/ksud")
        .arg("-V")
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string());

    let dsu_status = Command::new("/system/bin/gsi_tool")
        .arg("status")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "unknown".to_string());

    // Read fstab avb_keys from /vendor/etc/fstab.*
    let mut fstab_avb_keys = Vec::new();
    if let Ok(entries) = fs::read_dir("/vendor/etc") {
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name.starts_with("fstab.") {
                if let Ok(text) = fs::read_to_string(e.path()) {
                    for k in parse_avb_keys_from_fstab(&text) {
                        if !fstab_avb_keys.contains(&k) {
                            fstab_avb_keys.push(k);
                        }
                    }
                }
            }
        }
    }

    // Inspect live vbmeta_<slot>
    let mut vbmeta_pubkey_sha1 = None;
    let mut vbmeta_rollback_index = None;
    let mut vbmeta_chained_partitions = Vec::new();
    let vbmeta_dev = PathBuf::from(format!("/dev/block/by-name/vbmeta{active_slot}"));
    if vbmeta_dev.exists() {
        if let Ok(tmp) = tempfile::NamedTempFile::new() {
            if copy_block_head(&vbmeta_dev, tmp.path(), 65536).is_ok() {
                if let Ok(loaded) = load_avb_image(tmp.path()) {
                    vbmeta_pubkey_sha1 = loaded.vbmeta.public_key_sha1();
                    vbmeta_rollback_index = Some(loaded.vbmeta.rollback_index);
                    for d in &loaded.vbmeta.descriptors {
                        if let AvbDescriptor::ChainPartition(c) = d {
                            vbmeta_chained_partitions.push(c.partition_name.clone());
                        }
                    }
                }
            }
        }
    }

    // Inspect live init_boot_<slot>
    let mut init_boot_has_ksu = None;
    let mut init_boot_avbpubkeys = Vec::new();
    let ib_dev = PathBuf::from(format!("/dev/block/by-name/init_boot{active_slot}"));
    if ib_dev.exists() {
        if let Ok(tmp) = tempfile::NamedTempFile::new() {
            if let Ok(sz) = blockdev_size(&ib_dev) {
                if copy_block_head(&ib_dev, tmp.path(), sz).is_ok() {
                    if let Ok(bytes) = fs::read(tmp.path()) {
                        let raw_slice = match load_avb_image(tmp.path()) {
                            Ok(loaded) => match loaded.footer {
                                Some(f) => &bytes[..f.original_image_size as usize],
                                None => &bytes[..],
                            },
                            Err(_) => &bytes[..],
                        };
                        if let Ok(boot) = BootImageV3V4::parse(raw_slice) {
                            if let Ok(info) = boot.inspect() {
                                init_boot_has_ksu = Some(info.has_kernelsu);
                                init_boot_avbpubkeys = info.avbpubkey_entries;
                            }
                        }
                    }
                }
            }
        }
    }

    let freezer_enabled = getprop("persist.vivo.support.cgroup_freeze") == "true"
        || getprop("ro.vendor.vivo.software.pem.frozen") == "1";

    let pkg = app_package.unwrap_or("io.github.fu.avbforge");
    let app_in_doze_whitelist = Command::new("dumpsys")
        .args(["deviceidle", "whitelist"])
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).contains(pkg))
        .unwrap_or(false);

    // Collect live sizes for standard AVB/boot partitions on active slot
    let mut partition_sizes = Vec::new();
    for &part in STANDARD_BOOT_PARTITIONS {
        let dev = PathBuf::from(format!("/dev/block/by-name/{part}{active_slot}"));
        if dev.exists() {
            if let Ok(sz) = blockdev_size(&dev) {
                let in_chain = part == "vbmeta"
                    || vbmeta_chained_partitions.iter().any(|p| p == part);
                partition_sizes.push(PartitionSizeInfo {
                    name: part.to_string(),
                    size_bytes: sz,
                    in_vbmeta_chain: in_chain,
                });
            }
        }
    }

    let firmware_version = {
        let disp = getprop("ro.build.display.id");
        if !disp.is_empty() {
            disp
        } else {
            getprop("ro.build.version.incremental")
        }
    };
    let security_patch = getprop("ro.build.version.security_patch");
    let selinux_mode = Command::new("getenforce")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Unknown".to_string());
    let widevine_level = detect_widevine_level();
    let root_provider = detect_root_provider(ksu_version.as_deref());
    let root_is_temporary = root_provider.is_some()
        && verified_boot_state.eq_ignore_ascii_case("green")
        && device_state.eq_ignore_ascii_case("locked");

    Ok(DeviceSnapshot {
        model,
        device,
        manufacturer,
        android_release,
        sdk_int,
        vndk_version,
        kernel_release,
        active_slot,
        verified_boot_state,
        device_state,
        sdk_compatibility,
        ksu_version,
        init_boot_has_ksu,
        init_boot_avbpubkeys,
        vbmeta_pubkey_sha1,
        vbmeta_chained_partitions,
        fstab_avb_keys,
        dsu_status,
        freezer_enabled,
        app_in_doze_whitelist,
        partition_sizes,
        firmware_version,
        security_patch,
        selinux_mode,
        widevine_level,
        vbmeta_rollback_index,
        root_provider,
        root_is_temporary,
    })
}

pub fn backup_partitions(
    out_dir: &Path,
    slot_override: Option<&str>,
    partitions: &[String],
) -> Result<BackupManifest> {
    fs::create_dir_all(out_dir)?;
    let slot = match slot_override {
        Some(s) => s.to_string(),
        None => getprop("ro.boot.slot_suffix"),
    };
    let list: Vec<String> = if partitions.is_empty() {
        STANDARD_BOOT_PARTITIONS.iter().map(|s| s.to_string()).collect()
    } else {
        partitions.to_vec()
    };

    emit_plan(
        list.iter()
            .map(|part| {
                PlannedStep::new(
                    format!("backup_{part}"),
                    format!("备份分区 {part}{slot}"),
                    format!("avbforge-core backup-partitions --partition {part}{slot}"),
                )
            })
            .collect(),
    );

    let mut entries = Vec::new();
    let total = list.len() as u64;
    for (idx, part) in list.iter().enumerate() {
        emit_progress("backup", idx as u64, total);
        let dev = if part == "devinfo" {
            PathBuf::from("/dev/block/by-name/devinfo")
        } else {
            PathBuf::from(format!("/dev/block/by-name/{part}{slot}"))
        };
        if !dev.exists() {
            emit_log("warn", &format!("Partition device {} not found, skipping", dev.display()));
            let _ = run_step(
                format!("backup_{part}"),
                format!("跳过不存在分区 {part}{slot}"),
                format!("avbforge-core backup-partitions --partition {part}{slot}"),
                None,
                "SKIPPED",
                |step| {
                    step.warn(format!("Partition device {} not found, skipping", dev.display()));
                    Ok(())
                },
            );
            continue;
        }
        let filename = format!("{part}.img");
        let dst = out_dir.join(&filename);
        let entry = run_step(
            format!("backup_{part}"),
            format!("备份分区 {part}{slot}"),
            format!("avbforge-core backup-partitions --partition {part}{slot} --out {}", dst.display()),
            None,
            "BACKED_UP",
            |step| {
                let sz = blockdev_size(&dev)?;
                emit_log("info", &format!("Dumping {part}{slot} ({sz} bytes) -> {}", dst.display()));
                let sha256 = copy_block_head(&dev, &dst, sz)?;
                let avb_pubkey_sha1 = load_avb_image(&dst)
                    .ok()
                    .and_then(|l| l.vbmeta.public_key_sha1());
                step.kv(
                    "partition dumped",
                    &[
                        ("device", &dev.display().to_string()),
                        ("bytes", &sz.to_string()),
                        ("sha256", &sha256),
                    ],
                );
                if let Some(ref pk) = avb_pubkey_sha1 {
                    step.kv("avb pubkey", &[("sha1", pk)]);
                }
                step.ok_line(format!("saved {}", dst.display()));
                Ok(PartitionBackupEntry {
                    partition: part.clone(),
                    slot: slot.clone(),
                    filename,
                    size: sz,
                    sha256,
                    avb_pubkey_sha1,
                })
            },
        )?;
        entries.push(entry);
    }
    emit_progress("backup", total, total);

    let created_unix = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let manifest = BackupManifest {
        model: getprop("ro.product.model"),
        slot,
        android_release: getprop("ro.build.version.release"),
        sdk_int: getprop("ro.build.version.sdk").parse().unwrap_or(0),
        created_unix,
        partitions: entries,
    };
    let manifest_path = out_dir.join("manifest.json");
    fs::write(&manifest_path, serde_json::to_string_pretty(&manifest)?)?;
    Ok(manifest)
}

pub fn flash_partition(
    partition: &str,
    image_path: &Path,
    slot_override: Option<&str>,
    expected_pubkey_path: Option<&Path>,
) -> Result<FlashReport> {
    flash_partition_inner(partition, image_path, slot_override, expected_pubkey_path, true)
}

fn flash_partition_inner(
    partition: &str,
    image_path: &Path,
    slot_override: Option<&str>,
    expected_pubkey_path: Option<&Path>,
    emit_step_plan: bool,
) -> Result<FlashReport> {
    let slot = match slot_override {
        Some(s) => s.to_string(),
        None => getprop("ro.boot.slot_suffix"),
    };
    let dev = PathBuf::from(format!("/dev/block/by-name/{partition}{slot}"));

    if emit_step_plan {
        emit_plan(vec![
            PlannedStep::new(
                format!("verify_{partition}"),
                format!("刷前离线验签 {partition}"),
                format!("avbforge-core avb verify --image {}", image_path.display()),
            ),
            PlannedStep::new(
                format!("flash_{partition}"),
                format!("写入块设备 {partition}{slot} 并落盘"),
                format!(
                    "avbforge-core flash-partition --partition {partition} --slot {slot} --fsync"
                ),
            ),
            PlannedStep::new(
                format!("readback_{partition}"),
                format!("回读 {partition}{slot} 核对 SHA-256 与 AVB"),
                format!("avbforge-core verify-readback --device {}", dev.display()),
            ),
        ]);
    }

    if !dev.exists() {
        bail!("Target partition device does not exist: {}", dev.display());
    }

    let expected_pk = match expected_pubkey_path {
        Some(p) => Some(fs::read(p)?),
        None => None,
    };

    // 1. Mandatory pre-flash AVB verification!
    let (img_sz, src_sha) = run_step(
        format!("verify_{partition}"),
        format!("刷前离线验签 {partition}"),
        format!("avbforge-core avb verify --image {}", image_path.display()),
        Some(GateKind::OfflineVerify),
        "PASS",
        |step| {
            emit_log("info", &format!("Pre-flash AVB verification on {}", image_path.display()));
            verify_image(image_path, expected_pk.as_deref(), false, None, true)
                .context("Refusing to flash: pre-flash AVB verification failed")?;

            let dev_sz = blockdev_size(&dev)?;
            let img_sz = fs::metadata(image_path)?.len();
            if img_sz > dev_sz {
                bail!("Image size ({img_sz}) exceeds target partition size ({dev_sz})");
            }
            let src_sha = sha256_file(image_path)?;
            step.kv(
                "pre-flash image verified",
                &[
                    ("partition", partition),
                    ("bytes", &img_sz.to_string()),
                    ("dev_bytes", &dev_sz.to_string()),
                    ("sha256", &src_sha),
                ],
            );
            step.ok_line("offline AVB verify passed");
            Ok((img_sz, src_sha))
        },
    )?;

    // 2. Write image to block device & flush buffers
    run_step(
        format!("flash_{partition}"),
        format!("写入块设备 {partition}{slot} 并落盘"),
        format!("avbforge-core flash-partition --partition {partition} --slot {slot} --fsync"),
        Some(GateKind::BlockFlash),
        "FLUSHED",
        |step| {
            emit_log(
                "info",
                &format!("Writing {} ({img_sz} bytes) -> {}", image_path.display(), dev.display()),
            );
            let was_ro = Command::new("blockdev")
                .args(["--getro"])
                .arg(&dev)
                .output()
                .ok()
                .map(|o| String::from_utf8_lossy(&o.stdout).trim() == "1")
                .unwrap_or(false);
            if was_ro {
                let _ = Command::new("blockdev").args(["--setrw"]).arg(&dev).status();
            }
            {
                let mut src = File::open(image_path)?;
                let mut dst = OpenOptions::new().write(true).open(&dev)?;
                let mut buf = vec![0u8; 1024 * 1024];
                let mut written = 0u64;
                while written < img_sz {
                    let n = src.read(&mut buf)?;
                    if n == 0 {
                        break;
                    }
                    dst.write_all(&buf[..n])?;
                    written += n as u64;
                    emit_progress("flash", written, img_sz);
                }
                dst.sync_all()?;
            }

            let _ = Command::new("sync").status();
            let _ = Command::new("blockdev").args(["--flushbufs"]).arg(&dev).status();
            if was_ro {
                let _ = Command::new("blockdev").args(["--setro"]).arg(&dev).status();
            }
            step.kv(
                "partition flushed",
                &[("device", &dev.display().to_string()), ("bytes", &img_sz.to_string())],
            );
            step.ok_line("fsync + sync + blockdev --flushbufs completed");
            Ok(())
        },
    )?;

    // 3. Readback SHA-256 + post-flash AVB verify
    let rb_sha = run_step(
        format!("readback_{partition}"),
        format!("回读 {partition}{slot} 核对 SHA-256 与 AVB"),
        format!("avbforge-core verify-readback --device {}", dev.display()),
        Some(GateKind::ReadbackVerify),
        "VERIFIED",
        |step| {
            let tmp_dir = tempfile::tempdir()?;
            let rb_path = tmp_dir.path().join(format!("{partition}.img"));
            let rb_sha = copy_block_head(&dev, &rb_path, img_sz)?;
            step.kv("readback digest", &[("expected", &src_sha), ("actual", &rb_sha)]);
            if rb_sha != src_sha {
                bail!("CRITICAL: Readback SHA-256 mismatch! expected={src_sha}, got={rb_sha}");
            }
            verify_image(&rb_path, expected_pk.as_deref(), false, None, true)
                .context("CRITICAL: Post-flash readback AVB verification failed")?;
            step.ok_line("readback SHA-256 & secondary AVB verify passed");
            Ok(rb_sha)
        },
    )?;

    emit_log("info", &format!("Verified readback SHA-256 ({rb_sha}) & AVB signature OK!"));
    Ok(FlashReport {
        partition: partition.to_string(),
        slot,
        device_path: dev.display().to_string(),
        size: img_sz,
        sha256: rb_sha,
        avb_verified: true,
    })
}

pub fn flash_bundle(
    bundle_dir: &Path,
    slot_override: Option<&str>,
    expected_pubkey_path: &Path,
) -> Result<Vec<FlashReport>> {
    let slot = match slot_override {
        Some(s) => s.to_string(),
        None => getprop("ro.boot.slot_suffix"),
    };
    let expected_pk = fs::read(expected_pubkey_path)?;
    let vbmeta_path = bundle_dir.join("vbmeta.img");
    if !vbmeta_path.exists() {
        bail!("Bundle directory missing vbmeta.img: {}", bundle_dir.display());
    }

    let mut planned = vec![PlannedStep::new(
        "bundle_verify_chain",
        "刷前全链离线验签",
        format!("avbforge-core avb verify --image {} --follow-chain", vbmeta_path.display()),
    )];
    for &part in STANDARD_BOOT_PARTITIONS {
        if part != "vbmeta" && bundle_dir.join(format!("{part}.img")).exists() {
            planned.push(PlannedStep::new(
                format!("verify_{part}"),
                format!("刷前离线验签 {part}"),
                format!("avbforge-core avb verify --image {part}.img"),
            ));
            planned.push(PlannedStep::new(
                format!("flash_{part}"),
                format!("写入 {part}{slot} 并落盘"),
                format!("avbforge-core flash-partition --partition {part} --slot {slot} --fsync"),
            ));
            planned.push(PlannedStep::new(
                format!("readback_{part}"),
                format!("回读核对 {part}{slot}"),
                format!("avbforge-core verify-readback --device /dev/block/by-name/{part}{slot}"),
            ));
        }
    }
    planned.push(PlannedStep::new(
        "verify_vbmeta",
        "刷前离线验签 vbmeta",
        "avbforge-core avb verify --image vbmeta.img",
    ));
    planned.push(PlannedStep::new(
        "flash_vbmeta",
        format!("写入 vbmeta{slot} 并落盘"),
        format!("avbforge-core flash-partition --partition vbmeta --slot {slot} --fsync"),
    ));
    planned.push(PlannedStep::new(
        "readback_vbmeta",
        format!("回读核对 vbmeta{slot}"),
        format!("avbforge-core verify-readback --device /dev/block/by-name/vbmeta{slot}"),
    ));
    emit_plan(planned);

    // Mandatory full-chain verification before flashing ANY partition!
    let chain = run_step(
        "bundle_verify_chain",
        "刷前全链离线验签",
        format!("avbforge-core avb verify --image {} --follow-chain", vbmeta_path.display()),
        Some(GateKind::OfflineVerify),
        "CHAIN_PASS",
        |step| {
            emit_log("info", "Running mandatory full-chain AVB verification before flashing bundle...");
            let chain = verify_image(&vbmeta_path, Some(&expected_pk), true, None, true)
                .context("Refusing to flash bundle: full-chain AVB verification failed")?;
            emit_log("info", &format!("Full-chain verification passed ({} images verified).", chain.len()));
            step.kv("verified_images", &[("count", &chain.len().to_string())]);
            step.ok_line("full-chain offline AVB verify passed");
            Ok(chain)
        },
    )?;

    // Determine order: chained partitions first, vbmeta last (atomic-like safety)
    let mut reports = Vec::new();
    let mut ordered: Vec<String> = chain
        .iter()
        .map(|r| r.partition.clone())
        .filter(|p| p != "vbmeta")
        .collect();
    ordered.push("vbmeta".to_string());

    let mut seen = HashMap::new();
    for part in ordered {
        if seen.insert(part.clone(), true).is_some() {
            continue;
        }
        let img = bundle_dir.join(format!("{part}.img"));
        if img.exists() {
            reports.push(flash_partition_inner(
                &part,
                &img,
                slot_override,
                Some(expected_pubkey_path),
                false,
            )?);
        }
    }
    Ok(reports)
}

pub fn blockdev_size(dev: &Path) -> Result<u64> {
    let out = Command::new("blockdev")
        .args(["--getsize64"])
        .arg(dev)
        .output()
        .with_context(|| format!("Failed to invoke blockdev --getsize64 {}", dev.display()))?;
    if !out.status.success() {
        bail!("blockdev failed for {}", dev.display());
    }
    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
    Ok(s.parse::<u64>()?)
}

pub fn copy_block_head(dev: &Path, dst: &Path, size: u64) -> Result<String> {
    let mut src = File::open(dev).with_context(|| format!("Failed to open {}", dev.display()))?;
    let mut out = File::create(dst).with_context(|| format!("Failed to create {}", dst.display()))?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1024 * 1024];
    let mut rem = size;
    while rem > 0 {
        let n = (rem as usize).min(buf.len());
        src.read_exact(&mut buf[..n])?;
        out.write_all(&buf[..n])?;
        hasher.update(&buf[..n]);
        rem -= n as u64;
    }
    out.sync_all()?;
    Ok(hex::encode(hasher.finalize()))
}

pub fn sha256_file(path: &Path) -> Result<String> {
    let mut f = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn detect_widevine_level() -> String {
    for prop in ["ro.vendor.widevine.level", "vendor.widevine.level", "ro.widevine.level"] {
        let v = getprop(prop);
        if !v.is_empty() {
            return v;
        }
    }
    if let Ok(out) = Command::new("dumpsys").args(["media.drm"]).output() {
        let text = String::from_utf8_lossy(&out.stdout);
        for line in text.lines() {
            let trimmed = line.trim();
            if let Some(rest) = trimmed.strip_prefix("securityLevel:") {
                let lvl = rest.trim();
                if lvl.starts_with('L') {
                    return lvl.to_string();
                }
            }
        }
    }
    "Unknown".to_string()
}

fn detect_root_provider(ksu_version: Option<&str>) -> Option<String> {
    if let Some(kv) = ksu_version.filter(|s| !s.is_empty()) {
        let lower = kv.to_lowercase();
        let label = if lower.contains("suki") {
            format!("SukiSU ({})", kv.split_whitespace().next().unwrap_or(kv))
        } else if lower.contains("next") {
            format!("KernelSU Next ({})", kv.split_whitespace().next().unwrap_or(kv))
        } else {
            format!("KernelSU {}", kv.split_whitespace().next().unwrap_or(kv))
        };
        return Some(label);
    }
    if let Ok(out) = Command::new("/data/adb/apd").arg("-V").output() {
        if out.status.success() {
            let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if !v.is_empty() {
                return Some(format!("APatch {v}"));
            }
        }
    }
    if let Ok(out) = Command::new("magisk").arg("-v").output() {
        if out.status.success() {
            let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if !v.is_empty() {
                return Some(format!("Magisk {v}"));
            }
        }
    }
    if let Ok(out) = Command::new("su").arg("-v").output() {
        if out.status.success() {
            let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if !v.is_empty() {
                return Some(v);
            }
        }
    }
    if let Ok(out) = Command::new("id").arg("-u").output() {
        if String::from_utf8_lossy(&out.stdout).trim() == "0" {
            return Some("Root".to_string());
        }
    }
    None
}

/// Write an AVB public key (`avb_pkmd.bin`) into PD2338 `devinfo` at offset 0x94 (u32 LE len)
/// and 0x98 (raw key bytes) while keeping `is_unlocked` (0x0D) and `is_unlock_critical` (0x0E)
/// locked (0) so ABL accepts the user key in Yellow state without triggering unlock2.
pub fn write_devinfo_user_key(
    pkmd_path: &Path,
    keep_locked: bool,
    backup_output: Option<&Path>,
) -> Result<DevinfoWriteReport> {
    let dev = PathBuf::from("/dev/block/by-name/devinfo");
    if !dev.exists() {
        bail!("devinfo partition device not found: {}", dev.display());
    }
    let pkmd = fs::read(pkmd_path).with_context(|| format!("Failed to read {}", pkmd_path.display()))?;
    if pkmd.is_empty() || pkmd.len() > 2048 {
        bail!("Invalid avb_pkmd.bin length: {} bytes (expected 1..=2048)", pkmd.len());
    }

    let tmp_dir = tempfile::tempdir()?;
    let orig_path = tmp_dir.path().join("devinfo_orig.img");
    let sz = blockdev_size(&dev)?;
    if sz < 0xC90 {
        bail!("devinfo partition too small ({sz} bytes)");
    }
    copy_block_head(&dev, &orig_path, sz)?;

    let backup_str = if let Some(bkp) = backup_output {
        if let Some(parent) = bkp.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(&orig_path, bkp)?;
        emit_log("info", &format!("Backed up original devinfo to {}", bkp.display()));
        Some(bkp.display().to_string())
    } else {
        None
    };

    let mut data = fs::read(&orig_path)?;
    if !data.starts_with(b"ANDROID-BOOT!") {
        bail!("Refusing to modify devinfo: missing ANDROID-BOOT! magic header");
    }
    if keep_locked {
        data[0x0D] = 0;
        data[0x0E] = 0;
    }
    let len_le = (pkmd.len() as u32).to_le_bytes();
    data[0x94..0x98].copy_from_slice(&len_le);
    data[0x98..0x98 + pkmd.len()].copy_from_slice(&pkmd);

    let staged = tmp_dir.path().join("devinfo_userkey.img");
    fs::write(&staged, &data)?;
    let expected_sha = sha256_file(&staged)?;

    let was_ro = Command::new("blockdev")
        .args(["--getro"])
        .arg(&dev)
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim() == "1")
        .unwrap_or(false);
    if was_ro {
        let _ = Command::new("blockdev").args(["--setrw"]).arg(&dev).status();
    }
    {
        let mut dst = OpenOptions::new().write(true).open(&dev)?;
        dst.write_all(&data)?;
        dst.sync_all()?;
    }
    let _ = Command::new("sync").status();
    let _ = Command::new("blockdev").args(["--flushbufs"]).arg(&dev).status();
    if was_ro {
        let _ = Command::new("blockdev").args(["--setro"]).arg(&dev).status();
    }

    let rb_path = tmp_dir.path().join("devinfo_rb.img");
    let rb_sha = copy_block_head(&dev, &rb_path, sz)?;
    if rb_sha != expected_sha {
        bail!("CRITICAL: devinfo readback SHA-256 mismatch! expected={expected_sha}, got={rb_sha}");
    }

    emit_log("info", &format!("Wrote {}B user key to devinfo (locked={keep_locked}, sha256={rb_sha})", pkmd.len()));
    Ok(DevinfoWriteReport {
        device_path: dev.display().to_string(),
        backup_path: backup_str,
        pkmd_len: pkmd.len(),
        keep_locked,
        sha256: rb_sha,
    })
}
