use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};

pub const TAG_PROPERTY: u64 = 0;
pub const TAG_HASHTREE: u64 = 1;
pub const TAG_HASH: u64 = 2;
pub const TAG_KERNEL_CMDLINE: u64 = 3;
pub const TAG_CHAIN_PARTITION: u64 = 4;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AvbDescriptor {
    Property(PropertyDescriptor),
    Hashtree(HashtreeDescriptor),
    Hash(HashDescriptor),
    KernelCmdline(KernelCmdlineDescriptor),
    ChainPartition(ChainPartitionDescriptor),
    Unknown { tag: u64, data: Vec<u8> },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PropertyDescriptor {
    pub key: String,
    pub value: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct HashtreeDescriptor {
    pub dm_verity_version: u32,
    pub image_size: u64,
    pub tree_offset: u64,
    pub tree_size: u64,
    pub data_block_size: u32,
    pub hash_block_size: u32,
    pub fec_num_roots: u32,
    pub fec_offset: u64,
    pub fec_size: u64,
    pub hash_algorithm: String,
    pub partition_name: String,
    pub salt: Vec<u8>,
    pub root_digest: Vec<u8>,
    pub flags: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct HashDescriptor {
    pub image_size: u64,
    pub hash_algorithm: String,
    pub partition_name: String,
    pub salt: Vec<u8>,
    pub digest: Vec<u8>,
    pub flags: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct KernelCmdlineDescriptor {
    pub flags: u32,
    pub cmdline: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChainPartitionDescriptor {
    pub rollback_index_location: u32,
    pub partition_name: String,
    pub public_key: Vec<u8>,
    pub flags: u32,
}

fn pad8(buf: &mut Vec<u8>) {
    while buf.len() % 8 != 0 {
        buf.push(0);
    }
}

fn encode_fixed_str<const N: usize>(s: &str) -> Result<[u8; N]> {
    let b = s.as_bytes();
    if b.len() > N {
        bail!("String '{}' exceeds {} bytes", s, N);
    }
    let mut out = [0u8; N];
    out[..b.len()].copy_from_slice(b);
    Ok(out)
}

fn decode_cstr(bytes: &[u8]) -> String {
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).to_string()
}

impl AvbDescriptor {
    pub fn encode(&self) -> Result<Vec<u8>> {
        let (tag, mut body) = match self {
            AvbDescriptor::Property(p) => {
                let kb = p.key.as_bytes();
                let vb = p.value.as_bytes();
                let mut b = Vec::with_capacity(16 + kb.len() + 1 + vb.len() + 1 + 8);
                b.extend_from_slice(&(kb.len() as u64).to_be_bytes());
                b.extend_from_slice(&(vb.len() as u64).to_be_bytes());
                b.extend_from_slice(kb);
                b.push(0);
                b.extend_from_slice(vb);
                b.push(0);
                (TAG_PROPERTY, b)
            }
            AvbDescriptor::Hashtree(h) => {
                let pname = h.partition_name.as_bytes();
                let halg = encode_fixed_str::<32>(&h.hash_algorithm)?;
                let mut b = Vec::with_capacity(164 + pname.len() + h.salt.len() + h.root_digest.len() + 8);
                b.extend_from_slice(&h.dm_verity_version.to_be_bytes());
                b.extend_from_slice(&h.image_size.to_be_bytes());
                b.extend_from_slice(&h.tree_offset.to_be_bytes());
                b.extend_from_slice(&h.tree_size.to_be_bytes());
                b.extend_from_slice(&h.data_block_size.to_be_bytes());
                b.extend_from_slice(&h.hash_block_size.to_be_bytes());
                b.extend_from_slice(&h.fec_num_roots.to_be_bytes());
                b.extend_from_slice(&h.fec_offset.to_be_bytes());
                b.extend_from_slice(&h.fec_size.to_be_bytes());
                b.extend_from_slice(&halg);
                b.extend_from_slice(&(pname.len() as u32).to_be_bytes());
                b.extend_from_slice(&(h.salt.len() as u32).to_be_bytes());
                b.extend_from_slice(&(h.root_digest.len() as u32).to_be_bytes());
                b.extend_from_slice(&h.flags.to_be_bytes());
                b.extend_from_slice(&[0u8; 60]);
                b.extend_from_slice(pname);
                b.extend_from_slice(&h.salt);
                b.extend_from_slice(&h.root_digest);
                (TAG_HASHTREE, b)
            }
            AvbDescriptor::Hash(h) => {
                let pname = h.partition_name.as_bytes();
                let halg = encode_fixed_str::<32>(&h.hash_algorithm)?;
                let mut b = Vec::with_capacity(116 + pname.len() + h.salt.len() + h.digest.len() + 8);
                b.extend_from_slice(&h.image_size.to_be_bytes());
                b.extend_from_slice(&halg);
                b.extend_from_slice(&(pname.len() as u32).to_be_bytes());
                b.extend_from_slice(&(h.salt.len() as u32).to_be_bytes());
                b.extend_from_slice(&(h.digest.len() as u32).to_be_bytes());
                b.extend_from_slice(&h.flags.to_be_bytes());
                b.extend_from_slice(&[0u8; 60]);
                b.extend_from_slice(pname);
                b.extend_from_slice(&h.salt);
                b.extend_from_slice(&h.digest);
                (TAG_HASH, b)
            }
            AvbDescriptor::KernelCmdline(c) => {
                let cb = c.cmdline.as_bytes();
                let mut b = Vec::with_capacity(8 + cb.len() + 8);
                b.extend_from_slice(&c.flags.to_be_bytes());
                b.extend_from_slice(&(cb.len() as u32).to_be_bytes());
                b.extend_from_slice(cb);
                (TAG_KERNEL_CMDLINE, b)
            }
            AvbDescriptor::ChainPartition(c) => {
                let pname = c.partition_name.as_bytes();
                let mut b = Vec::with_capacity(76 + pname.len() + c.public_key.len() + 8);
                b.extend_from_slice(&c.rollback_index_location.to_be_bytes());
                b.extend_from_slice(&(pname.len() as u32).to_be_bytes());
                b.extend_from_slice(&(c.public_key.len() as u32).to_be_bytes());
                b.extend_from_slice(&c.flags.to_be_bytes());
                b.extend_from_slice(&[0u8; 60]);
                b.extend_from_slice(pname);
                b.extend_from_slice(&c.public_key);
                (TAG_CHAIN_PARTITION, b)
            }
            AvbDescriptor::Unknown { tag, data } => (*tag, data.clone()),
        };

        pad8(&mut body);
        let mut out = Vec::with_capacity(16 + body.len());
        out.extend_from_slice(&tag.to_be_bytes());
        out.extend_from_slice(&(body.len() as u64).to_be_bytes());
        out.extend_from_slice(&body);
        Ok(out)
    }

    pub fn parse_all(data: &[u8]) -> Result<Vec<AvbDescriptor>> {
        let mut pos = 0usize;
        let mut out = Vec::new();
        while pos < data.len() {
            if pos + 16 > data.len() {
                bail!("Truncated AVB descriptor header at offset {pos}");
            }
            let tag = u64::from_be_bytes(data[pos..pos + 8].try_into().unwrap());
            let nbytes = u64::from_be_bytes(data[pos + 8..pos + 16].try_into().unwrap()) as usize;
            pos += 16;
            if pos + nbytes > data.len() {
                bail!("Truncated AVB descriptor body (tag={tag}, len={nbytes})");
            }
            let body = &data[pos..pos + nbytes];
            pos += nbytes;

            let desc = match tag {
                TAG_PROPERTY => {
                    if body.len() < 16 {
                        bail!("Invalid PropertyDescriptor length");
                    }
                    let klen = u64::from_be_bytes(body[0..8].try_into().unwrap()) as usize;
                    let vlen = u64::from_be_bytes(body[8..16].try_into().unwrap()) as usize;
                    if 16 + klen + 1 + vlen > body.len() {
                        bail!("PropertyDescriptor out of bounds");
                    }
                    let key = String::from_utf8_lossy(&body[16..16 + klen]).to_string();
                    let value = String::from_utf8_lossy(&body[16 + klen + 1..16 + klen + 1 + vlen]).to_string();
                    AvbDescriptor::Property(PropertyDescriptor { key, value })
                }
                TAG_HASHTREE => {
                    if body.len() < 164 {
                        bail!("Invalid HashtreeDescriptor length");
                    }
                    let dm_verity_version = u32::from_be_bytes(body[0..4].try_into().unwrap());
                    let image_size = u64::from_be_bytes(body[4..12].try_into().unwrap());
                    let tree_offset = u64::from_be_bytes(body[12..20].try_into().unwrap());
                    let tree_size = u64::from_be_bytes(body[20..28].try_into().unwrap());
                    let data_block_size = u32::from_be_bytes(body[28..32].try_into().unwrap());
                    let hash_block_size = u32::from_be_bytes(body[32..36].try_into().unwrap());
                    let fec_num_roots = u32::from_be_bytes(body[36..40].try_into().unwrap());
                    let fec_offset = u64::from_be_bytes(body[40..48].try_into().unwrap());
                    let fec_size = u64::from_be_bytes(body[48..56].try_into().unwrap());
                    let hash_algorithm = decode_cstr(&body[56..88]);
                    let pname_len = u32::from_be_bytes(body[88..92].try_into().unwrap()) as usize;
                    let salt_len = u32::from_be_bytes(body[92..96].try_into().unwrap()) as usize;
                    let digest_len = u32::from_be_bytes(body[96..100].try_into().unwrap()) as usize;
                    let flags = u32::from_be_bytes(body[100..104].try_into().unwrap());
                    let base = 164;
                    if base + pname_len + salt_len + digest_len > body.len() {
                        bail!("HashtreeDescriptor payload out of bounds");
                    }
                    let partition_name = String::from_utf8_lossy(&body[base..base + pname_len]).to_string();
                    let salt = body[base + pname_len..base + pname_len + salt_len].to_vec();
                    let root_digest =
                        body[base + pname_len + salt_len..base + pname_len + salt_len + digest_len].to_vec();
                    AvbDescriptor::Hashtree(HashtreeDescriptor {
                        dm_verity_version,
                        image_size,
                        tree_offset,
                        tree_size,
                        data_block_size,
                        hash_block_size,
                        fec_num_roots,
                        fec_offset,
                        fec_size,
                        hash_algorithm,
                        partition_name,
                        salt,
                        root_digest,
                        flags,
                    })
                }
                TAG_HASH => {
                    if body.len() < 116 {
                        bail!("Invalid HashDescriptor length");
                    }
                    let image_size = u64::from_be_bytes(body[0..8].try_into().unwrap());
                    let hash_algorithm = decode_cstr(&body[8..40]);
                    let pname_len = u32::from_be_bytes(body[40..44].try_into().unwrap()) as usize;
                    let salt_len = u32::from_be_bytes(body[44..48].try_into().unwrap()) as usize;
                    let digest_len = u32::from_be_bytes(body[48..52].try_into().unwrap()) as usize;
                    let flags = u32::from_be_bytes(body[52..56].try_into().unwrap());
                    let base = 116;
                    if base + pname_len + salt_len + digest_len > body.len() {
                        bail!("HashDescriptor payload out of bounds");
                    }
                    let partition_name = String::from_utf8_lossy(&body[base..base + pname_len]).to_string();
                    let salt = body[base + pname_len..base + pname_len + salt_len].to_vec();
                    let digest =
                        body[base + pname_len + salt_len..base + pname_len + salt_len + digest_len].to_vec();
                    AvbDescriptor::Hash(HashDescriptor {
                        image_size,
                        hash_algorithm,
                        partition_name,
                        salt,
                        digest,
                        flags,
                    })
                }
                TAG_KERNEL_CMDLINE => {
                    if body.len() < 8 {
                        bail!("Invalid KernelCmdlineDescriptor length");
                    }
                    let flags = u32::from_be_bytes(body[0..4].try_into().unwrap());
                    let clen = u32::from_be_bytes(body[4..8].try_into().unwrap()) as usize;
                    if 8 + clen > body.len() {
                        bail!("KernelCmdlineDescriptor out of bounds");
                    }
                    let cmdline = String::from_utf8_lossy(&body[8..8 + clen]).to_string();
                    AvbDescriptor::KernelCmdline(KernelCmdlineDescriptor { flags, cmdline })
                }
                TAG_CHAIN_PARTITION => {
                    if body.len() < 76 {
                        bail!("Invalid ChainPartitionDescriptor length");
                    }
                    let rollback_index_location = u32::from_be_bytes(body[0..4].try_into().unwrap());
                    let pname_len = u32::from_be_bytes(body[4..8].try_into().unwrap()) as usize;
                    let pk_len = u32::from_be_bytes(body[8..12].try_into().unwrap()) as usize;
                    let flags = u32::from_be_bytes(body[12..16].try_into().unwrap());
                    let base = 76;
                    if base + pname_len + pk_len > body.len() {
                        bail!("ChainPartitionDescriptor payload out of bounds");
                    }
                    let partition_name = String::from_utf8_lossy(&body[base..base + pname_len]).to_string();
                    let public_key = body[base + pname_len..base + pname_len + pk_len].to_vec();
                    AvbDescriptor::ChainPartition(ChainPartitionDescriptor {
                        rollback_index_location,
                        partition_name,
                        public_key,
                        flags,
                    })
                }
                other => AvbDescriptor::Unknown {
                    tag: other,
                    data: body.to_vec(),
                },
            };
            out.push(desc);
        }
        Ok(out)
    }

    pub fn partition_name(&self) -> Option<&str> {
        match self {
            AvbDescriptor::Hash(h) => Some(&h.partition_name),
            AvbDescriptor::Hashtree(h) => Some(&h.partition_name),
            AvbDescriptor::ChainPartition(c) => Some(&c.partition_name),
            _ => None,
        }
    }
}
