package io.github.fu.avbforge.ui

import android.app.Application
import android.content.Context
import android.media.MediaDrm
import java.util.UUID
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fu.avbforge.core.CoreRunner
import io.github.fu.avbforge.core.KeyVaultManager
import io.github.fu.avbforge.core.SafetyAssessment
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.data.BackupManifest
import io.github.fu.avbforge.data.CommandBlock
import io.github.fu.avbforge.data.WhiteBoxConsoleState
import io.github.fu.avbforge.data.WhiteBoxEventReducer
import io.github.fu.avbforge.data.CoreProgress
import io.github.fu.avbforge.data.DeviceSnapshot
import io.github.fu.avbforge.data.DsuInfoReport
import io.github.fu.avbforge.data.ExportCleanInitBootReport
import io.github.fu.avbforge.data.ForgeBundleReport
import io.github.fu.avbforge.data.GsiSignReport
import io.github.fu.avbforge.data.KeyEntry
import io.github.fu.avbforge.data.LocalGsiImage
import io.github.fu.avbforge.data.LocalPartitionBackup
import io.github.fu.avbforge.data.TerminalCommandEntry
import io.github.fu.avbforge.data.TerminalLine
import io.github.fu.avbforge.data.PatchDsuKeyReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val cleanInitBootReport: ExportCleanInitBootReport? = null,
    val lastKsuUpdateReport: PatchDsuKeyReport? = null,
    val lastForgeReport: ForgeBundleReport? = null,
    val lastDsuPatchReport: PatchDsuKeyReport? = null,
    val lastGsiReport: GsiSignReport? = null,
    val dsuStatusText: String = "unknown",
    val dsuInfo: DsuInfoReport? = null,
    val localGsiImages: List<LocalGsiImage> = emptyList(),
    val localPartitionBackups: List<LocalPartitionBackup> = emptyList(),
    val lastInstalledDsuImageName: String? = null,
    val errorBanner: String? = null,
)

class AvbForgeViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = CoreRunner(app)
    val vault = KeyVaultManager(app)

    private val _state = MutableStateFlow(UiState(keys = vault.listKeys()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val prefs = app.getSharedPreferences("avbforge_prefs", Context.MODE_PRIVATE)

    private val _consoleState = MutableStateFlow(WhiteBoxConsoleState())
    val consoleState: StateFlow<WhiteBoxConsoleState> = _consoleState.asStateFlow()

    private val _terminalEntries = MutableStateFlow<List<TerminalCommandEntry>>(emptyList())
    val terminalEntries: StateFlow<List<TerminalCommandEntry>> = _terminalEntries.asStateFlow()

    private fun readWidevineLevel(): String = runCatching {
        val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        val drm = MediaDrm(widevineUuid)
        try {
            drm.getPropertyString("securityLevel").ifBlank { "Unknown" }
        } finally {
            drm.close()
        }
    }.getOrDefault("Unknown")

    private fun nowTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    fun clearTerminalEntries() {
        _terminalEntries.value = emptyList()
    }

    private suspend fun execCore(
        args: List<String>,
        requireRoot: Boolean = true,
        timeoutSeconds: Long = 600,
        onLog: (String, String) -> Unit = ::appendLog,
        onProgress: (CoreProgress) -> Unit = {},
        onRawLine: (String) -> Unit = {},
    ): Result<kotlinx.serialization.json.JsonElement> {
        val entryId = "cmd_${System.nanoTime()}"
        return runner.run(
            args = args,
            requireRoot = requireRoot,
            timeoutSeconds = timeoutSeconds,
            onLog = onLog,
            onProgress = onProgress,
            onRawLine = onRawLine,
            onCommandStart = { cmdStr ->
                val entry = TerminalCommandEntry(
                    id = entryId,
                    timestamp = nowTime(),
                    command = cmdStr,
                    running = true,
                )
                _terminalEntries.update { (it + entry).takeLast(60) }
            },
            onStreamLine = { stream, line ->
                _terminalEntries.update { list ->
                    list.map { e ->
                        if (e.id == entryId) {
                            e.copy(lines = (e.lines + TerminalLine(stream, line)).takeLast(160))
                        } else e
                    }
                }
            },
            onCommandFinish = { code, elapsed ->
                _terminalEntries.update { list ->
                    list.map { e ->
                        if (e.id == entryId) {
                            e.copy(exitCode = code, durationMs = elapsed, running = false)
                        } else e
                    }
                }
            },
        )
    }

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

    fun prepareWhiteBoxConsole(plannedSteps: List<Pair<String, String>> = emptyList()) {
        _consoleState.value = WhiteBoxConsoleState(
            blocks = plannedSteps.mapIndexed { idx, (title, cmd) ->
                CommandBlock(stepId = "planned_$idx", title = title, cmd = cmd)
            },
        )
    }

    fun clearWhiteBoxConsole() {
        _consoleState.value = WhiteBoxConsoleState()
    }

    private fun onCoreRawLine(rawLine: String) {
        _consoleState.update { WhiteBoxEventReducer.reduceRawLine(it, rawLine) }
    }

    fun setWhiteBoxRawJsonMode(showRaw: Boolean) {
        _consoleState.update { it.copy(showRawJson = showRaw) }
    }

    fun refreshDevice() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在体检本机 AVB 与 DSU 状态...", errorBanner = null) }
            val res = execCore(
                args = listOf("inspect-device", "--package", getApplication<Application>().packageName),
                requireRoot = true,
                onLog = ::appendLog,
            )
            res.onSuccess { elem ->
                val rawSnap = runner.json.decodeFromJsonElement<DeviceSnapshot>(elem)
                val widevine = if (rawSnap.widevineLevel.isBlank() || rawSnap.widevineLevel.equals("Unknown", ignoreCase = true)) {
                    readWidevineLevel()
                } else rawSnap.widevineLevel
                val snap = rawSnap.copy(widevineLevel = widevine)
                _state.update {
                    it.copy(
                        busy = false,
                        snapshot = snap,
                        dsuStatusText = snap.dsuStatus,
                        keys = vault.listKeys(),
                        localGsiImages = scanLocalGsiImages(),
                        localPartitionBackups = scanLocalPartitionBackups(),
                        lastInstalledDsuImageName = prefs.getString("last_dsu_image_name", null),
                        statusMessage = "体检完成 (${snap.model} / Android ${snap.androidRelease} / ${snap.activeSlot})",
                    )
                }
                refreshDsuInfo(silent = true)
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
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = "正在备份槽位 _$slotTag 的$desc...", errorBanner = null) }
            val args = mutableListOf("backup-partitions", "--out-dir", outDir.absolutePath)
            if (!slot.isNullOrBlank()) {
                args += listOf("--slot", slot)
            }
            if (partitions.isNotEmpty()) {
                args += listOf("--partitions", partitions.joinToString(","))
            }
            val res = execCore(
                args = args,
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
                onRawLine = ::onCoreRawLine,
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
                val res = execCore(
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
                val res = execCore(args = args, requireRoot = false, onLog = ::appendLog)
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
                val pickedDir = File(getApplication<Application>().cacheDir, "picked")
                val pemFile = File(pemPath)
                if (pemFile.parentFile == pickedDir) {
                    runCatching { pemFile.delete() }
                }
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
            val res = execCore(
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
            prepareWhiteBoxConsole()
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
                    execCore(
                        args = args,
                        requireRoot = false,
                        onLog = ::appendLog,
                        onRawLine = ::onCoreRawLine,
                    )
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
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = "正在写入自签名包并清缓存回读核验...", errorBanner = null) }
            val args = mutableListOf(
                "flash-bundle",
                "--bundle-dir", bundleDir,
                "--expected-pubkey", vault.pkmdFile(key.id).absolutePath,
            )
            if (!targetSlot.isNullOrBlank()) {
                args += listOf("--slot", targetSlot)
            }
            val res = execCore(
                args = args,
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
                onRawLine = ::onCoreRawLine,
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

    fun clearLastGsiReport() {
        _state.update { it.copy(lastGsiReport = null) }
    }

    /**
     * Fast path for an already self-signed GSI `.img`: verify its AVB signature & Merkle hashtree
     * against the default key without re-compressing or re-signing.
     */
    fun verifyExistingSignedGsi(imagePath: String) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在「工具 → 密钥库」导入与本机一致的默认 AVB 密钥") }
            return
        }
        viewModelScope.launch {
            prepareWhiteBoxConsole(
                listOf("离线验签已有 GSI" to "avbforge-core avb verify --image ${File(imagePath).name}")
            )
            _state.update {
                it.copy(
                    busy = true,
                    statusMessage = "正在校验已签名 GSI 的 AVB 签名与 Hashtree...",
                    errorBanner = null,
                )
            }
            val res = execCore(
                args = listOf(
                    "avb", "verify",
                    "--image", imagePath,
                    "--expected-pubkey", vault.pkmdFile(key.id).absolutePath,
                ),
                requireRoot = false,
                timeoutSeconds = 600,
                onLog = ::appendLog,
                onRawLine = ::onCoreRawLine,
            )
            res.onSuccess {
                val sz = runCatching { File(imagePath).length() }.getOrDefault(0L)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        lastGsiReport = GsiSignReport(
                            outputPath = imagePath,
                            originalSize = sz,
                            finalSize = sz,
                            treeSize = 0L,
                            rootDigest = "verified-existing-image",
                            pubkeySha1 = key.sha1,
                        ),
                        statusMessage = "验签通过！已跳过重复签名，可直接进入下一步安装 DSU。",
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        errorBanner = "该镜像未通过当前默认密钥验签（可能尚未自签名或使用了其他密钥），请点击「开始计算 Hashtree 并签名验签」重新签名。详情：${err.message}",
                    )
                }
            }
        }
    }

    fun signGsiImage(inputPath: String, fixVendorGenfs: Boolean = true) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在「密钥」页生成或导入默认 AVB 密钥") }
            return
        }
        viewModelScope.launch {
            val outFile = File(baseWorkDir, "signed_gsi_${System.currentTimeMillis() / 1000}.img")
            val msg = if (fixVendorGenfs) {
                "正在解压、检测/修复 SELinux 冲突并计算 Hashtree 签名 GSI..."
            } else {
                "正在解压/计算 Merkle Hashtree 并签名 GSI..."
            }
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = msg, errorBanner = null) }
            runCatching {
                vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                    execCore(
                        args = listOf(
                            "gsi", "sign",
                            "--input", inputPath,
                            "--output", outFile.absolutePath,
                            "--key", pemFile.absolutePath,
                            "--fix-vendor-genfs", fixVendorGenfs.toString(),
                        ),
                        requireRoot = fixVendorGenfs,
                        timeoutSeconds = 1800,
                        onLog = ::appendLog,
                        onProgress = { p -> _state.update { it.copy(progress = p) } },
                        onRawLine = ::onCoreRawLine,
                    )
                }
            }.onSuccess { result ->
                result.onSuccess { elem ->
                    val rep = runner.json.decodeFromJsonElement<GsiSignReport>(elem)
                    val doneMsg = if (rep.genfsConflictsPatched > 0) {
                        "已自动修复 ${rep.genfsConflictsPatched} 处 SELinux 冲突并完成签名验签：${rep.outputPath}"
                    } else {
                        "GSI 签名并验签通过：${rep.outputPath}"
                    }
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = null,
                            lastGsiReport = rep,
                            localGsiImages = scanLocalGsiImages(),
                            statusMessage = doneMsg,
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
            prepareWhiteBoxConsole()
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
            val res = execCore(
                args = args,
                requireRoot = true,
                timeoutSeconds = 1800,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
                onRawLine = ::onCoreRawLine,
            )
            res.onSuccess { elem ->
                val st = elem.jsonObject["status"]?.jsonPrimitive?.content ?: "installed"
                val imgName = File(signedPath).name
                prefs.edit().putString("last_dsu_image_name", imgName).apply()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        dsuStatusText = st,
                        lastInstalledDsuImageName = imgName,
                        statusMessage = "DSU 安装就绪（${imgName}），现在重启即可进入！",
                    )
                }
                refreshDsuInfo(silent = true)
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "DSU 安装失败：${err.message}") }
            }
        }
    }

    private fun scanLocalGsiImages(): List<LocalGsiImage> {
        val dir = baseWorkDir
        val minGsiBytes = 100L * 1024L * 1024L // Filter out 8MB init_boot / small boot images
        val files = dir.listFiles().orEmpty()
        return files
            .filter { f ->
                f.isFile &&
                    !f.name.startsWith(".") &&
                    f.length() >= minGsiBytes &&
                    (f.name.endsWith(".img", ignoreCase = true) ||
                        f.name.endsWith(".img.xz", ignoreCase = true) ||
                        f.name.endsWith(".img.gz", ignoreCase = true) ||
                        f.name.endsWith(".xz", ignoreCase = true) ||
                        f.name.endsWith(".gz", ignoreCase = true))
            }
            .sortedByDescending { it.lastModified() }
            .map { f ->
                LocalGsiImage(
                    name = f.name,
                    path = f.absolutePath,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                )
            }
    }

    fun refreshLocalGsiImages() {
        _state.update { it.copy(localGsiImages = scanLocalGsiImages()) }
    }

    fun deleteLocalGsiImage(path: String) {
        runCatching {
            val f = File(path)
            if (f.parentFile?.canonicalPath != baseWorkDir.canonicalPath) {
                error("仅允许删除 Download/AVBForge 目录下的本地镜像")
            }
            if (!f.delete()) error("文件删除失败")
        }.onSuccess {
            _state.update {
                it.copy(
                    localGsiImages = scanLocalGsiImages(),
                    statusMessage = "已删除本地镜像 ${File(path).name} 并释放存储空间",
                )
            }
        }.onFailure { err ->
            _state.update { it.copy(errorBanner = "删除镜像失败：${err.message}") }
        }
    }

    fun refreshDsuInfo(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _state.update { it.copy(busy = true, statusMessage = "正在刷新本机 DSU / GSI 状态...", errorBanner = null) }
            }
            val res = execCore(args = listOf("dsu", "info"), requireRoot = true, onLog = ::appendLog)
            val localImgs = scanLocalGsiImages()
            res.onSuccess { elem ->
                val info = runner.json.decodeFromJsonElement<DsuInfoReport>(elem)
                _state.update {
                    it.copy(
                        busy = if (silent) it.busy else false,
                        dsuInfo = info,
                        dsuStatusText = info.rawStatus,
                        localGsiImages = localImgs,
                        statusMessage = if (silent) it.statusMessage else "DSU 状态已更新",
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busy = if (silent) it.busy else false,
                        localGsiImages = localImgs,
                        errorBanner = if (silent) it.errorBanner else "读取 DSU 状态失败：${err.message}",
                    )
                }
            }
        }
    }

    fun enableDsu(singleBoot: Boolean) {
        viewModelScope.launch {
            val modeText = if (singleBoot) "单次防变砖启动 (-s)" else "持续启动"
            _state.update { it.copy(busy = true, statusMessage = "正在启用 GSI（$modeText）...", errorBanner = null) }
            val res = execCore(
                args = listOf("dsu", "enable", "--single-boot", singleBoot.toString()),
                requireRoot = true,
                onLog = ::appendLog,
            )
            res.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        statusMessage = "已启用 GSI（$modeText），现在重启即可进入！",
                    )
                }
                refreshDsuInfo(silent = true)
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "启用 DSU 失败：${err.message}") }
            }
        }
    }

    fun wipeDsuData() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在重置 GSI 独立 Userdata（保留系统镜像）...", errorBanner = null) }
            val res = execCore(args = listOf("dsu", "wipe-data"), requireRoot = true, onLog = ::appendLog)
            res.onSuccess {
                _state.update { it.copy(busy = false, statusMessage = "已标记重置 GSI 数据，下次进入 GSI 将重新初始化（不影响原系统）") }
                refreshDsuInfo(silent = true)
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "重置 GSI 数据失败：${err.message}") }
            }
        }
    }

    fun rebootToDsu() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在重启设备进入 GSI...", errorBanner = null) }
            val res = execCore(args = listOf("dsu", "reboot"), requireRoot = true, onLog = ::appendLog)
            res.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "重启失败：${err.message}") }
            }
        }
    }

    fun rebootDevice() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, statusMessage = "正在执行安全重启...", errorBanner = null) }
            val res = execCore(args = listOf("dsu", "reboot"), requireRoot = true, onLog = ::appendLog)
            res.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "重启失败：${err.message}") }
            }
        }
    }

    fun runDsuSimple(action: String) {
        viewModelScope.launch {
            val label = when (action) {
                "disable" -> "禁用 DSU"
                "wipe" -> "彻底卸载 DSU 并释放空间"
                else -> "执行 gsi_tool $action"
            }
            _state.update { it.copy(busy = true, statusMessage = "正在$label...", errorBanner = null) }
            val res = execCore(args = listOf("dsu", action), requireRoot = true, onLog = ::appendLog)
            res.onSuccess {
                if (action == "wipe") {
                    prefs.edit().remove("last_dsu_image_name").apply()
                }
                _state.update {
                    it.copy(
                        busy = false,
                        lastInstalledDsuImageName = if (action == "wipe") null else it.lastInstalledDsuImageName,
                        statusMessage = "${label}完成",
                    )
                }
                refreshDsuInfo(silent = true)
                refreshDevice()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "${label}失败：${err.message}") }
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
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = "正在备份当前 init_boot 并注入 DSU 公钥...", errorBanner = null) }

            val expRes = execCore(
                args = listOf("export-init-boot", "--output", bkpFile.absolutePath),
                requireRoot = true,
                onLog = ::appendLog,
                onRawLine = ::onCoreRawLine,
            )
            if (expRes.isFailure) {
                _state.update { it.copy(busy = false, errorBanner = "备份当前 init_boot 失败：${expRes.exceptionOrNull()?.message}") }
                return@launch
            }

            runCatching {
                vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                    execCore(
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
                        onRawLine = ::onCoreRawLine,
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

    fun exportCleanInitBootForKsu(stripKsu: Boolean = true) {
        viewModelScope.launch {
            val ts = System.currentTimeMillis() / 1000
            val backupFile = File(baseWorkDir, "init_boot_live_backup_$ts.img")
            val cleanFile = File(baseWorkDir, "init_boot_clean_for_ksu_$ts.img")
            prepareWhiteBoxConsole()
            _state.update {
                it.copy(
                    busy = true,
                    statusMessage = "正在备份当前 init_boot 并导出纯净底包...",
                    errorBanner = null,
                )
            }
            val res = execCore(
                args = listOf(
                    "export-clean-init-boot",
                    "--output", cleanFile.absolutePath,
                    "--strip-ksu", stripKsu.toString(),
                    "--raw-backup-output", backupFile.absolutePath,
                ),
                requireRoot = true,
                onLog = ::appendLog,
                onRawLine = ::onCoreRawLine,
            )
            res.onSuccess { elem ->
                val rep = runner.json.decodeFromJsonElement<ExportCleanInitBootReport>(elem)
                val msg = if (rep.strippedKernelsu) {
                    "已自动剥离旧版 KernelSU 并导出纯净底包：${File(rep.outputPath).name}"
                } else {
                    "已导出纯净 init_boot：${File(rep.outputPath).name}"
                }
                _state.update {
                    it.copy(
                        busy = false,
                        cleanInitBootReport = rep,
                        exportedInitBootPath = rep.outputPath,
                        statusMessage = msg,
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "导出纯净 init_boot 失败：${err.message}") }
            }
        }
    }

    fun prepareUpdatedKsuInitBoot(
        patchedImgPath: String,
        preserveDsuKey: Boolean,
        dsuKeyName: String,
    ) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在「工具 → 密钥库」导入与本机一致的默认 AVB 密钥") }
            return
        }
        viewModelScope.launch {
            val ts = System.currentTimeMillis() / 1000
            val outFile = File(baseWorkDir, "init_boot_ksu_signed_$ts.img")
            prepareWhiteBoxConsole()
            _state.update {
                it.copy(
                    busy = true,
                    statusMessage = "正在重签 KSU init_boot 并执行离线验签...",
                    errorBanner = null,
                )
            }

            // Ensure we have live init_boot metadata source (partition_size, rollback_index, props)
            var metaSource = _state.value.cleanInitBootReport?.backupPath?.takeIf { File(it).exists() }
            if (metaSource == null) {
                val tmpMeta = File(baseWorkDir, "init_boot_meta_backup_$ts.img")
                val exp = execCore(
                    args = listOf("export-init-boot", "--output", tmpMeta.absolutePath),
                    requireRoot = true,
                    onLog = ::appendLog,
                )
                if (exp.isSuccess) {
                    metaSource = tmpMeta.absolutePath
                }
            }

            runCatching {
                vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                    val args = mutableListOf(
                        "patch-dsu-key",
                        "--input", patchedImgPath,
                        "--output", outFile.absolutePath,
                        "--key", pemFile.absolutePath,
                        "--partition-name", "init_boot",
                        "--inject-dsu-key", preserveDsuKey.toString(),
                        "--target-key-name", dsuKeyName.ifBlank { "q-gsi.avbpubkey" },
                    )
                    if (!metaSource.isNullOrBlank()) {
                        args += listOf("--metadata-source", metaSource)
                    }
                    execCore(
                        args = args,
                        requireRoot = false,
                        onLog = ::appendLog,
                        onRawLine = ::onCoreRawLine,
                    )
                }
            }.onSuccess { res ->
                res.onSuccess { elem ->
                    val rep = runner.json.decodeFromJsonElement<PatchDsuKeyReport>(elem)
                    _state.update {
                        it.copy(
                            busy = false,
                            lastKsuUpdateReport = rep,
                            statusMessage = "KSU init_boot 自签名并验签通过（KSU=${rep.hasKernelsu}，DSU公钥=${rep.injectedPaths.isNotEmpty()}）",
                        )
                    }
                }.onFailure { err ->
                    _state.update { it.copy(busy = false, errorBanner = "重签/验签失败：${err.message}") }
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "读取私钥失败：${err.message}") }
            }
        }
    }

    fun flashPreparedDsuInitBoot(imagePath: String) {
        flashSingleInitBoot(
            imagePath = imagePath,
            successMessage = "DSU init_boot 写入并回读验签通过！重启一次手机后即可使用 DSU。",
        )
    }

    fun flashSingleInitBoot(
        imagePath: String,
        successMessage: String = "init_boot 写入并回读验签通过！现在重启手机即可生效（不会清空数据）。",
    ) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "未找到默认密钥") }
            return
        }
        viewModelScope.launch {
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = "正在写入活动槽位 init_boot 并清缓存回读校验...", errorBanner = null) }
            val res = execCore(
                args = listOf(
                    "flash-partition",
                    "--partition", "init_boot",
                    "--image", imagePath,
                    "--expected-pubkey", vault.pkmdFile(key.id).absolutePath,
                ),
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
                onRawLine = ::onCoreRawLine,
            )
            res.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        statusMessage = successMessage,
                    )
                }
                refreshDevice()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "写入 init_boot 失败：${err.message}") }
            }
        }
    }

    private fun scanLocalPartitionBackups(): List<LocalPartitionBackup> {
        val dir = baseWorkDir
        val subDirs = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        return subDirs.mapNotNull { folder ->
            val imgFiles = folder.listFiles()?.filter { it.isFile && it.name.endsWith(".img", ignoreCase = true) }.orEmpty()
            if (imgFiles.isEmpty()) return@mapNotNull null
            val manifestFile = File(folder, "manifest.json")
            val manifest = if (manifestFile.exists()) {
                runCatching { runner.json.decodeFromString<BackupManifest>(manifestFile.readText()) }.getOrNull()
            } else null
            val parts = manifest?.partitions?.map { it.partition }
                ?: imgFiles.map { it.nameWithoutExtension }.sorted()
            val pubkey = manifest?.partitions?.firstOrNull { !it.avbPubkeySha1.isNullOrBlank() }?.avbPubkeySha1
            val totalSize = imgFiles.sumOf { it.length() }
            LocalPartitionBackup(
                name = folder.name,
                path = folder.absolutePath,
                slot = manifest?.slot?.ifBlank { "_b" } ?: (_state.value.snapshot?.activeSlot ?: "_b"),
                partitions = parts,
                totalBytes = totalSize,
                lastModified = folder.lastModified(),
                pubkeySha1 = pubkey,
            )
        }.sortedByDescending { it.lastModified }
    }

    fun refreshLocalBackups() {
        _state.update { it.copy(localPartitionBackups = scanLocalPartitionBackups()) }
    }

    fun backupPartitionsNamed(
        folderName: String,
        slot: String? = null,
        partitions: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            val safeName = folderName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank {
                "backup_${System.currentTimeMillis() / 1000}"
            }
            val outDir = File(baseWorkDir, safeName)
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = "正在备份分区到 $safeName...", errorBanner = null) }
            val args = mutableListOf("backup-partitions", "--out-dir", outDir.absolutePath)
            if (!slot.isNullOrBlank()) args += listOf("--slot", slot)
            if (partitions.isNotEmpty()) args += listOf("--partitions", partitions.joinToString(","))
            val res = execCore(
                args = args,
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
                onRawLine = ::onCoreRawLine,
            )
            res.onSuccess { elem ->
                val manifest = runner.json.decodeFromJsonElement<BackupManifest>(elem)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        lastBackup = manifest,
                        lastBackupDir = outDir.absolutePath,
                        localPartitionBackups = scanLocalPartitionBackups(),
                        statusMessage = "备份完成：${manifest.partitions.size} 个分区已保存到 $safeName",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "备份失败：${err.message}") }
            }
        }
    }

    fun renamePartitionBackup(oldPath: String, newName: String) {
        runCatching {
            val src = File(oldPath)
            val safeName = newName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (safeName.isBlank()) error("名称不能为空")
            if (src.parentFile?.canonicalPath != baseWorkDir.canonicalPath) error("仅允许管理 AVBForge 目录内的备份")
            val dst = File(baseWorkDir, safeName)
            if (dst.exists()) error("同名备份目录已存在")
            if (!src.renameTo(dst)) error("重命名失败")
            safeName
        }.onSuccess { safeName ->
            _state.update {
                it.copy(
                    localPartitionBackups = scanLocalPartitionBackups(),
                    statusMessage = "已重命名备份为 $safeName",
                )
            }
        }.onFailure { err ->
            _state.update { it.copy(errorBanner = "重命名失败：${err.message}") }
        }
    }

    fun deletePartitionBackup(path: String) {
        runCatching {
            val dir = File(path)
            if (dir.parentFile?.canonicalPath != baseWorkDir.canonicalPath) error("仅允许删除 AVBForge 目录内的备份")
            if (!dir.deleteRecursively()) error("删除备份目录失败")
        }.onSuccess {
            _state.update {
                it.copy(
                    localPartitionBackups = scanLocalPartitionBackups(),
                    statusMessage = "已删除备份 ${File(path).name}",
                )
            }
        }.onFailure { err ->
            _state.update { it.copy(errorBanner = "删除备份失败：${err.message}") }
        }
    }

    /**
     * Re-sign (optional) and flash a single partition (`init_boot`, `boot`, `vendor_boot`, etc.).
     * Never silently modifies ramdisk or injects DSU pubkeys behind the user's back.
     */
    fun resignAndFlashSinglePartition(
        partition: String,
        imagePath: String,
        autoSign: Boolean,
        targetSlot: String? = null,
    ) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在密钥库中设置默认私钥") }
            return
        }
        viewModelScope.launch {
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = "正在处理并写入 $partition...", errorBanner = null) }
            val ts = System.currentTimeMillis() / 1000
            var imageToFlash = imagePath

            if (autoSign && partition != "devinfo") {
                val signedOut = File(baseWorkDir, "${partition}_signed_$ts.img")
                val metaBackup = File(baseWorkDir, "${partition}_meta_$ts.img")
                if (partition == "init_boot") {
                    val exp = execCore(
                        args = listOf("export-init-boot", "--output", metaBackup.absolutePath),
                        requireRoot = true,
                        onLog = ::appendLog,
                        onRawLine = ::onCoreRawLine,
                    )
                    val signRes = runCatching {
                        vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                            val args = mutableListOf(
                                "patch-dsu-key",
                                "--input", imagePath,
                                "--output", signedOut.absolutePath,
                                "--key", pemFile.absolutePath,
                                "--partition-name", "init_boot",
                                "--inject-dsu-key", "false",
                            )
                            if (exp.isSuccess) args += listOf("--metadata-source", metaBackup.absolutePath)
                            execCore(args = args, requireRoot = false, onLog = ::appendLog, onRawLine = ::onCoreRawLine)
                        }
                    }
                    val inner = signRes.getOrNull()
                    if (signRes.isFailure || inner == null || inner.isFailure) {
                        val msg = signRes.exceptionOrNull()?.message ?: inner?.exceptionOrNull()?.message ?: "重签失败"
                        _state.update { it.copy(busy = false, errorBanner = "重签 $partition 失败：$msg") }
                        return@launch
                    }
                    imageToFlash = signedOut.absolutePath
                } else {
                    // For boot / vendor_boot / dtbo / recovery: copy & add_hash_footer using partition size from live snapshot
                    runCatching { File(imagePath).copyTo(signedOut, overwrite = true) }
                    val partSize = _state.value.snapshot?.partitionSizes
                        ?.firstOrNull { it.name == partition }?.sizeBytes
                        ?: signedOut.length()
                    val signRes = runCatching {
                        vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                            execCore(
                                args = listOf(
                                    "avb", "add-hash-footer",
                                    "--image", signedOut.absolutePath,
                                    "--partition-name", partition,
                                    "--partition-size", partSize.toString(),
                                    "--key", pemFile.absolutePath,
                                ),
                                requireRoot = false,
                                onLog = ::appendLog,
                                onRawLine = ::onCoreRawLine,
                            )
                        }
                    }
                    val inner = signRes.getOrNull()
                    if (signRes.isFailure || inner == null || inner.isFailure) {
                        val msg = signRes.exceptionOrNull()?.message ?: inner?.exceptionOrNull()?.message ?: "重签失败"
                        _state.update { it.copy(busy = false, errorBanner = "重签 $partition 失败：$msg") }
                        return@launch
                    }
                    imageToFlash = signedOut.absolutePath
                }
            }

            val flashArgs = mutableListOf(
                "flash-partition",
                "--partition", partition,
                "--image", imageToFlash,
                "--expected-pubkey", vault.pkmdFile(key.id).absolutePath,
            )
            if (!targetSlot.isNullOrBlank()) flashArgs += listOf("--slot", targetSlot)

            val flashRes = execCore(
                args = flashArgs,
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
                onRawLine = ::onCoreRawLine,
            )
            flashRes.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        statusMessage = "$partition 已安全写入并通过回读核验",
                    )
                }
                refreshDevice()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "写入 $partition 失败：${err.message}") }
            }
        }
    }

    fun forgeAndFlashFullChain(
        stockDirPath: String,
        writeDevinfoKey: Boolean,
        targetSlot: String? = null,
    ) {
        val key = vault.getDefaultKey() ?: run {
            _state.update { it.copy(errorBanner = "请先在密钥库中设置默认私钥") }
            return
        }
        viewModelScope.launch {
            val ts = System.currentTimeMillis() / 1000
            val outDir = File(baseWorkDir, "forged_$ts")
            prepareWhiteBoxConsole()
            _state.update { it.copy(busy = true, statusMessage = "正在全链重签并验签 7 个分区...", errorBanner = null) }

            val forgeRes = runCatching {
                vault.withTempPlaintextKey(key.id) { pemFile, _ ->
                    execCore(
                        args = listOf(
                            "forge-bundle",
                            "--stock-dir", stockDirPath,
                            "--out-dir", outDir.absolutePath,
                            "--key", pemFile.absolutePath,
                            "--inject-dsu-key", "false",
                        ),
                        requireRoot = false,
                        onLog = ::appendLog,
                        onRawLine = ::onCoreRawLine,
                    )
                }
            }
            val forgeInner = forgeRes.getOrNull()
            if (forgeRes.isFailure || forgeInner == null || forgeInner.isFailure) {
                val msg = forgeRes.exceptionOrNull()?.message ?: forgeInner?.exceptionOrNull()?.message ?: "全链重签失败"
                _state.update { it.copy(busy = false, errorBanner = "全链构建失败：$msg") }
                return@launch
            }

            if (writeDevinfoKey) {
                val devBackup = File(baseWorkDir, "devinfo_orig_$ts.img")
                val devRes = execCore(
                    args = listOf(
                        "write-devinfo-key",
                        "--pkmd", vault.pkmdFile(key.id).absolutePath,
                        "--keep-locked", "true",
                        "--backup-output", devBackup.absolutePath,
                    ),
                    requireRoot = true,
                    onLog = ::appendLog,
                    onRawLine = ::onCoreRawLine,
                )
                if (devRes.isFailure) {
                    _state.update { it.copy(busy = false, errorBanner = "写入 devinfo user key 失败：${devRes.exceptionOrNull()?.message}") }
                    return@launch
                }
            }

            val flashArgs = mutableListOf(
                "flash-bundle",
                "--bundle-dir", outDir.absolutePath,
                "--expected-pubkey", vault.pkmdFile(key.id).absolutePath,
            )
            if (!targetSlot.isNullOrBlank()) flashArgs += listOf("--slot", targetSlot)

            val flashRes = execCore(
                args = flashArgs,
                requireRoot = true,
                onLog = ::appendLog,
                onProgress = { p -> _state.update { it.copy(progress = p) } },
                onRawLine = ::onCoreRawLine,
            )
            flashRes.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        statusMessage = "全链自签名包已安全写入并通过回读核验！",
                    )
                }
                refreshDevice()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, progress = null, errorBanner = "全链刷写失败：${err.message}") }
            }
        }
    }

    fun backupDsuNamed(folderName: String) {
        viewModelScope.launch {
            val safeName = folderName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank {
                "dsu_backup_${System.currentTimeMillis() / 1000}"
            }
            val outDir = File(baseWorkDir, safeName)
            _state.update { it.copy(busy = true, statusMessage = "正在备份当前 DSU 到 $safeName...", errorBanner = null) }
            val res = execCore(
                args = listOf("dsu", "backup", "--out-dir", outDir.absolutePath),
                requireRoot = true,
                timeoutSeconds = 1800,
                onLog = ::appendLog,
                onRawLine = ::onCoreRawLine,
            )
            res.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        statusMessage = "DSU 已备份到 Download/AVBForge/$safeName",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, errorBanner = "备份 DSU 失败：${err.message}") }
            }
        }
    }
}
