use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};

use crate::compress::{compress_ramdisk, decompress_ramdisk, CompressionFormat};
use crate::cpio::CpioArchive;

pub const BOOT_MAGIC: &[u8; 8] = b"ANDROID!";
pub const VENDOR_BOOT_MAGIC: &[u8; 8] = b"VNDRBOOT";
pub const BOOT_V3_V4_PAGE_SIZE: usize = 4096;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BootImageInfo {
    pub header_version: u32,
    pub kernel_size: u32,
    pub ramdisk_size: u32,
    pub os_version: u32,
    pub header_size: u32,
    pub signature_size: u32,
    pub ramdisk_compression: CompressionFormat,
    pub has_kernelsu: bool,
    pub avbpubkey_entries: Vec<String>,
}

fn align(val: usize, page: usize) -> usize {
    let rem = val % page;
    if rem == 0 {
        val
    } else {
        val + (page - rem)
    }
}

/// Parse an unsigned/raw Android Boot Image v3 or v4 (pass slice up to `original_image_size` if AVB-signed).
pub struct BootImageV3V4 {
    pub header_page: Vec<u8>,
    pub header_version: u32,
    pub kernel: Vec<u8>,
    pub ramdisk_raw: Vec<u8>,
    pub boot_signature: Vec<u8>,
}

impl BootImageV3V4 {
    pub fn parse(data: &[u8]) -> Result<Self> {
        if data.len() < BOOT_V3_V4_PAGE_SIZE || &data[0..8] != BOOT_MAGIC {
            bail!("Invalid Android boot image magic");
        }
        let kernel_size = u32::from_le_bytes(data[8..12].try_into().unwrap()) as usize;
        let ramdisk_size = u32::from_le_bytes(data[12..16].try_into().unwrap()) as usize;
        let header_version = u32::from_le_bytes(data[40..44].try_into().unwrap());
        if header_version != 3 && header_version != 4 {
            bail!("Unsupported boot header version: {header_version} (expected 3 or 4)");
        }
        let signature_size = if header_version == 4 {
            u32::from_le_bytes(data[1580..1584].try_into().unwrap()) as usize
        } else {
            0
        };

        let kernel_off = BOOT_V3_V4_PAGE_SIZE;
        let ramdisk_off = kernel_off + align(kernel_size, BOOT_V3_V4_PAGE_SIZE);
        let sig_off = ramdisk_off + align(ramdisk_size, BOOT_V3_V4_PAGE_SIZE);

        if ramdisk_off + ramdisk_size > data.len() {
            bail!("Truncated boot image ramdisk");
        }
        let kernel = data[kernel_off..kernel_off + kernel_size].to_vec();
        let ramdisk_raw = data[ramdisk_off..ramdisk_off + ramdisk_size].to_vec();
        let boot_signature = if signature_size > 0 && sig_off + signature_size <= data.len() {
            data[sig_off..sig_off + signature_size].to_vec()
        } else {
            Vec::new()
        };

        Ok(Self {
            header_page: data[0..BOOT_V3_V4_PAGE_SIZE].to_vec(),
            header_version,
            kernel,
            ramdisk_raw,
            boot_signature,
        })
    }

    pub fn unpack_ramdisk(&self) -> Result<(CompressionFormat, CpioArchive)> {
        let (fmt, decompressed) = decompress_ramdisk(&self.ramdisk_raw)?;
        let cpio = CpioArchive::parse(&decompressed)?;
        Ok((fmt, cpio))
    }

