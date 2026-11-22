use anyhow::{bail, Context, Result};
use rand::RngCore;
use rayon::prelude::*;
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

use crate::descriptors::{AvbDescriptor, HashDescriptor, HashtreeDescriptor, PropertyDescriptor};
use crate::format::{
    load_avb_image, round_up, AlgorithmType, AvbFooter, VbMetaImage, FOOTER_MAGIC, FOOTER_SIZE,
};
use crate::key::AvbPrivateKey;

pub const DEFAULT_BLOCK_SIZE: u32 = 4096;

pub struct HashFooterOptions<'a> {
    pub partition_name: &'a str,
    pub partition_size: u64,
    pub algorithm: AlgorithmType,
    pub key: Option<&'a AvbPrivateKey>,
    pub salt: Option<Vec<u8>>,
    pub rollback_index: u64,
    pub flags: u32,
    pub props: Vec<(String, String)>,
    pub release_string: Option<&'a str>,
}

pub struct HashtreeFooterOptions<'a> {
    pub partition_name: &'a str,
    pub partition_size: Option<u64>,
    pub algorithm: AlgorithmType,
    pub key: Option<&'a AvbPrivateKey>,
    pub salt: Option<Vec<u8>>,
    pub rollback_index: u64,
    pub flags: u32,
    pub props: Vec<(String, String)>,
    pub release_string: Option<&'a str>,
    pub block_size: u32,
}

/// Strip an existing AVB footer (and hashtree/vbmeta tail) back to `original_image_size`.
pub fn erase_footer(path: &Path) -> Result<bool> {
    let f = File::open(path).with_context(|| format!("Failed to open {}", path.display()))?;
    let len = f.metadata()?.len();
    if len < FOOTER_SIZE as u64 {
        return Ok(false);
    }
    let mut f = OpenOptions::new().read(true).write(true).open(path)?;
    f.seek(SeekFrom::End(-(FOOTER_SIZE as i64)))?;
    let mut ftr_buf = [0u8; FOOTER_SIZE];
    f.read_exact(&mut ftr_buf)?;
    if &ftr_buf[0..4] != FOOTER_MAGIC {
        return Ok(false);
    }
    let footer = AvbFooter::parse(&ftr_buf)?;
    f.set_len(footer.original_image_size)?;
    f.sync_all()?;
    Ok(true)
}

/// Add an AVB hash footer to a boot/init_boot/vendor_boot/dtbo/recovery partition image.
pub fn add_hash_footer(path: &Path, opts: HashFooterOptions<'_>) -> Result<AvbFooter> {
    let mut f = OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .with_context(|| format!("Failed to open {}", path.display()))?;
    let orig_size = f.metadata()?.len();
    if orig_size > opts.partition_size {
        bail!(
            "Original image size ({orig_size}) exceeds partition_size ({})",
            opts.partition_size
        );
    }

    let salt = opts.salt.unwrap_or_else(random_salt_32);

    // Hash original image content with salt prefix
    f.seek(SeekFrom::Start(0))?;
    let mut hasher = Sha256::new();
    hasher.update(&salt);
    let mut buf = vec![0u8; 1024 * 1024];
    let mut rem = orig_size;
    while rem > 0 {
        let to_read = (rem as usize).min(buf.len());
        f.read_exact(&mut buf[..to_read])?;
        hasher.update(&buf[..to_read]);
        rem -= to_read as u64;
    }
    let digest = hasher.finalize().to_vec();

    let mut descriptors = Vec::with_capacity(1 + opts.props.len());
    descriptors.push(AvbDescriptor::Hash(HashDescriptor {
        image_size: orig_size,
        hash_algorithm: "sha256".to_string(),
        partition_name: opts.partition_name.to_string(),
        salt,
        digest,
        flags: 0,
    }));
    for (k, v) in opts.props {
        descriptors.push(AvbDescriptor::Property(PropertyDescriptor { key: k, value: v }));
    }

    let vbmeta_blob = VbMetaImage::build_and_sign(
        descriptors,
        opts.algorithm,
        opts.key,
        opts.rollback_index,
        0,
        opts.flags,
        opts.release_string,
    )?;

    let vbmeta_offset = round_up(orig_size, 4096);
    let vbmeta_size = vbmeta_blob.len() as u64;
    if vbmeta_offset + vbmeta_size + FOOTER_SIZE as u64 > opts.partition_size {
        bail!(
            "Partition size ({}) too small for image ({orig_size}) + vbmeta ({vbmeta_size}) + footer",
            opts.partition_size
        );
    }

    let footer = AvbFooter {
        version_major: 1,
        version_minor: 0,
        original_image_size: orig_size,
        vbmeta_offset,
        vbmeta_size,
    };

    // Expand file to exact partition_size (zero-filled), write vbmeta & footer
    f.set_len(opts.partition_size)?;
    f.seek(SeekFrom::Start(vbmeta_offset))?;
    f.write_all(&vbmeta_blob)?;
    f.seek(SeekFrom::Start(opts.partition_size - FOOTER_SIZE as u64))?;
    f.write_all(&footer.encode())?;
    f.sync_all()?;

    Ok(footer)
}

