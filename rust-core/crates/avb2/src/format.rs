use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256, Sha512};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use crate::descriptors::AvbDescriptor;
use crate::key::{AvbPrivateKey, AvbPublicKey};

pub const VBMETA_MAGIC: &[u8; 4] = b"AVB0";
pub const FOOTER_MAGIC: &[u8; 4] = b"AVBf";
pub const VBMETA_HEADER_SIZE: usize = 256;
pub const FOOTER_SIZE: usize = 64;
pub const RELEASE_STRING_DEFAULT: &str = "avbforge 0.1.0";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum AlgorithmType {
    None = 0,
    Sha256Rsa2048 = 1,
    Sha256Rsa4096 = 2,
    Sha512Rsa2048 = 4,
    Sha512Rsa4096 = 5,
}

impl AlgorithmType {
    pub fn from_u32(v: u32) -> Result<Self> {
        match v {
            0 => Ok(Self::None),
            1 => Ok(Self::Sha256Rsa2048),
            2 => Ok(Self::Sha256Rsa4096),
            4 => Ok(Self::Sha512Rsa2048),
            5 => Ok(Self::Sha512Rsa4096),
            _ => bail!("Unsupported AVB algorithm type: {v}"),
        }
    }

    pub fn from_name(name: &str) -> Result<Self> {
        match name.to_uppercase().as_str() {
            "NONE" => Ok(Self::None),
            "SHA256_RSA2048" => Ok(Self::Sha256Rsa2048),
            "SHA256_RSA4096" => Ok(Self::Sha256Rsa4096),
            "SHA512_RSA2048" => Ok(Self::Sha512Rsa2048),
            "SHA512_RSA4096" => Ok(Self::Sha512Rsa4096),
            _ => bail!("Unknown AVB algorithm: {name}"),
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::None => "NONE",
            Self::Sha256Rsa2048 => "SHA256_RSA2048",
            Self::Sha256Rsa4096 => "SHA256_RSA4096",
            Self::Sha512Rsa2048 => "SHA512_RSA2048",
            Self::Sha512Rsa4096 => "SHA512_RSA4096",
        }
    }

    pub fn hash_len(&self) -> usize {
        match self {
            Self::None => 0,
            Self::Sha256Rsa2048 | Self::Sha256Rsa4096 => 32,
            Self::Sha512Rsa2048 | Self::Sha512Rsa4096 => 64,
        }
    }

    pub fn signature_len(&self) -> usize {
        match self {
            Self::None => 0,
            Self::Sha256Rsa2048 | Self::Sha512Rsa2048 => 256,
            Self::Sha256Rsa4096 | Self::Sha512Rsa4096 => 512,
        }
    }

