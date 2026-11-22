package io.github.fu.avbforge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PartitionSizeInfo(
    val name: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("in_vbmeta_chain") val inVbmetaChain: Boolean = true,
)

@Serializable
data class DeviceSnapshot(
    val model: String = "",
    val device: String = "",
    val manufacturer: String = "",
    @SerialName("android_release") val androidRelease: String = "",
    @SerialName("sdk_int") val sdkInt: Int = 0,
    @SerialName("vndk_version") val vndkVersion: String = "",
    @SerialName("kernel_release") val kernelRelease: String = "",
    @SerialName("active_slot") val activeSlot: String = "",
    @SerialName("verified_boot_state") val verifiedBootState: String = "",
    @SerialName("device_state") val deviceState: String = "",
    @SerialName("sdk_compatibility") val sdkCompatibility: String = "unknown",
    @SerialName("ksu_version") val ksuVersion: String? = null,
    @SerialName("init_boot_has_ksu") val initBootHasKsu: Boolean? = null,
    @SerialName("init_boot_avbpubkeys") val initBootAvbpubkeys: List<String> = emptyList(),
    @SerialName("vbmeta_pubkey_sha1") val vbmetaPubkeySha1: String? = null,
    @SerialName("vbmeta_chained_partitions") val vbmetaChainedPartitions: List<String> = emptyList(),
    @SerialName("fstab_avb_keys") val fstabAvbKeys: List<String> = emptyList(),
    @SerialName("dsu_status") val dsuStatus: String = "unknown",
    @SerialName("freezer_enabled") val freezerEnabled: Boolean = false,
    @SerialName("app_in_doze_whitelist") val appInDozeWhitelist: Boolean = false,
    @SerialName("partition_sizes") val partitionSizes: List<PartitionSizeInfo> = emptyList(),
)

@Serializable
data class KeyEntry(
    val id: String,
    val alias: String,
    val bits: Int,
    val sha1: String,
    val createdUnix: Long,
    val isDefault: Boolean = false,
)

@Serializable
data class PartitionBackupEntry(
    val partition: String,
    val slot: String,
    val filename: String,
    val size: Long,
    val sha256: String,
    @SerialName("avb_pubkey_sha1") val avbPubkeySha1: String? = null,
)

@Serializable
data class BackupManifest(
    val model: String,
    val slot: String,
    @SerialName("android_release") val androidRelease: String,
    @SerialName("sdk_int") val sdkInt: Int,
    @SerialName("created_unix") val createdUnix: Long,
    val partitions: List<PartitionBackupEntry>,
)

@Serializable
data class ForgeBundleReport(
    @SerialName("output_dir") val outputDir: String,
    @SerialName("signed_partitions") val signedPartitions: List<String>,
    @SerialName("pubkey_sha1") val pubkeySha1: String,
    @SerialName("dsu_key_injected") val dsuKeyInjected: Boolean,
    @SerialName("init_boot_has_ksu") val initBootHasKsu: Boolean,
    @SerialName("chain_verified_count") val chainVerifiedCount: Int,
)

@Serializable
data class GsiSignReport(
    @SerialName("output_path") val outputPath: String,
    @SerialName("original_size") val originalSize: Long,
    @SerialName("final_size") val finalSize: Long,
    @SerialName("tree_size") val treeSize: Long,
    @SerialName("root_digest") val rootDigest: String,
    @SerialName("pubkey_sha1") val pubkeySha1: String,
)

@Serializable
data class FlashReport(
    val partition: String,
    val slot: String,
    @SerialName("device_path") val devicePath: String,
    val size: Long,
    val sha256: String,
    @SerialName("avb_verified") val avbVerified: Boolean,
)

data class CoreProgress(
    val stage: String,
    val current: Long,
    val total: Long,
)
