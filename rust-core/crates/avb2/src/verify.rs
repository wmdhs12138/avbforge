use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256, Sha512};
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use crate::descriptors::{AvbDescriptor, HashDescriptor, HashtreeDescriptor};
use crate::format::load_avb_image;
use crate::hashtree::generate_sha256_hashtree;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PartitionVerifyResult {
    pub partition: String,
    pub path: String,
    pub algorithm: String,
    pub public_key_sha1: Option<String>,
    pub rollback_index: u64,
    pub flags: u32,
    pub verified_items: Vec<String>,
}

/// Verify a single AVB image (standalone vbmeta or footer-bearing partition).
/// If `follow_chain` is true, chained partitions are looked up in `partition_dir` (or overrides map).
pub fn verify_image(
    path: &Path,
    expected_pubkey: Option<&[u8]>,
    follow_chain: bool,
    partition_overrides: Option<&HashMap<String, PathBuf>>,
    skip_missing_external_hashtree: bool,
) -> Result<Vec<PartitionVerifyResult>> {
    let mut results = Vec::new();
    verify_recursive(
        path,
        "vbmeta",
        expected_pubkey,
        follow_chain,
        partition_overrides,
        skip_missing_external_hashtree,
        &mut results,
    )?;
    Ok(results)
}

fn verify_recursive(
    path: &Path,
    label: &str,
    expected_pubkey: Option<&[u8]>,
    follow_chain: bool,
    overrides: Option<&HashMap<String, PathBuf>>,
    skip_missing_external_hashtree: bool,
    out: &mut Vec<PartitionVerifyResult>,
) -> Result<()> {
    let loaded = load_avb_image(path)
        .with_context(|| format!("Failed to load AVB metadata from {}", path.display()))?;
    loaded
        .vbmeta
        .verify_signature(expected_pubkey)
        .with_context(|| format!("Signature verification failed for {} ({})", label, path.display()))?;

    let mut verified_items = vec![format!("vbmeta signature ({})", loaded.vbmeta.algorithm.as_str())];
    let parent_dir = path.parent().unwrap_or_else(|| Path::new("."));

    for desc in &loaded.vbmeta.descriptors {
        match desc {
            AvbDescriptor::Hash(hd) => {
                let target = if loaded.footer.is_some() {
                    path.to_path_buf()
                } else {
                    resolve_partition(parent_dir, &hd.partition_name, overrides)
                };
                verify_hash_descriptor(&target, hd)?;
                verified_items.push(format!(
                    "hash:{} ({} bytes, {})",
                    hd.partition_name, hd.image_size, hd.hash_algorithm
                ));
            }
            AvbDescriptor::Hashtree(ht) => {
                let target = if loaded.footer.is_some() {
                    path.to_path_buf()
                } else {
                    resolve_partition(parent_dir, &ht.partition_name, overrides)
                };
                if !target.exists() && skip_missing_external_hashtree {
                    verified_items.push(format!(
                        "hashtree:{} (descriptor present, external image skipped)",
                        ht.partition_name
                    ));
                } else {
                    verify_hashtree_descriptor(&target, ht)?;
                    verified_items.push(format!(
                        "hashtree:{} ({} bytes, root={})",
                        ht.partition_name,
                        ht.image_size,
                        &hex::encode(&ht.root_digest)[..12]
                    ));
                }
            }
            AvbDescriptor::ChainPartition(cp) => {
                verified_items.push(format!(
                    "chain:{} (rli={})",
                    cp.partition_name, cp.rollback_index_location
                ));
                if follow_chain {
                    let target = resolve_partition(parent_dir, &cp.partition_name, overrides);
                    verify_recursive(
                        &target,
                        &cp.partition_name,
                        Some(&cp.public_key),
                        true,
                        overrides,
                        skip_missing_external_hashtree,
                        out,
                    )?;
                }
            }
            _ => {}
        }
    }

    out.push(PartitionVerifyResult {
        partition: label.to_string(),
        path: path.display().to_string(),
        algorithm: loaded.vbmeta.algorithm.as_str().to_string(),
        public_key_sha1: loaded.vbmeta.public_key_sha1(),
        rollback_index: loaded.vbmeta.rollback_index,
        flags: loaded.vbmeta.flags,
        verified_items,
    });
    Ok(())
}

fn resolve_partition(
    parent_dir: &Path,
    partition_name: &str,
    overrides: Option<&HashMap<String, PathBuf>>,
) -> PathBuf {
    if let Some(map) = overrides {
        if let Some(p) = map.get(partition_name) {
            return p.clone();
        }
    }
    parent_dir.join(format!("{partition_name}.img"))
}

fn verify_hash_descriptor(path: &Path, hd: &HashDescriptor) -> Result<()> {
    let mut f = File::open(path).with_context(|| format!("Missing image for hash descriptor {}", path.display()))?;
    let mut buf = vec![0u8; 1024 * 1024];
    let mut rem = hd.image_size;

    let computed: Vec<u8> = match hd.hash_algorithm.as_str() {
        "sha256" => {
            let mut h = Sha256::new();
            h.update(&hd.salt);
            while rem > 0 {
                let n = (rem as usize).min(buf.len());
                f.read_exact(&mut buf[..n])?;
                h.update(&buf[..n]);
                rem -= n as u64;
            }
            h.finalize().to_vec()
        }
        "sha512" => {
            let mut h = Sha512::new();
            h.update(&hd.salt);
            while rem > 0 {
                let n = (rem as usize).min(buf.len());
                f.read_exact(&mut buf[..n])?;
                h.update(&buf[..n]);
                rem -= n as u64;
            }
            h.finalize().to_vec()
        }
        other => bail!("Unsupported hash algorithm in HashDescriptor: {other}"),
    };

    if computed != hd.digest {
        bail!(
            "Hash mismatch for {}: expected {}, got {}",
            hd.partition_name,
            hex::encode(&hd.digest),
            hex::encode(&computed)
        );
    }
    Ok(())
}

fn verify_hashtree_descriptor(path: &Path, ht: &HashtreeDescriptor) -> Result<()> {
    if ht.hash_algorithm != "sha256" {
        bail!("Unsupported hashtree algorithm: {}", ht.hash_algorithm);
    }
    let (root_digest, expected_tree) =
        generate_sha256_hashtree(path, ht.image_size, ht.data_block_size, &ht.salt, |_, _| {})?;
    if root_digest != ht.root_digest {
        bail!(
            "Hashtree root digest mismatch for {}: expected {}, got {}",
            ht.partition_name,
            hex::encode(&ht.root_digest),
            hex::encode(&root_digest)
        );
    }
    if ht.tree_size != expected_tree.len() as u64 {
        bail!(
            "Hashtree size mismatch for {}: descriptor={}, computed={}",
            ht.partition_name,
            ht.tree_size,
            expected_tree.len()
        );
    }
    // Verify embedded tree bytes match recomputed tree
    let mut f = File::open(path)?;
    f.seek(SeekFrom::Start(ht.tree_offset))?;
    let mut stored_tree = vec![0u8; ht.tree_size as usize];
    f.read_exact(&mut stored_tree)?;
    if stored_tree != expected_tree {
        bail!("Stored hashtree bytes mismatch for {}", ht.partition_name);
    }
    Ok(())
}
