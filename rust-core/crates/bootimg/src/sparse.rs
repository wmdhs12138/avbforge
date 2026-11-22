use anyhow::{bail, Context, Result};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

pub const SPARSE_HEADER_MAGIC: u32 = 0xed26_ff3a;
const CHUNK_TYPE_RAW: u16 = 0xCAC1;
const CHUNK_TYPE_FILL: u16 = 0xCAC2;
const CHUNK_TYPE_DONT_CARE: u16 = 0xCAC3;
const CHUNK_TYPE_CRC32: u16 = 0xCAC4;

pub fn is_sparse_image(path: &Path) -> Result<bool> {
    let mut f = File::open(path)?;
    let mut buf = [0u8; 4];
    if f.read(&mut buf)? < 4 {
        return Ok(false);
    }
    Ok(u32::from_le_bytes(buf) == SPARSE_HEADER_MAGIC)
}

/// Convert an Android sparse image (`.img`) into a raw image cleanly without external `simg2img`.
pub fn unsparse_image<F>(src_path: &Path, dst_path: &Path, mut progress_cb: F) -> Result<u64>
where
    F: FnMut(u64, u64),
{
    let mut src = File::open(src_path).with_context(|| format!("Failed to open {}", src_path.display()))?;
    let mut hdr = [0u8; 28];
    src.read_exact(&mut hdr)?;
    let magic = u32::from_le_bytes(hdr[0..4].try_into().unwrap());
    if magic != SPARSE_HEADER_MAGIC {
        bail!("Not an Android sparse image");
    }
    let file_hdr_sz = u16::from_le_bytes(hdr[8..10].try_into().unwrap()) as u64;
    let chunk_hdr_sz = u16::from_le_bytes(hdr[10..12].try_into().unwrap()) as u64;
    let blk_sz = u32::from_le_bytes(hdr[12..16].try_into().unwrap()) as u64;
    let total_blks = u32::from_le_bytes(hdr[16..20].try_into().unwrap()) as u64;
    let total_chunks = u32::from_le_bytes(hdr[20..24].try_into().unwrap());

    if file_hdr_sz > 28 {
        src.seek(SeekFrom::Current((file_hdr_sz - 28) as i64))?;
    }

    let total_bytes = total_blks * blk_sz;
    let mut dst = File::create(dst_path)?;
    dst.set_len(total_bytes)?;

    let mut out_off = 0u64;
    let mut copy_buf = vec![0u8; 1024 * 1024];

    for _ in 0..total_chunks {
        let mut ch = [0u8; 12];
        src.read_exact(&mut ch)?;
        let chunk_type = u16::from_le_bytes(ch[0..2].try_into().unwrap());
        let chunk_sz = u32::from_le_bytes(ch[4..8].try_into().unwrap()) as u64;
        let total_sz = u32::from_le_bytes(ch[8..12].try_into().unwrap()) as u64;
        if chunk_hdr_sz > 12 {
            src.seek(SeekFrom::Current((chunk_hdr_sz - 12) as i64))?;
        }
        let data_bytes = total_sz.saturating_sub(chunk_hdr_sz);
        let out_bytes = chunk_sz * blk_sz;

        match chunk_type {
            CHUNK_TYPE_RAW => {
                dst.seek(SeekFrom::Start(out_off))?;
                let mut rem = out_bytes;
                while rem > 0 {
                    let n = (rem as usize).min(copy_buf.len());
                    src.read_exact(&mut copy_buf[..n])?;
                    dst.write_all(&copy_buf[..n])?;
                    rem -= n as u64;
                }
                out_off += out_bytes;
            }
            CHUNK_TYPE_FILL => {
                let mut fill_val = [0u8; 4];
                src.read_exact(&mut fill_val)?;
                if fill_val != [0, 0, 0, 0] {
                    dst.seek(SeekFrom::Start(out_off))?;
                    for c in copy_buf.chunks_exact_mut(4) {
                        c.copy_from_slice(&fill_val);
                    }
                    let mut rem = out_bytes;
                    while rem > 0 {
                        let n = (rem as usize).min(copy_buf.len());
                        dst.write_all(&copy_buf[..n])?;
                        rem -= n as u64;
                    }
                }
                out_off += out_bytes;
            }
            CHUNK_TYPE_DONT_CARE => {
                out_off += out_bytes;
            }
            CHUNK_TYPE_CRC32 => {
                src.seek(SeekFrom::Current(data_bytes as i64))?;
            }
            other => bail!("Unknown sparse chunk type: 0x{other:04x}"),
        }
        progress_cb(out_off, total_bytes);
    }

    dst.sync_all()?;
    Ok(total_bytes)
}
