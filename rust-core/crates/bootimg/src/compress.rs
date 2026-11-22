use anyhow::{bail, Context, Result};
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use serde::{Deserialize, Serialize};
use std::io::{BufReader, Read, Write};

pub const LZ4_LEGACY_MAGIC: [u8; 4] = [0x02, 0x21, 0x4c, 0x18];
pub const LZ4_FRAME_MAGIC: [u8; 4] = [0x04, 0x22, 0x4d, 0x18];
pub const GZIP_MAGIC: [u8; 2] = [0x1f, 0x8b];
pub const XZ_MAGIC: [u8; 6] = [0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00];
pub const LZ4_LEGACY_BLOCK_SIZE: usize = 8 * 1024 * 1024; // 8 MiB

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CompressionFormat {
    Raw,
    Lz4Legacy,
    Lz4Frame,
    Gzip,
    Xz,
}

pub fn detect_compression(data: &[u8]) -> CompressionFormat {
    if data.len() >= 4 && data[0..4] == LZ4_LEGACY_MAGIC {
        CompressionFormat::Lz4Legacy
    } else if data.len() >= 4 && data[0..4] == LZ4_FRAME_MAGIC {
        CompressionFormat::Lz4Frame
    } else if data.len() >= 2 && data[0..2] == GZIP_MAGIC {
        CompressionFormat::Gzip
    } else if data.len() >= 6 && data[0..6] == XZ_MAGIC {
        CompressionFormat::Xz
    } else {
        CompressionFormat::Raw
    }
}

pub fn decompress_ramdisk(data: &[u8]) -> Result<(CompressionFormat, Vec<u8>)> {
    let fmt = detect_compression(data);
    let out = match fmt {
        CompressionFormat::Raw => data.to_vec(),
        CompressionFormat::Lz4Legacy => decompress_lz4_legacy(data)?,
        CompressionFormat::Lz4Frame => {
            let mut dec = lz4_flex::frame::FrameDecoder::new(data);
            let mut buf = Vec::new();
            dec.read_to_end(&mut buf).context("LZ4 frame decompression failed")?;
            buf
        }
        CompressionFormat::Gzip => {
            let mut dec = GzDecoder::new(data);
            let mut buf = Vec::new();
            dec.read_to_end(&mut buf).context("Gzip decompression failed")?;
            buf
        }
        CompressionFormat::Xz => {
            let mut br = BufReader::new(data);
            let mut buf = Vec::new();
            lzma_rs::xz_decompress(&mut br, &mut buf).context("XZ decompression failed")?;
            buf
        }
    };
    Ok((fmt, out))
}

pub fn compress_ramdisk(fmt: CompressionFormat, raw: &[u8]) -> Result<Vec<u8>> {
    match fmt {
        CompressionFormat::Raw => Ok(raw.to_vec()),
        CompressionFormat::Lz4Legacy => compress_lz4_legacy(raw),
        CompressionFormat::Lz4Frame => {
            let mut enc = lz4_flex::frame::FrameEncoder::new(Vec::new());
            enc.write_all(raw)?;
            enc.finish().context("LZ4 frame compression failed")
        }
        CompressionFormat::Gzip => {
            let mut enc = GzEncoder::new(Vec::new(), Compression::default());
            enc.write_all(raw)?;
            enc.finish().context("Gzip compression failed")
        }
        CompressionFormat::Xz => bail!("XZ ramdisk recompression is not supported"),
    }
}

pub fn decompress_lz4_legacy(data: &[u8]) -> Result<Vec<u8>> {
    if data.len() < 4 || data[0..4] != LZ4_LEGACY_MAGIC {
        bail!("Not an LZ4 legacy stream");
    }
    let mut pos = 4usize;
    let mut out = Vec::new();
    while pos + 4 <= data.len() {
        let block_size = u32::from_le_bytes(data[pos..pos + 4].try_into().unwrap()) as usize;
        pos += 4;
        if block_size == 0 {
            break;
        }
        // Check if another legacy magic header starts concatenated stream
        if block_size == u32::from_le_bytes(LZ4_LEGACY_MAGIC) as usize {
            continue;
        }
        if pos + block_size > data.len() {
            bail!(
                "Truncated LZ4 legacy block at offset {pos}: need {block_size} bytes, have {}",
                data.len() - pos
            );
        }
        let chunk = &data[pos..pos + block_size];
        pos += block_size;
        let decoded = lz4_flex::block::decompress(chunk, LZ4_LEGACY_BLOCK_SIZE)
            .with_context(|| format!("LZ4 legacy block decode failed at offset {pos}"))?;
        out.extend_from_slice(&decoded);
    }
    Ok(out)
}

