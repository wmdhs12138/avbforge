package io.github.fu.avbforge.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.fu.avbforge.data.KeyEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages AVB private keys encrypted at rest using Android Keystore (AES-256-GCM).
 * Plaintext PEM is only materialized to a 0600 temp file during an active signing call and zero-wiped immediately.
 */
class KeyVaultManager(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val vaultDir = File(context.filesDir, "key_vault").apply { mkdirs() }
    private val indexFile = File(vaultDir, "keys.json")
    private val keystoreAlias = "avbforge_vault_master_v1"

    fun listKeys(): List<KeyEntry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<KeyEntry>>(indexFile.readText())
        }.getOrDefault(emptyList())
    }

    fun getDefaultKey(): KeyEntry? =
        listKeys().firstOrNull { it.isDefault } ?: listKeys().firstOrNull()

    fun setDefaultKey(id: String) {
        val updated = listKeys().map { it.copy(isDefault = it.id == id) }
        saveIndex(updated)
    }

    fun deleteKey(id: String) {
        encPemFile(id).delete()
        pkmdFile(id).delete()
        val remaining = listKeys().filterNot { it.id == id }.toMutableList()
        if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
            remaining[0] = remaining[0].copy(isDefault = true)
        }
        saveIndex(remaining)
    }

    fun storeKey(alias: String, bits: Int, sha1: String, plaintextPem: ByteArray, pkmdBytes: ByteArray): KeyEntry {
        val id = UUID.randomUUID().toString().take(8)
        val encrypted = encryptBytes(plaintextPem)
        encPemFile(id).writeBytes(encrypted)
        pkmdFile(id).writeBytes(pkmdBytes)

        val current = listKeys().toMutableList()
        val makeDefault = current.isEmpty()
        val entry = KeyEntry(
            id = id,
            alias = alias,
            bits = bits,
            sha1 = sha1,
            createdUnix = System.currentTimeMillis() / 1000L,
            isDefault = makeDefault,
        )
        current.add(entry)
        saveIndex(current)
        return entry
    }

    fun pkmdFile(id: String): File = File(vaultDir, "$id.pkmd")

    private fun encPemFile(id: String): File = File(vaultDir, "$id.pem.enc")

    suspend fun <T> withTempPlaintextKey(id: String, block: suspend (pemFile: File, pkmdFile: File) -> T): T {
        val enc = encPemFile(id).readBytes()
        val plain = decryptBytes(enc)
        val tmpDir = File(context.cacheDir, "key_tmp").apply { mkdirs() }
        val tmpPem = File(tmpDir, "k_${System.nanoTime()}.pem")
        try {
            tmpPem.writeBytes(plain)
            tmpPem.setReadable(false, false)
            tmpPem.setReadable(true, true)
            tmpPem.setWritable(true, true)
            return block(tmpPem, pkmdFile(id))
        } finally {
            plain.fill(0)
            if (tmpPem.exists()) {
                runCatching { tmpPem.writeBytes(ByteArray(tmpPem.length().toInt())) }
                tmpPem.delete()
            }
        }
    }

    private fun saveIndex(entries: List<KeyEntry>) {
        indexFile.writeText(json.encodeToString(entries))
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keystoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun decryptBytes(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}
