use anyhow::{anyhow, bail, Context, Result};
use pkcs8::{DecodePrivateKey, EncodePrivateKey, LineEnding};
use rand::rngs::OsRng;
use rsa::traits::PublicKeyParts;
use rsa::{BigUint, Pkcs1v15Sign, RsaPrivateKey, RsaPublicKey};
use sha1::Sha1;
use sha2::{Digest, Sha256, Sha512};
use std::fs;
use std::path::Path;

use crate::format::AlgorithmType;

#[derive(Clone)]
pub struct AvbPrivateKey {
    inner: RsaPrivateKey,
}

#[derive(Clone, Debug)]
pub struct AvbPublicKey {
    pub key_num_bits: u32,
    pub raw_avb_key: Vec<u8>,
    rsa_pub: RsaPublicKey,
}

impl AvbPrivateKey {
    pub fn generate(bits: usize) -> Result<Self> {
        if bits != 2048 && bits != 4096 {
            bail!("Unsupported RSA key size: {bits} (must be 2048 or 4096)");
        }
        let inner = RsaPrivateKey::new(&mut OsRng, bits)
            .with_context(|| format!("Failed to generate RSA-{bits} key"))?;
        Ok(Self { inner })
    }

    pub fn from_pem_str(pem: &str, passphrase: Option<&str>) -> Result<Self> {
        let trimmed = pem.trim();
        let inner = if trimmed.contains("ENCRYPTED PRIVATE KEY") {
            let pwd = passphrase.ok_or_else(|| anyhow!("Passphrase required for encrypted PKCS#8 PEM"))?;
            RsaPrivateKey::from_pkcs8_encrypted_pem(trimmed, pwd)
                .context("Failed to decrypt encrypted PKCS#8 PEM private key")?
        } else if trimmed.contains("RSA PRIVATE KEY") {
            use rsa::pkcs1::DecodeRsaPrivateKey;
            RsaPrivateKey::from_pkcs1_pem(trimmed)
                .context("Failed to parse PKCS#1 RSA PRIVATE KEY PEM")?
        } else {
            RsaPrivateKey::from_pkcs8_pem(trimmed)
                .context("Failed to parse PKCS#8 PRIVATE KEY PEM")?
        };
        Ok(Self { inner })
    }

    pub fn from_pem_file(path: &Path, passphrase: Option<&str>) -> Result<Self> {
        let pem = fs::read_to_string(path)
            .with_context(|| format!("Failed to read private key file {}", path.display()))?;
        Self::from_pem_str(&pem, passphrase)
    }

    pub fn to_pkcs8_pem(&self, passphrase: Option<&str>) -> Result<String> {
        let doc = if let Some(pwd) = passphrase {
            self.inner
                .to_pkcs8_encrypted_pem(&mut OsRng, pwd, LineEnding::LF)
                .context("Failed to encode encrypted PKCS#8 PEM")?
        } else {
            self.inner
                .to_pkcs8_pem(LineEnding::LF)
                .context("Failed to encode PKCS#8 PEM")?
        };
        Ok(doc.to_string())
    }

    pub fn key_bits(&self) -> usize {
        self.inner.size() * 8
    }

    pub fn public_key(&self) -> Result<AvbPublicKey> {
        AvbPublicKey::from_rsa(RsaPublicKey::from(&self.inner))
    }

    pub fn default_algorithm(&self) -> Result<AlgorithmType> {
        match self.key_bits() {
            2048 => Ok(AlgorithmType::Sha256Rsa2048),
            4096 => Ok(AlgorithmType::Sha256Rsa4096),
            b => bail!("Unsupported key bit length for AVB: {b}"),
        }
    }

    pub fn sign_digest(&self, alg: AlgorithmType, digest: &[u8]) -> Result<Vec<u8>> {
        let expected_bits = alg.key_bits();
        if self.key_bits() != expected_bits {
            bail!(
                "Key size ({} bits) does not match algorithm {:?} ({} bits)",
                self.key_bits(),
                alg,
                expected_bits
            );
        }
        let sig = match alg {
            AlgorithmType::Sha256Rsa2048 | AlgorithmType::Sha256Rsa4096 => {
                self.inner.sign(Pkcs1v15Sign::new::<Sha256>(), digest)
            }
            AlgorithmType::Sha512Rsa2048 | AlgorithmType::Sha512Rsa4096 => {
                self.inner.sign(Pkcs1v15Sign::new::<Sha512>(), digest)
            }
            AlgorithmType::None => bail!("Cannot sign with AlgorithmType::None"),
        }
        .context("RSA PKCS#1 v1.5 signing failed")?;
        Ok(sig)
    }
}