pub fn compress_lz4_legacy(raw: &[u8]) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(raw.len() / 2 + 16);
    out.extend_from_slice(&LZ4_LEGACY_MAGIC);
    for chunk in raw.chunks(LZ4_LEGACY_BLOCK_SIZE) {
        let comp = lz4_flex::block::compress(chunk);
        out.extend_from_slice(&(comp.len() as u32).to_le_bytes());
        out.extend_from_slice(&comp);
    }
    Ok(out)
}

/// Stream-decompress an image file (`.img`, `.gz`, `.xz`) to `dst_path`.
pub fn stream_decompress_file<F>(
    src_path: &std::path::Path,
    dst_path: &std::path::Path,
    mut progress_cb: F,
) -> Result<CompressionFormat>
where
    F: FnMut(u64),
{
    let mut src = std::fs::File::open(src_path)?;
    let mut head = [0u8; 6];
    let n = src.read(&mut head)?;
    let fmt = detect_compression(&head[..n]);
    drop(src);

    let src = std::fs::File::open(src_path)?;
    let mut dst = std::fs::File::create(dst_path)?;
    let mut buf = vec![0u8; 1024 * 1024];
    let mut written = 0u64;

    match fmt {
        CompressionFormat::Raw => {
            let mut r = src;
            loop {
                let k = r.read(&mut buf)?;
                if k == 0 {
                    break;
                }
                dst.write_all(&buf[..k])?;
                written += k as u64;
                progress_cb(written);
            }
        }
        CompressionFormat::Gzip => {
            let mut dec = GzDecoder::new(BufReader::new(src));
            loop {
                let k = dec.read(&mut buf)?;
                if k == 0 {
                    break;
                }
                dst.write_all(&buf[..k])?;
                written += k as u64;
                progress_cb(written);
            }
        }
        CompressionFormat::Xz => {
            let mut br = BufReader::new(src);
            // Stream-decompress xz via counting writer
            struct CountingWriter<'a, W: Write, F: FnMut(u64)> {
                inner: &'a mut W,
                written: u64,
                last_report: u64,
                cb: F,
            }
            impl<W: Write, F: FnMut(u64)> Write for CountingWriter<'_, W, F> {
                fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
                    let k = self.inner.write(buf)?;
                    self.written += k as u64;
                    if self.written - self.last_report >= 8 * 1024 * 1024 {
                        self.last_report = self.written;
                        (self.cb)(self.written);
                    }
                    Ok(k)
                }
                fn flush(&mut self) -> std::io::Result<()> {
                    self.inner.flush()
                }
            }
            let mut cw = CountingWriter {
                inner: &mut dst,
                written: 0,
                last_report: 0,
                cb: progress_cb,
            };
            lzma_rs::xz_decompress(&mut br, &mut cw).context("XZ stream decompression failed")?;
            (cw.cb)(cw.written);
        }
        CompressionFormat::Lz4Legacy => {
            let raw = std::fs::read(src_path)?;
            let dec = decompress_lz4_legacy(&raw)?;
            dst.write_all(&dec)?;
            progress_cb(dec.len() as u64);
        }
        CompressionFormat::Lz4Frame => {
            let mut dec = lz4_flex::frame::FrameDecoder::new(BufReader::new(src));
            loop {
                let k = dec.read(&mut buf)?;
                if k == 0 {
                    break;
                }
                dst.write_all(&buf[..k])?;
                written += k as u64;
                progress_cb(written);
            }
        }
    }
    dst.sync_all()?;
    Ok(fmt)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lz4_legacy_roundtrip() {
        let sample = b"AVBForge test data ".repeat(10000);
        let comp = compress_lz4_legacy(&sample).unwrap();
        assert_eq!(&comp[0..4], &LZ4_LEGACY_MAGIC);
        let (fmt, dec) = decompress_ramdisk(&comp).unwrap();
        assert_eq!(fmt, CompressionFormat::Lz4Legacy);
        assert_eq!(dec, sample);
    }
}
