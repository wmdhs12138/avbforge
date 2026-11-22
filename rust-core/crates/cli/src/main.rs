mod device;
mod events;
mod forge;

use anyhow::Result;
use avb2::{
    add_hash_footer, add_hashtree_footer, erase_footer, load_avb_image, verify_image,
    AlgorithmType, AvbPrivateKey, AvbPublicKey, HashFooterOptions, HashtreeFooterOptions,
};
use clap::{Parser, Subcommand};
use serde_json::json;
use std::fs;
use std::path::PathBuf;

use crate::events::{emit_error, emit_progress, emit_result};

#[derive(Parser)]
#[command(name = "avbforge-core", version, about = "AVBForge native Rust AVB2/Boot/DSU engine")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Inspect device properties, AVB chain, fstab avb_keys, KSU and freezer status
    InspectDevice {
        #[arg(long)]
        package: Option<String>,
    },
    /// Key generation, inspection and avb_pkmd.bin export
    Key {
        #[command(subcommand)]
        action: KeyAction,
    },
    /// Low-level AVB 2.0 inspection, signing and verification
    Avb {
        #[command(subcommand)]
        action: AvbAction,
    },
    /// Backup active/specified slot boot partitions + manifest.json
    BackupPartitions {
        #[arg(long)]
        out_dir: PathBuf,
        #[arg(long)]
        slot: Option<String>,
        #[arg(long)]
        partitions: Vec<String>,
    },
    /// Export current slot init_boot.img for patching with any user-chosen KernelSU manager
    ExportInitBoot {
        #[arg(long)]
        output: PathBuf,
        #[arg(long)]
        slot: Option<String>,
    },
    /// Inject DSU avbpubkey into init_boot/boot ramdisk and re-sign
    PatchDsuKey {
        #[arg(long)]
        input: PathBuf,
        #[arg(long)]
        output: PathBuf,
        #[arg(long)]
        key: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
        #[arg(long, default_value = "init_boot")]
        partition_name: String,
        #[arg(long, default_value = "q-gsi.avbpubkey")]
        target_key_name: String,
        #[arg(long)]
        metadata_source: Option<PathBuf>,
    },
    /// Forge a complete self-signed bundle (optional user KSU init_boot + optional DSU pubkey)
    ForgeBundle {
        #[arg(long)]
        stock_dir: PathBuf,
        #[arg(long)]
        out_dir: PathBuf,
        #[arg(long)]
        key: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
        #[arg(long)]
        patched_init_boot: Option<PathBuf>,
        #[arg(long, default_value_t = true, action = clap::ArgAction::Set)]
        inject_dsu_key: bool,
        #[arg(long, default_value = "q-gsi.avbpubkey")]
        dsu_key_name: String,
    },
    /// Flash a single verified image to a partition with readback verification
    FlashPartition {
        #[arg(long)]
        partition: String,
        #[arg(long)]
        image: PathBuf,
        #[arg(long)]
        slot: Option<String>,
        #[arg(long)]
        expected_pubkey: Option<PathBuf>,
    },
    /// Flash a complete forged bundle after mandatory full-chain verification
    FlashBundle {
        #[arg(long)]
        bundle_dir: PathBuf,
        #[arg(long)]
        slot: Option<String>,
        #[arg(long)]
        expected_pubkey: PathBuf,
    },
    /// GSI signing (supports .img / .xz / .gz / sparse)
    Gsi {
        #[command(subcommand)]
        action: GsiAction,
    },
    /// Native DSU management via gsi_tool
    Dsu {
        #[command(subcommand)]
        action: DsuAction,
    },
}

#[derive(Subcommand)]
enum KeyAction {
    Gen {
        #[arg(long, default_value_t = 2048)]
        bits: usize,
        #[arg(long)]
        out_pem: PathBuf,
        #[arg(long)]
        out_pkmd: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
    },
    ExportPkmd {
        #[arg(long)]
        key: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
        #[arg(long)]
        output: PathBuf,
    },
    Inspect {
        #[arg(long)]
        path: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
    },
}

#[derive(Subcommand)]
enum AvbAction {
    Info {
        #[arg(long)]
        image: PathBuf,
    },
    Verify {
        #[arg(long)]
        image: PathBuf,
        #[arg(long)]
        expected_pubkey: Option<PathBuf>,
        #[arg(long, default_value_t = false)]
        follow_chain: bool,
        #[arg(long, default_value_t = true)]
        skip_missing_external_hashtree: bool,
    },
    EraseFooter {
        #[arg(long)]
        image: PathBuf,
    },
    AddHashFooter {
        #[arg(long)]
        image: PathBuf,
        #[arg(long)]
        partition_name: String,
        #[arg(long)]
        partition_size: u64,
        #[arg(long)]
        key: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
        #[arg(long)]
        algorithm: Option<String>,
        #[arg(long, default_value_t = 0)]
        rollback_index: u64,
        #[arg(long, default_value_t = 0)]
        flags: u32,
        #[arg(long)]
        prop: Vec<String>,
    },
    AddHashtreeFooter {
        #[arg(long)]
        image: PathBuf,
        #[arg(long, default_value = "system")]
        partition_name: String,
        #[arg(long)]
        partition_size: Option<u64>,
        #[arg(long)]
        key: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
        #[arg(long)]
        algorithm: Option<String>,
        #[arg(long, default_value_t = 0)]
        rollback_index: u64,
    },
}