    pub fn key_bits(&self) -> usize {
        match self {
            Self::None => 0,
            Self::Sha256Rsa2048 | Self::Sha512Rsa2048 => 2048,
            Self::Sha256Rsa4096 | Self::Sha512Rsa4096 => 4096,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AvbFooter {
    pub version_major: u32,
    pub version_minor: u32,
    pub original_image_size: u64,
    pub vbmeta_offset: u64,
    pub vbmeta_size: u64,
}

impl AvbFooter {
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != FOOTER_SIZE {
            bail!("Footer must be 64 bytes");
        }
        if &bytes[0..4] != FOOTER_MAGIC {
            bail!("Invalid AVB footer magic");
        }
        Ok(Self {
            version_major: u32::from_be_bytes(bytes[4..8].try_into().unwrap()),
            version_minor: u32::from_be_bytes(bytes[8..12].try_into().unwrap()),
            original_image_size: u64::from_be_bytes(bytes[12..20].try_into().unwrap()),
            vbmeta_offset: u64::from_be_bytes(bytes[20..28].try_into().unwrap()),
            vbmeta_size: u64::from_be_bytes(bytes[28..36].try_into().unwrap()),
        })
    }

    pub fn encode(&self) -> [u8; FOOTER_SIZE] {
        let mut out = [0u8; FOOTER_SIZE];
        out[0..4].copy_from_slice(FOOTER_MAGIC);
        out[4..8].copy_from_slice(&self.version_major.to_be_bytes());
        out[8..12].copy_from_slice(&self.version_minor.to_be_bytes());
        out[12..20].copy_from_slice(&self.original_image_size.to_be_bytes());
        out[20..28].copy_from_slice(&self.vbmeta_offset.to_be_bytes());
        out[28..36].copy_from_slice(&self.vbmeta_size.to_be_bytes());
        out
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct VbMetaImage {
    pub required_libavb_version_major: u32,
    pub required_libavb_version_minor: u32,
    pub algorithm: AlgorithmType,
    pub rollback_index: u64,
    pub flags: u32,
    pub rollback_index_location: u32,
    pub release_string: String,
    pub descriptors: Vec<AvbDescriptor>,
    pub public_key: Vec<u8>,
    pub public_key_metadata: Vec<u8>,
    pub hash: Vec<u8>,
    pub signature: Vec<u8>,
    #[serde(skip)]
    pub raw_header: Vec<u8>,
    #[serde(skip)]
    pub raw_aux: Vec<u8>,
}

pub fn round_up(val: u64, align: u64) -> u64 {
    if align == 0 {
        return val;
    }
    let rem = val % align;
    if rem == 0 {
        val
    } else {
        val + (align - rem)
    }
}

impl VbMetaImage {
    pub fn parse(data: &[u8]) -> Result<Self> {
        if data.len() < VBMETA_HEADER_SIZE {
            bail!("VBMeta blob too small ({} bytes)", data.len());
        }
        if &data[0..4] != VBMETA_MAGIC {
            bail!("Invalid VBMeta magic: {:?}", &data[0..4]);
        }
        let major = u32::from_be_bytes(data[4..8].try_into().unwrap());
        let minor = u32::from_be_bytes(data[8..12].try_into().unwrap());
        let auth_size = u64::from_be_bytes(data[12..20].try_into().unwrap()) as usize;
        let aux_size = u64::from_be_bytes(data[20..28].try_into().unwrap()) as usize;
        let algorithm = AlgorithmType::from_u32(u32::from_be_bytes(data[28..32].try_into().unwrap()))?;
        let hash_off = u64::from_be_bytes(data[32..40].try_into().unwrap()) as usize;
        let hash_sz = u64::from_be_bytes(data[40..48].try_into().unwrap()) as usize;
        let sig_off = u64::from_be_bytes(data[48..56].try_into().unwrap()) as usize;
        let sig_sz = u64::from_be_bytes(data[56..64].try_into().unwrap()) as usize;
        let pk_off = u64::from_be_bytes(data[64..72].try_into().unwrap()) as usize;
        let pk_sz = u64::from_be_bytes(data[72..80].try_into().unwrap()) as usize;
        let pkm_off = u64::from_be_bytes(data[80..88].try_into().unwrap()) as usize;
        let pkm_sz = u64::from_be_bytes(data[88..96].try_into().unwrap()) as usize;
        let desc_off = u64::from_be_bytes(data[96..104].try_into().unwrap()) as usize;
        let desc_sz = u64::from_be_bytes(data[104..112].try_into().unwrap()) as usize;
        let rollback_index = u64::from_be_bytes(data[112..120].try_into().unwrap());
        let flags = u32::from_be_bytes(data[120..124].try_into().unwrap());
        let rollback_index_location = u32::from_be_bytes(data[124..128].try_into().unwrap());
        let rel_end = data[128..176].iter().position(|&b| b == 0).unwrap_or(48);
        let release_string = String::from_utf8_lossy(&data[128..128 + rel_end]).to_string();

        let total = VBMETA_HEADER_SIZE + auth_size + aux_size;
        if data.len() < total {
            bail!("VBMeta truncated: have {} bytes, need {}", data.len(), total);
        }
        let raw_header = data[0..VBMETA_HEADER_SIZE].to_vec();
        let auth = &data[VBMETA_HEADER_SIZE..VBMETA_HEADER_SIZE + auth_size];
        let aux = &data[VBMETA_HEADER_SIZE + auth_size..total];

        let hash = auth
            .get(hash_off..hash_off + hash_sz)
            .ok_or_else(|| anyhow::anyhow!("VBMeta hash range out of bounds"))?
            .to_vec();
        let signature = auth
            .get(sig_off..sig_off + sig_sz)
            .ok_or_else(|| anyhow::anyhow!("VBMeta signature range out of bounds"))?
            .to_vec();
        let public_key = aux
            .get(pk_off..pk_off + pk_sz)
            .ok_or_else(|| anyhow::anyhow!("VBMeta pubkey range out of bounds"))?
            .to_vec();
        let public_key_metadata = aux
            .get(pkm_off..pkm_off + pkm_sz)
            .ok_or_else(|| anyhow::anyhow!("VBMeta pubkey metadata out of bounds"))?
            .to_vec();
        let desc_bytes = aux
            .get(desc_off..desc_off + desc_sz)
            .ok_or_else(|| anyhow::anyhow!("VBMeta descriptors out of bounds"))?;
        let descriptors = AvbDescriptor::parse_all(desc_bytes)?;

        Ok(Self {
            required_libavb_version_major: major,
            required_libavb_version_minor: minor,
            algorithm,
            rollback_index,
            flags,
            rollback_index_location,
            release_string,
            descriptors,
            public_key,
            public_key_metadata,
            hash,
            signature,
            raw_header,
            raw_aux: aux.to_vec(),
        })
    }

    pub fn build_and_sign(
        descriptors: Vec<AvbDescriptor>,
        algorithm: AlgorithmType,
        key: Option<&AvbPrivateKey>,
        rollback_index: u64,
        rollback_index_location: u32,
        flags: u32,
        release_string: Option<&str>,
    ) -> Result<Vec<u8>> {
        let mut desc_bytes = Vec::new();
        for d in &descriptors {
            desc_bytes.extend_from_slice(&d.encode()?);
        }

        let pk_bytes = match (algorithm, key) {
            (AlgorithmType::None, _) => Vec::new(),
            (_, Some(k)) => k.public_key()?.raw_avb_key,
            (_, None) => bail!("Private key is required for algorithm {:?}", algorithm),
        };

        let desc_off = 0u64;
        let desc_sz = desc_bytes.len() as u64;
        let pk_off = desc_sz;
        let pk_sz = pk_bytes.len() as u64;
        let pkm_off = pk_off + pk_sz;
        let pkm_sz = 0u64;

        let mut aux = Vec::with_capacity((pkm_off as usize) + 64);
        aux.extend_from_slice(&desc_bytes);
        aux.extend_from_slice(&pk_bytes);
        let aux_padded_len = round_up(aux.len() as u64, 64) as usize;
        aux.resize(aux_padded_len, 0);

        let hash_off = 0u64;
        let hash_sz = algorithm.hash_len() as u64;
        let sig_off = hash_sz;
        let sig_sz = algorithm.signature_len() as u64;
        let auth_padded_len = round_up(hash_sz + sig_sz, 64) as usize;

        // Determine minimum libavb minor version (1.0 for standard descriptors; 1.1 if rli > 0 or chain flags > 0)
        let mut req_minor = 0u32;
        if rollback_index_location > 0 {
            req_minor = req_minor.max(1);
        }
        for d in &descriptors {
            if let AvbDescriptor::ChainPartition(c) = d {
                if c.flags != 0 {
                    req_minor = req_minor.max(2);
                }
            }
        }

        let mut hdr = vec![0u8; VBMETA_HEADER_SIZE];
        hdr[0..4].copy_from_slice(VBMETA_MAGIC);
        hdr[4..8].copy_from_slice(&1u32.to_be_bytes());
        hdr[8..12].copy_from_slice(&req_minor.to_be_bytes());
        hdr[12..20].copy_from_slice(&(auth_padded_len as u64).to_be_bytes());
        hdr[20..28].copy_from_slice(&(aux_padded_len as u64).to_be_bytes());
        hdr[28..32].copy_from_slice(&(algorithm as u32).to_be_bytes());
        hdr[32..40].copy_from_slice(&hash_off.to_be_bytes());
        hdr[40..48].copy_from_slice(&hash_sz.to_be_bytes());
        hdr[48..56].copy_from_slice(&sig_off.to_be_bytes());
        hdr[56..64].copy_from_slice(&sig_sz.to_be_bytes());
        hdr[64..72].copy_from_slice(&pk_off.to_be_bytes());
        hdr[72..80].copy_from_slice(&pk_sz.to_be_bytes());
        hdr[80..88].copy_from_slice(&pkm_off.to_be_bytes());
        hdr[88..96].copy_from_slice(&pkm_sz.to_be_bytes());
        hdr[96..104].copy_from_slice(&desc_off.to_be_bytes());
        hdr[104..112].copy_from_slice(&desc_sz.to_be_bytes());
        hdr[112..120].copy_from_slice(&rollback_index.to_be_bytes());
        hdr[120..124].copy_from_slice(&flags.to_be_bytes());
        hdr[124..128].copy_from_slice(&rollback_index_location.to_be_bytes());
        let rel = release_string.unwrap_or(RELEASE_STRING_DEFAULT).as_bytes();
        let copy_len = rel.len().min(47);
        hdr[128..128 + copy_len].copy_from_slice(&rel[..copy_len]);

        let mut auth = vec![0u8; auth_padded_len];
        if algorithm != AlgorithmType::None {
            let digest: Vec<u8> = match algorithm {
                AlgorithmType::Sha256Rsa2048 | AlgorithmType::Sha256Rsa4096 => {
                    let mut h = Sha256::new();
                    h.update(&hdr);
                    h.update(&aux);
                    h.finalize().to_vec()
                }
                AlgorithmType::Sha512Rsa2048 | AlgorithmType::Sha512Rsa4096 => {
                    let mut h = Sha512::new();
                    h.update(&hdr);
                    h.update(&aux);
                    h.finalize().to_vec()
                }
                AlgorithmType::None => unreachable!(),
            };
            let sig = key.unwrap().sign_digest(algorithm, &digest)?;
            auth[0..digest.len()].copy_from_slice(&digest);
            auth[digest.len()..digest.len() + sig.len()].copy_from_slice(&sig);
        }

        let mut blob = Vec::with_capacity(VBMETA_HEADER_SIZE + auth.len() + aux.len());
        blob.extend_from_slice(&hdr);
        blob.extend_from_slice(&auth);
        blob.extend_from_slice(&aux);
        Ok(blob)
    }

    pub fn verify_signature(&self, expected_pubkey: Option<&[u8]>) -> Result<()> {
        if let Some(exp) = expected_pubkey {
            if self.public_key.as_slice() != exp {
                bail!("VBMeta public key does not match expected key");
            }
        }
        if self.algorithm == AlgorithmType::None {
            return Ok(());
        }
        let computed: Vec<u8> = match self.algorithm {
            AlgorithmType::Sha256Rsa2048 | AlgorithmType::Sha256Rsa4096 => {
                let mut h = Sha256::new();
                h.update(&self.raw_header);
                h.update(&self.raw_aux);
                h.finalize().to_vec()
            }
            AlgorithmType::Sha512Rsa2048 | AlgorithmType::Sha512Rsa4096 => {
                let mut h = Sha512::new();
                h.update(&self.raw_header);
                h.update(&self.raw_aux);
                h.finalize().to_vec()
            }
            AlgorithmType::None => unreachable!(),
        };
        if computed != self.hash {
            bail!("VBMeta header/aux digest mismatch");
        }
        let pubkey = AvbPublicKey::from_avb_bytes(&self.public_key)?;
        pubkey.verify_digest(self.algorithm, &computed, &self.signature)
    }

    pub fn public_key_sha1(&self) -> Option<String> {
        if self.public_key.is_empty() {
            None
        } else {
            AvbPublicKey::from_avb_bytes(&self.public_key)
                .ok()
                .map(|pk| pk.sha1_hex())
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LoadedAvbImage {
    pub image_size: u64,
    pub footer: Option<AvbFooter>,
    pub vbmeta: VbMetaImage,
}

pub fn load_avb_image(path: &Path) -> Result<LoadedAvbImage> {
    let mut f = File::open(path).with_context(|| format!("Failed to open {}", path.display()))?;
    let image_size = f.metadata()?.len();
    if image_size < VBMETA_HEADER_SIZE as u64 {
        bail!("Image {} too small ({image_size} bytes)", path.display());
    }

    // Check footer first
    f.seek(SeekFrom::End(-(FOOTER_SIZE as i64)))?;
    let mut ftr_buf = [0u8; FOOTER_SIZE];
    f.read_exact(&mut ftr_buf)?;
    if &ftr_buf[0..4] == FOOTER_MAGIC {
        let footer = AvbFooter::parse(&ftr_buf)?;
        f.seek(SeekFrom::Start(footer.vbmeta_offset))?;
        let mut vb_buf = vec![0u8; footer.vbmeta_size as usize];
        f.read_exact(&mut vb_buf)?;
        let vbmeta = VbMetaImage::parse(&vb_buf)?;
        return Ok(LoadedAvbImage {
            image_size,
            footer: Some(footer),
            vbmeta,
        });
    }

    // Standalone vbmeta image
    f.seek(SeekFrom::Start(0))?;
    let mut header_buf = [0u8; VBMETA_HEADER_SIZE];
    f.read_exact(&mut header_buf)?;
    if &header_buf[0..4] != VBMETA_MAGIC {
        bail!("Image {} has neither AVB footer nor standalone VBMeta header", path.display());
    }
    let auth_sz = u64::from_be_bytes(header_buf[12..20].try_into().unwrap()) as usize;
    let aux_sz = u64::from_be_bytes(header_buf[20..28].try_into().unwrap()) as usize;
    let total = VBMETA_HEADER_SIZE + auth_sz + aux_sz;
    f.seek(SeekFrom::Start(0))?;
    let mut vb_buf = vec![0u8; total];
    f.read_exact(&mut vb_buf)?;
    let vbmeta = VbMetaImage::parse(&vb_buf)?;
    Ok(LoadedAvbImage {
        image_size,
        footer: None,
        vbmeta,
    })
}