/// Build dm-verity SHA-256 Merkle hash tree in top-down layout (matching AOSP avbtool.py).
/// Returns `(root_digest, tree_bytes)`.
pub fn generate_sha256_hashtree<F>(
    path: &Path,
    image_size: u64,
    block_size: u32,
    salt: &[u8],
    mut progress_cb: F,
) -> Result<(Vec<u8>, Vec<u8>)>
where
    F: FnMut(u64, u64),
{
    let bs = block_size as usize;
    if image_size == 0 || image_size % (bs as u64) != 0 {
        bail!("Image size ({image_size}) must be a non-zero multiple of block_size ({bs})");
    }

    // Level 0: read file in parallelizable chunks (e.g., 16 MiB) and hash each 4096-byte block
    let chunk_blocks = 4096usize; // 16 MiB per chunk
    let chunk_bytes = chunk_blocks * bs;
    let total_blocks = (image_size / bs as u64) as usize;
    let mut level0 = Vec::with_capacity(total_blocks * 32 + bs);

    let mut f = File::open(path).with_context(|| format!("Failed to open {}", path.display()))?;
    let mut raw = vec![0u8; chunk_bytes];
    let mut offset = 0u64;
    while offset < image_size {
        let to_read = ((image_size - offset) as usize).min(chunk_bytes);
        f.read_exact(&mut raw[..to_read])?;
        let slice = &raw[..to_read];
        let hashes: Vec<[u8; 32]> = slice
            .par_chunks_exact(bs)
            .map(|blk| {
                let mut h = Sha256::new();
                h.update(salt);
                h.update(blk);
                h.finalize().into()
            })
            .collect();
        for h in hashes {
            level0.extend_from_slice(&h);
        }
        offset += to_read as u64;
        progress_cb(offset, image_size);
    }

    pad_to_block(&mut level0, bs);

    // Special case matching avbtool.py: if image_size <= block_size (single data block),
    // level0 is 1 padded hash block and root digest is hash(salt || level0_block)
    //Wait: in AOSP avbtool.py `generate_hash_tree`:
    //   if image_size <= block_size -> hash_ret = [level0], root = sha256(salt || data)??
    //   Let's match AOSP avbtool.py EXACTLY:
    //   In AOSP avbtool.py:
    //     while hash_src_size > block_size:
    //       hash each block -> level, pad level to block_size, push level, hash_src = level
    //     root_digest = hash(salt || hash_src_block)  (when hash_src_size == block_size)
    // Wait: if image_size == block_size (1 block), loop doesn't run, tree is empty, root = sha256(salt || data_block)!
    // If image_size > block_size, level0 has >= 2 hashes (64.. bytes), padded to >= 4096 bytes.
    // While current_level.len() > bs, compute parent level; when current_level.len() == bs, root_digest = sha256(salt || current_level)!
    if image_size == bs as u64 {
        let root_digest = level0[0..32].to_vec();
        return Ok((root_digest, Vec::new()));
    }

    let mut levels: Vec<Vec<u8>> = vec![level0];
    while levels.last().unwrap().len() > bs {
        let prev = levels.last().unwrap();
        let hashes: Vec<[u8; 32]> = prev
            .par_chunks_exact(bs)
            .map(|blk| {
                let mut h = Sha256::new();
                h.update(salt);
                h.update(blk);
                h.finalize().into()
            })
            .collect();
        let mut next_level = Vec::with_capacity(hashes.len() * 32 + bs);
        for h in hashes {
            next_level.extend_from_slice(&h);
        }
        pad_to_block(&mut next_level, bs);
        levels.push(next_level);
    }

    // Root digest = SHA256(salt || top_level_block)
    let top = levels.last().unwrap();
    let mut rh = Sha256::new();
    rh.update(salt);
    rh.update(top);
    let root_digest = rh.finalize().to_vec();

    // Concatenate top-down (highest level first, level0 last)
    let total_tree_size: usize = levels.iter().map(|l| l.len()).sum();
    let mut tree = Vec::with_capacity(total_tree_size);
    for lvl in levels.into_iter().rev() {
        tree.extend_from_slice(&lvl);
    }

    Ok((root_digest, tree))
}

