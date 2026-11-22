package io.github.fu.avbforge.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fu.avbforge.core.CoreRunner
import io.github.fu.avbforge.core.KeyVaultManager
import io.github.fu.avbforge.core.SafetyAssessment
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.data.BackupManifest
import io.github.fu.avbforge.data.CoreProgress
import io.github.fu.avbforge.data.DeviceSnapshot
import io.github.fu.avbforge.data.ForgeBundleReport
import io.github.fu.avbforge.data.GsiSignReport
import io.github.fu.avbforge.data.KeyEntry
import io.github.fu.avbforge.data.PatchDsuKeyReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class UiState(
    val busy: Boolean = false,
    val statusMessage: String = "就绪",
    val progress: CoreProgress? = null,
    val logs: List<String> = emptyList(),
    val snapshot: DeviceSnapshot? = null,
    val keys: List<KeyEntry> = emptyList(),
    val lastBackup: BackupManifest? = null,
    val lastBackupDir: String? = null,
    val exportedInitBootPath: String? = null,
    val lastForgeReport: ForgeBundleReport? = null,
    val lastDsuPatchReport: PatchDsuKeyReport? = null,
    val lastGsiReport: GsiSignReport? = null,
    val dsuStatusText: String = "unknown",
    val errorBanner: String? = null,
)

class AvbForgeViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = CoreRunner(app)
    val vault = KeyVaultManager(app)

    private val _state = MutableStateFlow(UiState(keys = vault.listKeys()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val baseWorkDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AVBForge"
        ).apply { mkdirs() }

    init {
        refreshDevice()
    }

    private fun appendLog(level: String, msg: String) {
        _state.update { s ->
            val next = (s.logs + "[${level.uppercase()}] $msg").takeLast(200)
            s.copy(logs = next)
        }
    }

    fun clearError() {
        _state.update { it.copy(errorBanner = null) }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun refreshDevice() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在体检本机 AVB 与 DSU 状态...", errorBanner = null) }
            val res = runner.run(
                args = listOf("inspect-device", "--package", getApplication<Application>().packageName),
                requireRoot = true,
                onLog = ::appendLog,
            )
            res.onSuccess { elem ->
                val snap = runner.json.decodeFromJsonElement<DeviceSnapshot>(elem)
                _state.update {
                    it.copy(
                        busy = false,
                        snapshot = snap,
                        dsuStatusText = snap.dsuStatus,
                        keys = vault.listKeys(),
                        statusMessage = "体检完成 (${snap.model} / Android ${snap.androidRelease} / ${snap.activeSlot})",
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busy = false,
                        errorBanner = "体检失败：${err.message}",
                        statusMessage = "请确认已授予本应用 Root（含临时 Root）权限",
                    )
                }
            }
        }
    }

    fun backupPartitions(slot: String? = null, partitions: List<String> = emptyList()) {
        viewModelScope.launch {
            val slotTag = slot?.trimStart('_')?.ifBlank { null } ?: _state.value.snapshot?.activeSlot?.trimStart('_') ?: "cur"
            val outDir = File(baseWorkDir, "backup_${slotTag}_${System.currentTimeMillis() / 1000}")
            val desc = if (partitions.isEmpty()) "全套 AVB 链分区" else "${partitions.size} 个选中分区"
            _state.update { it.copy(busy = true, statusMessage = "正在备份槽位 _$slotTag 的$desc...", errorBanner = null) }
            val args = mutableListOf("backup-partitions", "--out-dir", outDir.absolutePath)
            if (!slot.isNullOrBlank()) {
                args += listOf("--slot", slot)
            }
            if (partitions.isNotEmpty()) {
                args += listOf("--partitions", partitions.joinToString(","))
            }
            val res = runner.run(
                args = args,
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
            )
            res.onSuccess { elem ->
                val manifest = runner.json.decodeFromJsonElement<BackupManifest>(elem)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        lastBackup = manifest,
                        lastBackupDir = outDir.absolutePath,
                        statusMessage = "备份完成：${manifest.partitions.size} 个分区已保存到 ${outDir.absolutePath}",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "备份失败：${err.message}") }
            }
        }
    }

    fun generateKey(alias: String, bits: Int) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在生成 RSA-$bits AVB 密钥对...", errorBanner = null) }
            val tmpDir = File(getApplication<Application>().cacheDir, "keygen_${System.nanoTime()}").apply { mkdirs() }
            val tmpPem = File(tmpDir, "avb.pem")
            val tmpPkmd = File(tmpDir, "avb_pkmd.bin")
            try {
                val res = runner.run(
                    args = listOf(
                        "key", "gen",
                        "--bits", bits.toString(),
                        "--out-pem", tmpPem.absolutePath,
                        "--out-pkmd", tmpPkmd.absolutePath,
                    ),
                    requireRoot = false,
                    onLog = ::appendLog,
                )
                res.onSuccess { elem ->
                    val sha1 = elem.jsonObject["sha1"]?.jsonPrimitive?.content ?: ""
                    val entry = vault.storeKey(
                        alias = alias.ifBlank { "AVB-$bits" },
                        bits = bits,
                        sha1 = sha1,
                        plaintextPem = tmpPem.readBytes(),
                        pkmdBytes = tmpPkmd.readBytes(),
                    )
                    _state.update {
                        it.copy(
                            busy = false,
                            keys = vault.listKeys(),
                            statusMessage = "密钥「${entry.alias}」生成成功，已加密存入 Keystore（SHA1: ${sha1.take(12)}...）",
                        )
                    }
                }.onFailure { err ->
                    _state.update { it.copy(busy = false, errorBanner = "生成密钥失败：${err.message}") }
                }
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    }

    fun importStandardPem(alias: String, pemPath: String, passphrase: String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在校验并导入标准 PEM 密钥...", errorBanner = null) }
            val tmpPkmd = File(getApplication<Application>().cacheDir, "imp_${System.nanoTime()}.bin")
            try {
                val args = mutableListOf("key", "export-pkmd", "--key", pemPath, "--output", tmpPkmd.absolutePath)
                if (!passphrase.isNullOrEmpty()) {
                    args += listOf("--passphrase", passphrase)
                }
                val res = runner.run(args = args, requireRoot = false, onLog = ::appendLog)
                res.onSuccess { elem ->
                    val sha1 = elem.jsonObject["sha1"]?.jsonPrimitive?.content ?: ""
                    val bits = elem.jsonObject["bits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2048
                    val entry = vault.storeKey(
                        alias = alias.ifBlank { File(pemPath).nameWithoutExtension },
                        bits = bits,
                        sha1 = sha1,
                        plaintextPem = File(pemPath).readBytes(),
                        pkmdBytes = tmpPkmd.readBytes(),
                    )
                    _state.update {
                        it.copy(
                            busy = false,
                            keys = vault.listKeys(),
                            statusMessage = "导入成功：${entry.alias}（RSA-$bits, SHA1: ${sha1.take(12)}...）",
                        )
                    }
                }.onFailure { err ->
                    _state.update { it.copy(busy = false, errorBanner = "导入失败：${err.message}") }
                }
            } finally {
                tmpPkmd.delete()
            }
        }
    }

    fun exportPkmdToDownloads(key: KeyEntry) {
        runCatching {
            val dst = File(baseWorkDir, "${key.alias}_avb_pkmd.bin")
            vault.pkmdFile(key.id).copyTo(dst, overwrite = true)
            _state.update { it.copy(statusMessage = "已导出公钥到 ${dst.absolutePath}") }
        }.onFailure { err ->
            _state.update { it.copy(errorBanner = "导出失败：${err.message}") }
        }
    }

    fun setDefaultKey(id: String) {
        vault.setDefaultKey(id)
        _state.update { it.copy(keys = vault.listKeys()) }
    }

    fun deleteKey(id: String) {
        vault.deleteKey(id)
        _state.update { it.copy(keys = vault.listKeys()) }
    }

    fun exportInitBootForKsuManager() {
        viewModelScope.launch {
            val dst = File(baseWorkDir, "stock_init_boot_for_ksu.img")
            _state.update { it.copy(busy = true, statusMessage = "正在导出当前槽位 init_boot.img...", errorBanner = null) }
            val res = runner.run(
                args = listOf("export-init-boot", "--output", dst.absolutePath),
                requireRoot = true,
                onLog = ::appendLog,
            )
            res.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        exportedInitBootPath = dst.absolutePath,
                        statusMessage = "已导出 ${dst.absolutePath}，请打开你喜欢的 KernelSU 管理器修补后再选回修补产物",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "导出 init_boot 失败：${err.message}") }
            }
        }
    }

    fun computeSafety(includeKsuRoot: Boolean): SafetyAssessment? {
        val snap = _state.value.snapshot ?: return null
        val defKey = vault.getDefaultKey()
        return SafetyGate.assessFlash(
            snapshot = snap,
            selectedKeySha1 = defKey?.sha1,
            includeKsuRoot = includeKsuRoot,
            hasStockBackup = _state.value.lastBackup != null,
        )
    }

    fun forgeSelfSignedBundle(
        stockDirPath: String,
        patchedInitBootPath: String?,
        injectDsuKey: Boolean,
        dsuKeyName: String,
    ) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在「密钥」页生成或导入默认 AVB 密钥") }
            return
        }
        viewModelScope.launch {
            val outDir = File(baseWorkDir, "forged_${System.currentTimeMillis() / 1000}")
            _state.update { it.copy(busy = true, statusMessage = "正在构建并全链验签自签名镜像包...", errorBanner = null) }
            runCatching {
                vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                    val args = mutableListOf(
                        "forge-bundle",
                        "--stock-dir", stockDirPath,
                        "--out-dir", outDir.absolutePath,
                        "--key", pemFile.absolutePath,
                        "--inject-dsu-key", injectDsuKey.toString(),
                        "--dsu-key-name", dsuKeyName.ifBlank { "q-gsi.avbpubkey" },
                    )
                    if (!patchedInitBootPath.isNullOrBlank()) {
                        args += listOf("--patched-init-boot", patchedInitBootPath)
                    }
                    runner.run(args = args, requireRoot = false, onLog = ::appendLog)
                }
            }.onSuccess { result ->
                result.onSuccess { elem ->
                    val rep = runner.json.decodeFromJsonElement<ForgeBundleReport>(elem)
                    _state.update {
                        it.copy(
                            busy = false,
                            lastForgeReport = rep,
                            statusMessage = "构建并通过全链验签（${rep.chainVerifiedCount} 个镜像）：${rep.outputDir}",
                        )
                    }
                }.onFailure { err ->
                    _state.update { it.copy(busy = false, errorBanner = "构建/验签失败：${err.message}") }
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "读取密钥失败：${err.message}") }
            }
        }
    }

    fun flashForgedBundle(bundleDir: String, targetSlot: String?) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "未找到默认密钥") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在写入自签名包并清缓存回读核验...", errorBanner = null) }
            val args = mutableListOf(
                "flash-bundle",
                "--bundle-dir", bundleDir,
                "--expected-pubkey", vault.pkmdFile(key.id).absolutePath,
            )
            if (!targetSlot.isNullOrBlank()) {
                args += listOf("--slot", targetSlot)
            }
            val res = runner.run(
                args = args,
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
            )
            res.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        statusMessage = "全套镜像已安全写入并通过回读 SHA-256 与 AVB 验签！可手动重启。",
                    )
                }
                refreshDevice()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "刷写中断：${err.message}") }
            }
        }
    }

    fun signGsiImage(inputPath: String) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在「密钥」页生成或导入默认 AVB 密钥") }
            return
        }
        viewModelScope.launch {
            val outFile = File(baseWorkDir, "signed_gsi_${System.currentTimeMillis() / 1000}.img")
            _state.update { it.copy(busy = true, statusMessage = "正在解压/计算 Merkle Hashtree 并签名 GSI...", errorBanner = null) }
            runCatching {
                vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                    runner.run(
                        args = listOf(
                            "gsi", "sign",
                            "--input", inputPath,
                            "--output", outFile.absolutePath,
                            "--key", pemFile.absolutePath,
                        ),
                        requireRoot = false,
                        timeoutSeconds = 1800,
                        onLog = ::appendLog,
                        onProgress = { p -> _state.update { it.copy(progress = p) } },
                    )
                }
            }.onSuccess { result ->
                result.onSuccess { elem ->
                    val rep = runner.json.decodeFromJsonElement<GsiSignReport>(elem)
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = null,
                            lastGsiReport = rep,
                            statusMessage = "GSI 签名并验签通过：${rep.outputPath}",
                        )
                    }
                }.onFailure { err ->
                    _state.update { it.copy(busy = false, progress = null, errorBanner = "GSI 签名失败：${err.message}") }
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "解密私钥失败：${err.message}") }
            }
        }
    }

    fun installSignedDsu(signedPath: String, userdataGiB: Int, singleBoot: Boolean) {
        val key = vault.getDefaultKey()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在通过 gsi_tool 写入 DSU...", errorBanner = null) }
            val userdataBytes = userdataGiB.toLong() * 1024L * 1024L * 1024L
            val args = mutableListOf(
                "dsu", "install",
                "--image", signedPath,
                "--userdata-size", userdataBytes.toString(),
                "--single-boot", singleBoot.toString(),
            )
            if (key != null) {
                args += listOf("--expected-pubkey", vault.pkmdFile(key.id).absolutePath)
            }
            val res = runner.run(args = args, requireRoot = true, timeoutSeconds = 1800, onLog = ::appendLog)
            res.onSuccess { elem ->
                val st = elem.jsonObject["status"]?.jsonPrimitive?.content ?: "installed"
                _state.update {
                    it.copy(
                        busy = false,
                        dsuStatusText = st,
                        statusMessage = "DSU 安装就绪（单次启动=${singleBoot}），现在重启即可进入 GSI！",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "DSU 安装失败：${err.message}") }
            }
        }
    }

    fun runDsuSimple(action: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "执行 gsi_tool $action...", errorBanner = null) }
            val res = runner.run(args = listOf("dsu", action), requireRoot = true, onLog = ::appendLog)
            res.onSuccess {
                _state.update { it.copy(busy = false, statusMessage = "DSU $action 完成") }
                refreshDevice()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "DSU $action 失败：${err.message}") }
            }
        }
    }

    /**
     * One-click first-time DSU preparation:
     * 1. Backup active slot live init_boot (preserving KSU)
     * 2. Inject user's avb_pkmd.bin into ramdisk & re-sign with default key + verify
     */
    fun prepareDsuInitBoot(targetKeyName: String) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在「密钥」页导入或生成与本机一致的默认 AVB 密钥") }
            return
        }
        viewModelScope.launch {
            val ts = System.currentTimeMillis() / 1000
            val bkpFile = File(baseWorkDir, "init_boot_before_dsu_$ts.img")
            val outFile = File(baseWorkDir, "init_boot_dsu_ready_$ts.img")
            _state.update { it.copy(busy = true, statusMessage = "正在备份当前 init_boot 并注入 DSU 公钥...", errorBanner = null) }

            val expRes = runner.run(
                args = listOf("export-init-boot", "--output", bkpFile.absolutePath),
                requireRoot = true,
                onLog = ::appendLog,
            )
            if (expRes.isFailure) {
                _state.update { it.copy(busy = false, errorBanner = "备份当前 init_boot 失败：${expRes.exceptionOrNull()?.message}") }
                return@launch
            }

            runCatching {
                vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                    runner.run(
                        args = listOf(
                            "patch-dsu-key",
                            "--input", bkpFile.absolutePath,
                            "--output", outFile.absolutePath,
                            "--key", pemFile.absolutePath,
                            "--partition-name", "init_boot",
                            "--target-key-name", targetKeyName.ifBlank { "q-gsi.avbpubkey" },
                        ),
                        requireRoot = false,
                        onLog = ::appendLog,
                    )
                }
            }.onSuccess { res ->
                res.onSuccess { elem ->
                    val rep = runner.json.decodeFromJsonElement<PatchDsuKeyReport>(elem)
                    _state.update {
                        it.copy(
                            busy = false,
                            lastDsuPatchReport = rep,
                            statusMessage = "已生成并验签 DSU init_boot（KSU保留=${rep.hasKernelsu}），确认后可写入当前槽位",
                        )
                    }
                }.onFailure { err ->
                    _state.update { it.copy(busy = false, errorBanner = "注入 DSU 公钥失败：${err.message}") }
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "读取密钥失败：${err.message}") }
            }
        }
    }

    fun flashPreparedDsuInitBoot(imagePath: String) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "未找到默认密钥") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在写入 DSU init_boot 并回读校验...", errorBanner = null) }
            val res = runner.run(
                args = listOf(
                    "flash-partition",
                    "--partition", "init_boot",
                    "--image", imagePath,
                    "--expected-pubkey", vault.pkmdFile(key.id).absolutePath,
                ),
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
            )
            res.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        statusMessage = "DSU init_boot 写入并回读验签通过！重启一次手机后即可使用 DSU。",
                    )
                }
                refreshDevice()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "写入 init_boot 失败：${err.message}") }
            }
        }
    }
}
