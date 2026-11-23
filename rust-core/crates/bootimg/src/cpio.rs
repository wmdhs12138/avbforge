use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CpioEntry {
    pub name: String,
    pub ino: u32,
    pub mode: u32,
    pub uid: u32,
    pub gid: u32,
    pub nlink: u32,
    pub mtime: u32,
    #[serde(skip)]
    pub data: Vec<u8>,
    #[serde(skip)]
    pub raw_chunk: Vec<u8>,
}

#[derive(Clone, Debug)]
pub struct CpioArchive {
    pub entries: Vec<CpioEntry>,
}

impl CpioArchive {
    pub fn parse(buf: &[u8]) -> Result<Self> {
        let mut pos = 0usize;
        let mut entries = Vec::new();
        while pos + 110 <= buf.len() {
            if &buf[pos..pos + 6] != b"070701" {
                bail!("Unexpected CPIO magic at offset {pos}: {:?}", &buf[pos..pos + 6]);
            }
            let parse_hex = |start: usize| -> Result<u32> {
                let s = std::str::from_utf8(&buf[start..start + 8])?;
                Ok(u32::from_str_radix(s, 16)?)
            };
            let ino = parse_hex(pos + 6)?;
            let mode = parse_hex(pos + 14)?;
            let uid = parse_hex(pos + 22)?;
            let gid = parse_hex(pos + 30)?;
            let nlink = parse_hex(pos + 38)?;
            let mtime = parse_hex(pos + 46)?;
            let filesize = parse_hex(pos + 54)? as usize;
            let namesize = parse_hex(pos + 94)? as usize;
            if namesize == 0 {
                bail!("Invalid zero namesize in CPIO at {pos}");
            }
            let name_start = pos + 110;
            let name_end = name_start + namesize - 1;
            if name_end > buf.len() {
                bail!("Truncated CPIO filename at {pos}");
            }
            let name = String::from_utf8_lossy(&buf[name_start..name_end]).to_string();
            let data_start = (name_start + namesize + 3) & !3;
            let data_end = data_start + filesize;
            if data_end > buf.len() {
                bail!("Truncated CPIO data for '{name}' at {pos}");
            }
            let next_pos = (data_end + 3) & !3;
            if name == "TRAILER!!!" {
                pos = next_pos;
                while pos < buf.len() && buf[pos] == 0 {
                    pos += 1;
                }
                if pos >= buf.len() {
                    break;
                }
                continue;
            }
            entries.push(CpioEntry {
                name,
                ino,
                mode,
                uid,
                gid,
                nlink,
                mtime,
                data: buf[data_start..data_end].to_vec(),
                raw_chunk: buf[pos..next_pos].to_vec(),
            });
            pos = next_pos;
        }
        Ok(Self { entries })
    }

    pub fn get(&self, name: &str) -> Option<&CpioEntry> {
        let norm = name.trim_start_matches('/');
        self.entries.iter().find(|e| e.name.trim_start_matches('/') == norm)
    }

    pub fn has(&self, name: &str) -> bool {
        self.get(name).is_some()
    }

    /// Remove all entries matching normalized `name`. Returns true if any entry was removed.
    pub fn remove_entry(&mut self, name: &str) -> bool {
        let norm = name.trim_start_matches('/');
        let before = self.entries.len();
        self.entries
            .retain(|e| e.name.trim_start_matches('/') != norm);
        self.entries.len() != before
    }

    /// Rename entry `from` to `to` (replacing any existing `to`), preserving original mode/uid/gid/mtime/data.
    pub fn rename_entry(&mut self, from: &str, to: &str) -> bool {
        let from_norm = from.trim_start_matches('/');
        let to_norm = to.trim_start_matches('/');
        if !self.has(from_norm) {
            return false;
        }
        if from_norm != to_norm {
            self.remove_entry(to_norm);
        }
        if let Some(entry) = self
            .entries
            .iter_mut()
            .find(|e| e.name.trim_start_matches('/') == from_norm)
        {
            entry.name = to_norm.to_string();
            entry.raw_chunk = encode_cpio_entry_full(
                to_norm,
                entry.mode,
                entry.uid,
                entry.gid,
                entry.nlink,
                entry.mtime,
                &entry.data,
                entry.ino,
            );
            true
        } else {
            false
        }
    }

    /// Strip KernelSU LKM ramdisk patch (`init.real -> init`, remove `kernelsu.ko` & `ksu_config`).
    /// Returns true if any KernelSU artifact was detected and stripped.
    pub fn strip_kernelsu(&mut self) -> bool {
        let mut modified = false;
        if self.has("init.real") {
            modified |= self.rename_entry("init.real", "init");
        }
        modified |= self.remove_entry("kernelsu.ko");
        modified |= self.remove_entry("ksu_config");
        modified
    }