#[derive(Subcommand)]
enum GsiAction {
    Sign {
        #[arg(long)]
        input: PathBuf,
        #[arg(long)]
        output: PathBuf,
        #[arg(long)]
        key: PathBuf,
        #[arg(long)]
        passphrase: Option<String>,
        #[arg(long)]
        partition_size: Option<u64>,
    },
}

#[derive(Subcommand)]
enum DsuAction {
    Status,
    Install {
        #[arg(long)]
        image: PathBuf,
        #[arg(long, default_value_t = 8_589_934_592)]
        userdata_size: u64,
        #[arg(long, default_value_t = true, action = clap::ArgAction::Set)]
        single_boot: bool,
        #[arg(long)]
        expected_pubkey: Option<PathBuf>,
    },
    Enable {
        #[arg(long, default_value_t = true, action = clap::ArgAction::Set)]
        single_boot: bool,
    },
    Disable,
    Wipe,
}

fn run(cli: Cli) -> Result<()> {
    match cli.command {
        Commands::InspectDevice { package } => {
            let snap = device::inspect_device(package.as_deref())?;
            emit_result(snap);
        }
        Commands::Key { action } => match action {
            KeyAction::Gen {
                bits,
                out_pem,
                out_pkmd,
                passphrase,
            } => {
                let priv_key = AvbPrivateKey::generate(bits)?;
                let pub_key = priv_key.public_key()?;
                if let Some(p) = out_pem.parent() {
                    fs::create_dir_all(p)?;
                }
                if let Some(p) = out_pkmd.parent() {
                    fs::create_dir_all(p)?;
                }
                fs::write(&out_pem, priv_key.to_pkcs8_pem(passphrase.as_deref())?)?;
                fs::write(&out_pkmd, &pub_key.raw_avb_key)?;
                emit_result(json!({
                    "bits": bits,
                    "out_pem": out_pem,
                    "out_pkmd": out_pkmd,
                    "pkmd_size": pub_key.raw_avb_key.len(),
                    "sha1": pub_key.sha1_hex(),
                }));
            }
            KeyAction::ExportPkmd {
                key,
                passphrase,
                output,
            } => {
                let priv_key = AvbPrivateKey::from_pem_file(&key, passphrase.as_deref())?;
                let pub_key = priv_key.public_key()?;
                if let Some(p) = output.parent() {
                    fs::create_dir_all(p)?;
                }
                fs::write(&output, &pub_key.raw_avb_key)?;
                emit_result(json!({
                    "output": output,
                    "bits": pub_key.key_num_bits,
                    "size": pub_key.raw_avb_key.len(),
                    "sha1": pub_key.sha1_hex(),
                }));
            }
            KeyAction::Inspect { path, passphrase } => {
                let bytes = fs::read(&path)?;
                let pub_key = if bytes.starts_with(b"-----BEGIN") {
                    AvbPrivateKey::from_pem_file(&path, passphrase.as_deref())?.public_key()?
                } else {
                    AvbPublicKey::from_avb_bytes(&bytes)?
                };
                emit_result(json!({
                    "path": path,
                    "bits": pub_key.key_num_bits,
                    "pkmd_size": pub_key.raw_avb_key.len(),
                    "sha1": pub_key.sha1_hex(),
                }));
            }
        },
        Commands::Avb { action } => match action {
            AvbAction::Info { image } => {
                let loaded = load_avb_image(&image)?;
                emit_result(loaded);
            }
            AvbAction::Verify {
                image,
                expected_pubkey,
                follow_chain,
                skip_missing_external_hashtree,
            } => {
                let pk = match expected_pubkey {
                    Some(p) => Some(fs::read(p)?),
                    None => None,
                };
                let results = verify_image(
                    &image,
                    pk.as_deref(),
                    follow_chain,
                    None,
                    skip_missing_external_hashtree,
                )?;
                emit_result(results);
            }
            AvbAction::EraseFooter { image } => {
                let removed = erase_footer(&image)?;
                emit_result(json!({ "image": image, "footer_removed": removed }));
            }
            AvbAction::AddHashFooter {
                image,
                partition_name,
                partition_size,
                key,
                passphrase,
                algorithm,
                rollback_index,
                flags,
                prop,
            } => {
                let priv_key = AvbPrivateKey::from_pem_file(&key, passphrase.as_deref())?;
                let alg = match algorithm {
                    Some(a) => AlgorithmType::from_name(&a)?,
                    None => priv_key.default_algorithm()?,
                };
                let props: Vec<(String, String)> = prop
                    .iter()
                    .filter_map(|p| p.split_once(':').map(|(k, v)| (k.to_string(), v.to_string())))
                    .collect();
                let ftr = add_hash_footer(
                    &image,
                    HashFooterOptions {
                        partition_name: &partition_name,
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
                emit_result(ftr);
            }
            AvbAction::AddHashtreeFooter {
                image,
                partition_name,
                partition_size,
                key,
                passphrase,
                algorithm,
                rollback_index,
            } => {
                let priv_key = AvbPrivateKey::from_pem_file(&key, passphrase.as_deref())?;
                let alg = match algorithm {
                    Some(a) => AlgorithmType::from_name(&a)?,
                    None => priv_key.default_algorithm()?,
                };
                let (ftr, desc) = add_hashtree_footer(
                    &image,
                    HashtreeFooterOptions {
                        partition_name: &partition_name,
                        partition_size,
                        algorithm: alg,
                        key: Some(&priv_key),
                        salt: None,
                        rollback_index,
                        flags: 0,
                        props: vec![],
                        release_string: None,
                        block_size: 4096,
                    },
                    |c, t| emit_progress("hashtree", c, t),
                )?;
                emit_result(json!({
                    "footer": ftr,
                    "root_digest": hex::encode(&desc.root_digest),
                    "tree_size": desc.tree_size,
                }));
            }
        },
        Commands::BackupPartitions {
            out_dir,
            slot,
            partitions,
        } => {
            let manifest = device::backup_partitions(&out_dir, slot.as_deref(), &partitions)?;
            emit_result(manifest);
        }
        Commands::ExportInitBoot { output, slot } => {
            let p = forge::export_stock_init_boot(&output, slot.as_deref())?;
            emit_result(json!({ "output": p, "sha256": device::sha256_file(&p)? }));
        }
        Commands::PatchDsuKey {
            input,
            output,
            key,
            passphrase,
            partition_name,
            target_key_name,
            metadata_source,
        } => {
            let rep = forge::patch_dsu_key(
                &input,
                &output,
                &key,
                passphrase.as_deref(),
                &partition_name,
                &target_key_name,
                metadata_source.as_deref(),
            )?;
            emit_result(rep);
        }
        Commands::ForgeBundle {
            stock_dir,
            out_dir,
            key,
            passphrase,
            patched_init_boot,
            inject_dsu_key,
            dsu_key_name,
        } => {
            let rep = forge::forge_bundle(
                &stock_dir,
                &out_dir,
                &key,
                passphrase.as_deref(),
                patched_init_boot.as_deref(),
                inject_dsu_key,
                &dsu_key_name,
            )?;
            emit_result(rep);
        }
        Commands::FlashPartition {
            partition,
            image,
            slot,
            expected_pubkey,
        } => {
            let rep = device::flash_partition(
                &partition,
                &image,
                slot.as_deref(),
                expected_pubkey.as_deref(),
            )?;
            emit_result(rep);
        }
        Commands::FlashBundle {
            bundle_dir,
            slot,
            expected_pubkey,
        } => {
            let reps = device::flash_bundle(&bundle_dir, slot.as_deref(), &expected_pubkey)?;
            emit_result(reps);
        }
        Commands::Gsi { action } => match action {
            GsiAction::Sign {
                input,
                output,
                key,
                passphrase,
                partition_size,
            } => {
                let rep =
                    forge::sign_gsi(&input, &output, &key, passphrase.as_deref(), partition_size)?;
                emit_result(rep);
            }
        },
        Commands::Dsu { action } => match action {
            DsuAction::Status => {
                let st = forge::dsu_cmd(&["status"])?;
                emit_result(json!({ "status": st }));
            }
            DsuAction::Install {
                image,
                userdata_size,
                single_boot,
                expected_pubkey,
            } => {
                let st = forge::dsu_install(
                    &image,
                    userdata_size,
                    single_boot,
                    expected_pubkey.as_deref(),
                )?;
                emit_result(json!({ "status": st }));
            }
            DsuAction::Enable { single_boot } => {
                let args: &[&str] = if single_boot {
                    &["enable", "-s"]
                } else {
                    &["enable"]
                };
                let st = forge::dsu_cmd(args)?;
                emit_result(json!({ "output": st }));
            }
            DsuAction::Disable => {
                let st = forge::dsu_cmd(&["disable"])?;
                emit_result(json!({ "output": st }));
            }
            DsuAction::Wipe => {
                let st = forge::dsu_cmd(&["wipe"])?;
                emit_result(json!({ "output": st }));
            }
        },
    }
    Ok(())
}

fn main() {
    let cli = Cli::parse();
    if let Err(e) = run(cli) {
        emit_error(format!("{e:#}"));
        std::process::exit(1);
    }
}
