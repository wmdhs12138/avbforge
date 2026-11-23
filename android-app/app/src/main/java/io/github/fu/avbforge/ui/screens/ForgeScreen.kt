package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.RiskLevel
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.data.PartitionSizeInfo
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EHelpButton
import io.github.fu.avbforge.ui.components.M3EHeroCard
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File
import java.util.Locale

private data class PartitionMeta(
    val name: String,
    val desc: String,
    val fallbackBytes: Long,
    val minimalDefault: Boolean,
)

private val KNOWN_PARTITIONS = listOf(
    PartitionMeta("init_boot", "KernelSU LKM 与 DSU 公钥所在分区", 8L * 1024 * 1024, true),
    PartitionMeta("vbmeta", "AVB 根校验表（链式校验入口）", 64L * 1024, true),
    PartitionMeta("vbmeta_system", "super 内 7 个逻辑分区总校验表", 64L * 1024, false),
    PartitionMeta("boot", "GKI 内核分区（首次全链迁移必选）", 96L * 1024 * 1024, false),
    PartitionMeta("dtbo", "设备树覆盖（首次全链迁移必选）", 24L * 1024 * 1024, false),
    PartitionMeta("vendor_boot", "厂商 Ramdisk / fstab（体积较大）", 128L * 1024 * 1024, false),
    PartitionMeta("recovery", "恢复模式镜像（体积最大）", 192L * 1024 * 1024, false),
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Dedicated First-Time Self-Signed Boot & KernelSU Migration Page:
 * Step 1: Backup stock partitions (built-in custom partition picker!) or pick existing folder
 * Step 2: Optional KernelSU Root collaboration (export stock init_boot -> user patches -> pick back)
 * Step 3: Build & mandatory full-chain verification (+ optional DSU pubkey injection)
 * Step 4: Safety Gate (with explicit Data Partition Format warning!) & Readback Flash
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ForgeScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val snap = state.snapshot
    val activeSlot = snap?.activeSlot?.ifBlank { "_b" } ?: "_b"
    val isYellow = snap?.verifiedBootState.equals("yellow", ignoreCase = true)

    var stockDir by remember { mutableStateOf(state.lastBackupDir ?: "") }
    var includeKsuRoot by remember { mutableStateOf(true) }
    var patchedInitBootPath by remember { mutableStateOf("") }
    var injectDsuKey by remember { mutableStateOf(true) }
    var dsuKeyName by remember {
        mutableStateOf(
            snap?.fstabAvbKeys?.firstOrNull()?.substringAfterLast('/') ?: "q-gsi.avbpubkey"
        )
    }
    var slotChoiceIdx by remember { mutableIntStateOf(0) }
    val slotChoices = listOf("" to "当前槽位 ($activeSlot)", "_a" to "槽位 _a", "_b" to "槽位 _b")
    var showBackupSheet by remember { mutableStateOf(false) }
    var showFlashConfirm by remember { mutableStateOf(false) }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveTreeUri(uri)?.let {
                stockDir = it
                onNotify("已选择原厂散包目录：${File(it).name}")
            } ?: onNotify("无法解析所选目录")
        }
    }

    val ksuPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                patchedInitBootPath = it
                onNotify("已选择 KSU 修补产物：${File(it).name}")
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Notice banner about first-time data wipe when on Green stock
        if (!isYellow) {
            M3EGroupRow(
                title = "首次迁移自签名将格式化数据分区",
                subtitle = "从原厂切换为自定义密钥后重启会清空全部用户数据，请提前备份个人资料并确保已在「密钥」页配置好密钥。",
                icon = Icons.Rounded.WarningAmber,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBg = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        }

        // Step 1: Backup & Select Stock Image Source
        M3ESectionLabel("1 · 备份或选择原厂镜像", helpDoc = HelpDocs.BACKUP_PARTITIONS)
        val srcRows = if (state.lastBackupDir != null) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "提取备份本机原厂引导分区",
                subtitle = "当前活动槽位 $activeSlot · 支持自定义勾选或一键 7 分区完整链",
                icon = Icons.Rounded.Backup,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconBg = MaterialTheme.colorScheme.secondaryContainer,
                shape = m3eGroupShape(0, srcRows),
                showChevron = true,
                onClick = { if (!state.busy) showBackupSheet = true },
            )
            M3EGroupRow(
                title = if (stockDir.isBlank()) "选择已有原厂散包目录" else "已选目录：${File(stockDir).name}",
                subtitle = stockDir.ifBlank { "需包含 vbmeta.img、vbmeta_system.img 及引导分区" },
                icon = Icons.Rounded.FolderOpen,
                shape = m3eGroupShape(1, srcRows),
                showChevron = true,
                monospaceSubtitle = stockDir.isNotBlank(),
                helpDoc = HelpDocs.FORGE_STOCK_DIR,
                onClick = { dirPicker.launch(null) },
            )
            state.lastBackupDir?.let { bkpDir ->
                M3EGroupRow(
                    title = "填入刚完成的黄金备份目录",
                    subtitle = bkpDir,
                    icon = Icons.Rounded.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    iconBg = MaterialTheme.colorScheme.secondaryContainer,
                    shape = m3eGroupShape(2, srcRows),
                    trailingText = "使用",
                    onClick = {
                        stockDir = bkpDir
                        onNotify("已填入最近备份目录")
                    },
                )
            }
        }

        // Step 2: KernelSU Root Collaboration
        M3ESectionLabel("2 · KernelSU Root 协作修补", helpDoc = HelpDocs.KSU_COLLAB)
        val ksuRowCount = if (includeKsuRoot) 3 else 1
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "随本轮一并刷入 KernelSU",
                subtitle = if (includeKsuRoot) "推荐开启，重启直接保有持久 Root" else "关闭则重启后无 Root，需重新获取临时 Root",
                icon = Icons.Rounded.Memory,
                shape = m3eGroupShape(0, ksuRowCount),
                trailing = {
                    Switch(checked = includeKsuRoot, onCheckedChange = { includeKsuRoot = it })
                },
            )
            if (includeKsuRoot) {
                M3EGroupRow(
                    title = "导出纯净 init_boot.img",
                    subtitle = state.exportedInitBootPath ?: "导出后请用你偏爱的 KSU 管理器修补",
                    icon = Icons.Rounded.FileDownload,
                    shape = m3eGroupShape(1, ksuRowCount),
                    showChevron = true,
                    onClick = { if (!state.busy) vm.exportInitBootForKsuManager() },
                )
                M3EGroupRow(
                    title = if (patchedInitBootPath.isBlank()) "选择 KSU 修补后的镜像" else File(patchedInitBootPath).name,
                    subtitle = patchedInitBootPath.ifBlank { "点击选取 kernelsu_patched_*.img" },
                    icon = Icons.Rounded.UploadFile,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    iconBg = MaterialTheme.colorScheme.secondaryContainer,
                    shape = m3eGroupShape(2, ksuRowCount),
                    showChevron = true,
                    monospaceSubtitle = patchedInitBootPath.isNotBlank(),
                    onClick = { ksuPicker.launch(arrayOf("*/*")) },
                )
            }
        }

        // Step 3: Build & Offline Full-Chain Verification
        M3ESectionLabel("3 · 构建与全链验签", helpDoc = HelpDocs.DSU_PUBKEY)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "顺带注入 DSU 公钥（推荐）",
                subtitle = if (injectDsuKey) "目标：$dsuKeyName · 省去后续再刷一次 init_boot" else "仅重签系统分区，后续可随时在「DSU」页启用",
                icon = Icons.Rounded.Key,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                shape = m3eGroupShape(0, 1),
                trailing = {
                    Switch(checked = injectDsuKey, onCheckedChange = { injectDsuKey = it })
                },
            )
        }

        val fstabNames = snap?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()
        AnimatedVisibility(visible = injectDsuKey && fstabNames.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                fstabNames.forEach { kname ->
                    M3EPillChip(
                        text = kname,
                        containerColor = if (dsuKeyName == kname) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = if (dsuKeyName == kname) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { dsuKeyName = kname },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        M3EPrimaryButton(
            text = "构建全套自签名包并全链验签",
            icon = Icons.Rounded.AutoFixHigh,
            onClick = {
                vm.forgeSelfSignedBundle(
                    stockDirPath = stockDir.trim(),
                    patchedInitBootPath = if (includeKsuRoot) patchedInitBootPath.trim().ifBlank { null } else null,
                    injectDsuKey = injectDsuKey,
                    dsuKeyName = dsuKeyName.trim(),
                )
            },
            enabled = !state.busy && stockDir.isNotBlank(),
            loading = state.busy,
            modifier = Modifier.fillMaxWidth(),
        )

        // Step 4: Verified Result + Safety Gate Flash
        AnimatedVisibility(
            visible = state.lastForgeReport != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.lastForgeReport?.let { rep ->
                M3EHeroCard(
                    title = "全链验签通过（${rep.chainVerifiedCount} 个镜像）",
                    subtitle = "KSU: ${if (rep.initBootHasKsu) "已含" else "无"} · DSU公钥: ${if (rep.dsuKeyInjected) "已注入" else "无"}",
                    icon = Icons.Rounded.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        slotChoices.forEachIndexed { idx, (_, label) ->
                            SegmentedButton(
                                selected = slotChoiceIdx == idx,
                                onClick = { slotChoiceIdx = idx },
                                shape = SegmentedButtonDefaults.itemShape(idx, slotChoices.size),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                    M3EPrimaryButton(
                        text = "进入安全门禁并写入目标槽位",
                        icon = Icons.Rounded.FlashOn,
                        onClick = { showFlashConfirm = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    if (showBackupSheet) {
        BackupPartitionsSheet(
            activeSlot = activeSlot,
            isYellowAlready = isYellow,
            liveSizes = snap?.partitionSizes.orEmpty(),
            busy = state.busy,
            onStartBackup = { chosenSlot, selectedParts ->
                showBackupSheet = false
                vm.backupPartitions(slot = chosenSlot, partitions = selectedParts)
            },
            onDismiss = { showBackupSheet = false },
        )
    }

    if (showFlashConfirm && state.lastForgeReport != null) {
        val assessment = vm.computeSafety(includeKsuRoot)
        val checks = remember(assessment) {
            mutableStateListOf<Boolean>().apply {
                repeat(assessment?.requiredChecklist?.size ?: 0) { add(false) }
            }
        }
        val allChecked = checks.all { it }

        AlertDialog(
            onDismissRequest = { showFlashConfirm = false },
            shape = RoundedCornerShape(28.dp),
            icon = {
                Icon(
                    if (assessment?.riskLevel == RiskLevel.SAFE) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (assessment?.riskLevel == RiskLevel.HIGH_RISK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(assessment?.sdkBannerTitle ?: "确认写入自签名镜像") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (assessment != null) {
                        Text(assessment.sdkBannerDetail, style = MaterialTheme.typography.bodySmall)
                        Text(assessment.stateNotice, style = MaterialTheme.typography.bodySmall)
                        assessment.requiredChecklist.forEachIndexed { i, item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checks[i], onCheckedChange = { checks[i] = it })
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (item.contains("数据清除")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFlashConfirm = false
                        val chosenSlot = slotChoices[slotChoiceIdx].first.ifBlank { null }
                        vm.flashForgedBundle(state.lastForgeReport.outputDir, chosenSlot)
                    },
                    enabled = allChecked,
                    shape = CircleShape,
                ) { Text("确认写入并回读核验") }
            },
            dismissButton = {
                TextButton(onClick = { showFlashConfirm = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BackupPartitionsSheet(
    activeSlot: String,
    isYellowAlready: Boolean,
    liveSizes: List<PartitionSizeInfo>,
    busy: Boolean,
    onStartBackup: (slot: String, partitions: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inactiveSlot = if (activeSlot == "_b") "_a" else "_b"
    val slotOptions = listOf(
        activeSlot to "活动槽位 ${activeSlot.trimStart('_').uppercase()} ($activeSlot)",
        inactiveSlot to "备用槽位 ${inactiveSlot.trimStart('_').uppercase()} ($inactiveSlot)",
    )
    var slotIdx by remember { mutableIntStateOf(0) }
    val sizeMap = remember(liveSizes) { liveSizes.associateBy { it.name } }

    val selected = remember {
        mutableStateMapOf<String, Boolean>().apply {
            KNOWN_PARTITIONS.forEach { meta ->
                put(meta.name, if (isYellowAlready) meta.minimalDefault else true)
            }
        }
    }

    val selectedList = KNOWN_PARTITIONS.filter { selected[it.name] == true }
    val totalBytes = selectedList.sumOf { meta ->
        sizeMap[meta.name]?.sizeBytes ?: meta.fallbackBytes
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("选择要备份的分区", style = MaterialTheme.typography.titleLarge)
                M3EHelpButton(doc = HelpDocs.BACKUP_PARTITIONS)
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                slotOptions.forEachIndexed { idx, (_, label) ->
                    SegmentedButton(
                        selected = slotIdx == idx,
                        onClick = { slotIdx = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, slotOptions.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                M3EPillChip(
                    text = "首次迁移完整 AVB 链 (7个)",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { KNOWN_PARTITIONS.forEach { selected[it.name] = true } },
                )
                M3EPillChip(
                    text = "仅 KSU/DSU 最小 (~8 MB)",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { KNOWN_PARTITIONS.forEach { selected[it.name] = it.minimalDefault } },
                )
                M3EPillChip(
                    text = "清空勾选",
                    onClick = { KNOWN_PARTITIONS.forEach { selected[it.name] = false } },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KNOWN_PARTITIONS.forEachIndexed { idx, meta ->
                    val checked = selected[meta.name] == true
                    val actualBytes = sizeMap[meta.name]?.sizeBytes ?: meta.fallbackBytes
                    val inChain = sizeMap[meta.name]?.inVbmetaChain ?: true

                    Surface(
                        shape = m3eGroupShape(idx, KNOWN_PARTITIONS.size),
                        color = if (checked) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(m3eGroupShape(idx, KNOWN_PARTITIONS.size))
                            .clickable { selected[meta.name] = !checked },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { selected[meta.name] = it })
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(meta.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = formatBytes(actualBytes),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                val tagPrefix = when {
                                    meta.minimalDefault -> "核心必选 · "
                                    inChain -> "首次必选 · "
                                    else -> ""
                                }
                                Text(
                                    text = "$tagPrefix${meta.desc}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            M3EPrimaryButton(
                text = if (selectedList.isEmpty()) "请至少选择 1 个分区" else "备份 ${selectedList.size} 个分区（约 ${formatBytes(totalBytes)}）",
                icon = Icons.Rounded.Backup,
                onClick = { onStartBackup(slotOptions[slotIdx].first, selectedList.map { it.name }) },
                enabled = !busy && selectedList.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