    pub fn repack_with_ramdisk(&self, fmt: CompressionFormat, cpio: &CpioArchive) -> Result<Vec<u8>> {
        let cpio_bytes = cpio.to_bytes();
        let new_rd = compress_ramdisk(fmt, &cpio_bytes)?;

        let mut hdr = self.header_page.clone();
        hdr[12..16].copy_from_slice(&(new_rd.len() as u32).to_le_bytes());

        let k_padded = align(self.kernel.len(), BOOT_V3_V4_PAGE_SIZE);
        let r_padded = align(new_rd.len(), BOOT_V3_V4_PAGE_SIZE);
        let s_padded = align(self.boot_signature.len(), BOOT_V3_V4_PAGE_SIZE);

        let mut out = Vec::with_capacity(BOOT_V3_V4_PAGE_SIZE + k_padded + r_padded + s_padded);
        out.extend_from_slice(&hdr);
        out.extend_from_slice(&self.kernel);
        out.resize(BOOT_V3_V4_PAGE_SIZE + k_padded, 0);
        out.extend_from_slice(&new_rd);
        out.resize(BOOT_V3_V4_PAGE_SIZE + k_padded + r_padded, 0);
        if !self.boot_signature.is_empty() {
            out.extend_from_slice(&self.boot_signature);
            out.resize(BOOT_V3_V4_PAGE_SIZE + k_padded + r_padded + s_padded, 0);
        }
        Ok(out)
    }

    pub fn inspect(&self) -> Result<BootImageInfo> {
        let os_version = u32::from_le_bytes(self.header_page[16..20].try_into().unwrap());
        let header_size = u32::from_le_bytes(self.header_page[20..24].try_into().unwrap());
        let (fmt, cpio) = self.unpack_ramdisk()?;
        let has_kernelsu = cpio.has("kernelsu.ko") || cpio.has("init.real");
        let avbpubkey_entries = cpio
            .entries
            .iter()
            .filter(|e| e.name.ends_with(".avbpubkey"))
            .map(|e| e.name.clone())
            .collect();
        Ok(BootImageInfo {
            header_version: self.header_version,
            kernel_size: self.kernel.len() as u32,
            ramdisk_size: self.ramdisk_raw.len() as u32,
            os_version,
            header_size,
            signature_size: self.boot_signature.len() as u32,
            ramdisk_compression: fmt,
            has_kernelsu,
            avbpubkey_entries,
        })
    }
}

/// Extract all `avb_keys=...` paths found inside a `vendor_boot` image's first-stage `fstab.*` files.
pub fn extract_fstab_avb_keys_from_vendor_boot(data: &[u8]) -> Result<Vec<String>> {
    if data.len() < 2128 || &data[0..8] != VENDOR_BOOT_MAGIC {
        bail!("Invalid vendor_boot magic");
    }
    let hdr_ver = u32::from_le_bytes(data[8..12].try_into().unwrap());
    let page_sz = u32::from_le_bytes(data[12..16].try_into().unwrap()) as usize;
    let vrd_sz = u32::from_le_bytes(data[24..28].try_into().unwrap()) as usize;
    let hdr_sz = if hdr_ver >= 4 { 2128 } else { 2112 };
    let vrd_off = align(hdr_sz, page_sz);
    if vrd_off + vrd_sz > data.len() {
        bail!("Truncated vendor_boot ramdisk");
    }
    let vrd = &data[vrd_off..vrd_off + vrd_sz];
    let (_, decompressed) = decompress_ramdisk(vrd)?;
    let cpio = CpioArchive::parse(&decompressed)?;
    let mut keys = Vec::new();
    for e in &cpio.entries {
        if e.name.contains("fstab.") {
            let text = String::from_utf8_lossy(&e.data);
            for k in parse_avb_keys_from_fstab(&text) {
                if !keys.contains(&k) {
                    keys.push(k);
                }
            }
        }
    }
    Ok(keys)
}

pub fn parse_avb_keys_from_fstab(fstab_text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for line in fstab_text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        if let Some(idx) = trimmed.find("avb_keys=") {
            let rest = &trimmed[idx + "avb_keys=".len()..];
            let list = rest
                .split(|c: char| c == ',' || c.is_whitespace())
                .next()
                .unwrap_or("");
            for p in list.split(':') {
                if !p.is_empty() && !out.iter().any(|x| x == p) {
                    out.push(p.to_string());
                }
            }
        }
    }
    out
}