    /// Ensure parent directories exist and insert/replace a regular file entry (0644, root:root).
    pub fn upsert_file(&mut self, path: &str, mode: u32, data: Vec<u8>) {
        let norm = path.trim_start_matches('/');
        let mut max_ino = self.entries.iter().map(|e| e.ino).max().unwrap_or(1000);

        // Ensure intermediate directories exist
        let parts: Vec<&str> = norm.split('/').collect();
        if parts.len() > 1 {
            let mut cur = String::new();
            for dir in &parts[..parts.len() - 1] {
                if !cur.is_empty() {
                    cur.push('/');
                }
                cur.push_str(dir);
                if !self.has(&cur) {
                    max_ino += 1;
                    let chunk = encode_cpio_entry(&cur, 0o040755, &[], max_ino, 2);
                    self.entries.push(CpioEntry {
                        name: cur.clone(),
                        ino: max_ino,
                        mode: 0o040755,
                        uid: 0,
                        gid: 0,
                        nlink: 2,
                        mtime: 0,
                        data: Vec::new(),
                        raw_chunk: chunk,
                    });
                }
            }
        }

        if let Some(existing) = self
            .entries
            .iter_mut()
            .find(|e| e.name.trim_start_matches('/') == norm)
        {
            let chunk = encode_cpio_entry(norm, mode, &data, existing.ino, 1);
            existing.mode = mode;
            existing.data = data;
            existing.raw_chunk = chunk;
        } else {
            max_ino += 1;
            let chunk = encode_cpio_entry(norm, mode, &data, max_ino, 1);
            self.entries.push(CpioEntry {
                name: norm.to_string(),
                ino: max_ino,
                mode,
                uid: 0,
                gid: 0,
                nlink: 1,
                mtime: 0,
                data,
                raw_chunk: chunk,
            });
        }
    }

    /// Losslessly serialize preserving every unmodified entry's exact raw chunk.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        for e in &self.entries {
            out.extend_from_slice(&e.raw_chunk);
        }
        out.extend_from_slice(&encode_cpio_entry("TRAILER!!!", 0, &[], 0, 1));
        while out.len() % 512 != 0 {
            out.push(0);
        }
        out
    }
}

pub fn encode_cpio_entry(name: &str, mode: u32, data: &[u8], ino: u32, nlink: u32) -> Vec<u8> {
    encode_cpio_entry_full(name, mode, 0, 0, nlink, 0, data, ino)
}

#[allow(clippy::too_many_arguments)]
pub fn encode_cpio_entry_full(
    name: &str,
    mode: u32,
    uid: u32,
    gid: u32,
    nlink: u32,
    mtime: u32,
    data: &[u8],
    ino: u32,
) -> Vec<u8> {
    let name_bytes = [name.as_bytes(), &[0u8]].concat();
    let hdr = format!(
        "070701{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}{:08X}",
        ino,
        mode,
        uid,
        gid,
        nlink,
        mtime,
        data.len() as u32,
        0u32,
        0u32,
        0u32,
        0u32,
        name_bytes.len() as u32,
        0u32,
    );
    let mut out = Vec::with_capacity(110 + name_bytes.len() + data.len() + 8);
    out.extend_from_slice(hdr.as_bytes());
    out.extend_from_slice(&name_bytes);
    while out.len() % 4 != 0 {
        out.push(0);
    }
    out.extend_from_slice(data);
    while out.len() % 4 != 0 {
        out.push(0);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cpio_lossless_upsert() {
        let mut a = CpioArchive { entries: vec![] };
        a.upsert_file("init", 0o100750, b"ELF-init".to_vec());
        a.upsert_file("first_stage_ramdisk/avb/q-gsi.avbpubkey", 0o100644, vec![0x42; 520]);
        let bytes = a.to_bytes();
        let parsed = CpioArchive::parse(&bytes).unwrap();
        assert_eq!(parsed.get("init").unwrap().data, b"ELF-init");
        assert_eq!(parsed.get("init").unwrap().mode, 0o100750);
        assert_eq!(
            parsed.get("first_stage_ramdisk/avb/q-gsi.avbpubkey").unwrap().data,
            vec![0x42; 520]
        );
    }

    #[test]
    fn cpio_strip_kernelsu_restores_stock_init() {
        let mut a = CpioArchive { entries: vec![] };
        a.upsert_file("init", 0o100755, b"KSU-STUB-INIT".to_vec());
        a.upsert_file("init.real", 0o100750, b"STOCK-ANDROID-INIT".to_vec());
        a.upsert_file("kernelsu.ko", 0o100644, vec![0xAA; 128]);
        a.upsert_file("ksu_config", 0o100644, b"KSU_VER=3".to_vec());
        a.upsert_file("first_stage_ramdisk/avb/q-gsi.avbpubkey", 0o100644, vec![0x11; 520]);

        assert!(a.strip_kernelsu());
        let bytes = a.to_bytes();
        let parsed = CpioArchive::parse(&bytes).unwrap();
        assert!(!parsed.has("init.real"));
        assert!(!parsed.has("kernelsu.ko"));
        assert!(!parsed.has("ksu_config"));
        assert_eq!(parsed.get("init").unwrap().data, b"STOCK-ANDROID-INIT");
        assert_eq!(parsed.get("init").unwrap().mode, 0o100750);
        assert_eq!(
            parsed.get("first_stage_ramdisk/avb/q-gsi.avbpubkey").unwrap().data,
            vec![0x11; 520]
        );
    }
}