/// Add an AVB hashtree footer (without FEC, ideal for DSU & fast mobile signing) to a raw system/GSI image.
pub fn add_hashtree_footer<F>(
    path: &Path,
    opts: HashtreeFooterOptions<'_>,
    progress_cb: F,
) -> Result<(AvbFooter, HashtreeDescriptor)>
where
    F: FnMut(u64, u64),
{
    let bs = if opts.block_size == 0 {
        DEFAULT_BLOCK_SIZE
    } else {
        opts.block_size
    };

    // Align raw image up to block_size if needed
    {
        let f = OpenOptions::new().read(true).write(true).open(path)?;
        let len = f.metadata()?.len();
        let aligned = round_up(len, bs as u64);
        if aligned != len {
            f.set_len(aligned)?;
        }
    }

    let orig_size = File::open(path)?.metadata()?.len();
    let salt = opts.salt.unwrap_or_else(random_salt_32);
    let (root_digest, tree_bytes) = generate_sha256_hashtree(path, orig_size, bs, &salt, progress_cb)?;

    let tree_offset = orig_size;
    let tree_size = tree_bytes.len() as u64;

    let ht_desc = HashtreeDescriptor {
        dm_verity_version: 1,
        image_size: orig_size,
        tree_offset,
        tree_size,
        data_block_size: bs,
        hash_block_size: bs,
        fec_num_roots: 0,
        fec_offset: 0,
        fec_size: 0,
        hash_algorithm: "sha256".to_string(),
        partition_name: opts.partition_name.to_string(),
        salt,
        root_digest,
        flags: 0,
    };

    let mut descriptors = Vec::with_capacity(1 + opts.props.len());
    descriptors.push(AvbDescriptor::Hashtree(ht_desc.clone()));
    for (k, v) in opts.props {
        descriptors.push(AvbDescriptor::Property(PropertyDescriptor { key: k, value: v }));
    }

    let vbmeta_blob = VbMetaImage::build_and_sign(
        descriptors,
        opts.algorithm,
        opts.key,
        opts.rollback_index,
        0,
        opts.flags,
        opts.release_string,
    )?;

    let vbmeta_offset = round_up(tree_offset + tree_size, bs as u64);
    let vbmeta_size = vbmeta_blob.len() as u64;
    let min_partition_size = round_up(vbmeta_offset + vbmeta_size + FOOTER_SIZE as u64, bs as u64);
    let final_partition_size = match opts.partition_size {
        Some(ps) => {
            if ps < min_partition_size {
                bail!(
                    "Specified partition_size ({ps}) is smaller than required ({min_partition_size})"
                );
            }
            ps
        }
        None => min_partition_size,
    };

    let footer = AvbFooter {
        version_major: 1,
        version_minor: 0,
        original_image_size: orig_size,
        vbmeta_offset,
        vbmeta_size,
    };

    let mut f = OpenOptions::new().read(true).write(true).open(path)?;
    f.set_len(final_partition_size)?;
    f.seek(SeekFrom::Start(tree_offset))?;
    f.write_all(&tree_bytes)?;
    f.seek(SeekFrom::Start(vbmeta_offset))?;
    f.write_all(&vbmeta_blob)?;
    f.seek(SeekFrom::Start(final_partition_size - FOOTER_SIZE as u64))?;
    f.write_all(&footer.encode())?;
    f.sync_all()?;

    Ok((footer, ht_desc))
}

/// Build a standalone vbmeta image (padded to `padding_size`, e.g. 4096 or 65536).
pub fn make_vbmeta_image(
    output: &Path,
    descriptors: Vec<AvbDescriptor>,
    algorithm: AlgorithmType,
    key: Option<&AvbPrivateKey>,
    rollback_index: u64,
    flags: u32,
    padding_size: usize,
    release_string: Option<&str>,
) -> Result<usize> {
    let mut blob = VbMetaImage::build_and_sign(
        descriptors,
        algorithm,
        key,
        rollback_index,
        0,
        flags,
        release_string,
    )?;
    if padding_size > 0 && blob.len() < padding_size {
        blob.resize(padding_size, 0);
    } else if padding_size > 0 {
        let padded = round_up(blob.len() as u64, padding_size as u64) as usize;
        blob.resize(padded, 0);
    }
    let mut f = File::create(output).with_context(|| format!("Failed to create {}", output.display()))?;
    f.write_all(&blob)?;
    f.sync_all()?;
    Ok(blob.len())
}

/// Helper: extract all descriptors from an existing signed image (vbmeta or footer-bearing partition).
pub fn extract_descriptors_from_image(path: &Path) -> Result<Vec<AvbDescriptor>> {
    Ok(load_avb_image(path)?.vbmeta.descriptors)
}

fn pad_to_block(buf: &mut Vec<u8>, bs: usize) {
    let rem = buf.len() % bs;
    if rem != 0 {
        buf.resize(buf.len() + (bs - rem), 0);
    }
}

fn random_salt_32() -> Vec<u8> {
    let mut salt = vec![0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut salt);
    salt
}