impl AvbPublicKey {
    pub fn from_rsa(rsa_pub: RsaPublicKey) -> Result<Self> {
        let key_num_bits = (rsa_pub.size() * 8) as u32;
        let mod_len = (key_num_bits / 8) as usize;
        let n = rsa_pub.n();

        let n_bytes = biguint_to_fixed_be(n, mod_len)?;
        let n0 = u32::from_be_bytes([
            n_bytes[mod_len - 4],
            n_bytes[mod_len - 3],
            n_bytes[mod_len - 2],
            n_bytes[mod_len - 1],
        ]);
        if n0 % 2 == 0 {
            bail!("Invalid RSA modulus (even)");
        }
        // Modular inverse of n0 mod 2^32 via Newton-Raphson
        let mut inv: u32 = 1;
        for _ in 0..5 {
            inv = inv.wrapping_mul(2u32.wrapping_sub(n0.wrapping_mul(inv)));
        }
        let n0inv = 0u32.wrapping_sub(inv);

        // rr = (2^key_num_bits)^2 mod n
        let rr_big = (BigUint::from(1u32) << (2 * key_num_bits as usize)) % n;
        let rr_bytes = biguint_to_fixed_be(&rr_big, mod_len)?;

        let mut raw = Vec::with_capacity(8 + 2 * mod_len);
        raw.extend_from_slice(&key_num_bits.to_be_bytes());
        raw.extend_from_slice(&n0inv.to_be_bytes());
        raw.extend_from_slice(&n_bytes);
        raw.extend_from_slice(&rr_bytes);

        Ok(Self {
            key_num_bits,
            raw_avb_key: raw,
            rsa_pub,
        })
    }

    pub fn from_avb_bytes(raw: &[u8]) -> Result<Self> {
        if raw.len() < 8 {
            bail!("AVB public key too short ({} bytes)", raw.len());
        }
        let key_num_bits = u32::from_be_bytes(raw[0..4].try_into().unwrap());
        let mod_len = (key_num_bits / 8) as usize;
        let expected_len = 8 + 2 * mod_len;
        if raw.len() != expected_len {
            bail!(
                "AVB public key length mismatch: got {} bytes, expected {} for {}-bit key",
                raw.len(),
                expected_len,
                key_num_bits
            );
        }
        let n = BigUint::from_bytes_be(&raw[8..8 + mod_len]);
        let e = BigUint::from(65537u32);
        let rsa_pub = RsaPublicKey::new(n, e).context("Invalid RSA public key modulus")?;
        Ok(Self {
            key_num_bits,
            raw_avb_key: raw.to_vec(),
            rsa_pub,
        })
    }

    pub fn sha1_hex(&self) -> String {
        let digest = Sha1::digest(&self.raw_avb_key);
        hex::encode(digest)
    }

    pub fn verify_digest(&self, alg: AlgorithmType, digest: &[u8], signature: &[u8]) -> Result<()> {
        let res = match alg {
            AlgorithmType::Sha256Rsa2048 | AlgorithmType::Sha256Rsa4096 => {
                self.rsa_pub.verify(Pkcs1v15Sign::new::<Sha256>(), digest, signature)
            }
            AlgorithmType::Sha512Rsa2048 | AlgorithmType::Sha512Rsa4096 => {
                self.rsa_pub.verify(Pkcs1v15Sign::new::<Sha512>(), digest, signature)
            }
            AlgorithmType::None => bail!("Cannot verify signature with AlgorithmType::None"),
        };
        res.map_err(|e| anyhow!("AVB RSA signature verification failed: {e}"))
    }
}

fn biguint_to_fixed_be(val: &BigUint, len: usize) -> Result<Vec<u8>> {
    let bytes = val.to_bytes_be();
    if bytes.len() > len {
        bail!("BigUint exceeds fixed size {} bytes (got {})", len, bytes.len());
    }
    let mut out = vec![0u8; len];
    out[len - bytes.len()..].copy_from_slice(&bytes);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keygen_roundtrip_and_avb_pkmd() {
        let priv_key = AvbPrivateKey::generate(2048).unwrap();
        let pub_key = priv_key.public_key().unwrap();
        assert_eq!(pub_key.raw_avb_key.len(), 520);
        let parsed = AvbPublicKey::from_avb_bytes(&pub_key.raw_avb_key).unwrap();
        assert_eq!(parsed.sha1_hex(), pub_key.sha1_hex());

        let pem = priv_key.to_pkcs8_pem(Some("test-pass")).unwrap();
        let reloaded = AvbPrivateKey::from_pem_str(&pem, Some("test-pass")).unwrap();
        assert_eq!(reloaded.public_key().unwrap().raw_avb_key, pub_key.raw_avb_key);

        let digest = Sha256::digest(b"hello avbforge");
        let sig = priv_key.sign_digest(AlgorithmType::Sha256Rsa2048, &digest).unwrap();
        parsed.verify_digest(AlgorithmType::Sha256Rsa2048, &digest, &sig).unwrap();
    }
}
