package io.github.fu.avbforge.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.FreezerDetector
import io.github.fu.avbforge.core.SafetyGate
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snap = state.snapshot
    var expandChain by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }

    val vbs = snap?.verifiedBootState?.lowercase().orEmpty()
    val isYellow = vbs == "yellow"
    val isNeo9 = snap?.let { SafetyGate.isSupportedNeo9(it) } ?: true

    val activeSlot = snap?.activeSlot?.ifBlank { "_a" } ?: ""
    val activeSlotLetter = activeSlot.trimStart('_').uppercase()
    val inactiveSlot = if (activeSlot == "_b") "_a" else "_b"
    val inactiveSlotLetter = inactiveSlot.trimStart('_').uppercase()

    val heroBg = when {
        snap == null -> MaterialTheme.colorScheme.surfaceContainerHigh
        !isNeo9 || snap.sdkInt >= 35 -> MaterialTheme.colorScheme.errorContainer
        isYellow -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val heroFg = when {
        snap == null -> MaterialTheme.colorScheme.onSurface
        !isNeo9 || snap.sdkInt >= 35 -> MaterialTheme.colorScheme.onErrorContainer
        isYellow -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val heroTitle = when {
        snap == null -> "等待 Root 授权"
        !isNeo9 -> "非目标机型"
        snap.sdkInt >= 35 -> "高版本固件警示"
        isYellow -> "自签名链运行中"
        vbs == "green" -> "原厂系统 (可迁移)"
        else -> "状态: ${snap.verifiedBootState}"
    }
    val heroSub = if (snap == null) {
        "点此刷新，请在 KernelSU 中授予本应用超级用户权限"
    } else {
        "${snap.manufacturer} ${snap.model} · Android ${snap.androidRelease} (SDK ${snap.sdkInt})"
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // KernelSU-style Clean Solid Hero Card with Prominent Active Slot Highlight!
        M3EHeroCard(
            title = heroTitle,
            subtitle = heroSub,
            icon = when {
                snap == null -> Icons.Rounded.Security
                !isNeo9 || snap.sdkInt >= 35 -> Icons.Rounded.WarningAmber
                isYellow -> Icons.Rounded.CheckCircle
                else -> Icons.Rounded.Shield
            },
            containerColor = heroBg,
            contentColor = heroFg,
            badgeText = snap?.let { "${it.verifiedBootState.uppercase()} · ${it.deviceState.uppercase()}" },
            isPulsing = state.busy,
            onClick = { if (!state.busy) vm.refreshDevice() },
        ) {
            if (snap != null && activeSlotLetter.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = heroFg,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                tint = heroBg,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "当前活动槽位：$activeSlotLetter ($activeSlot)",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = heroBg,
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = heroFg.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = "备用：$inactiveSlotLetter",
                            style = MaterialTheme.typography.labelMedium,
                            color = heroFg,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    M3EHelpButton(doc = HelpDocs.ACTIVE_SLOT, tint = heroFg.copy(alpha = 0.85f))
                }
            }
        }

        // Risk / System Notices
        if (snap != null && !isNeo9) {
            M3EGroupRow(
                title = "非 iQOO Neo9 机型警示",
                subtitle = "本应用仅针对 iQOO Neo9 (V2338A/PD2338) Android 14 开发验证，切勿在其他机型盲目刷写分区！",
                icon = Icons.Rounded.WarningAmber,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBg = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        }
        if (snap != null && snap.sdkInt >= 35) {
            M3EGroupRow(
                title = "Android ${snap.androidRelease} 高风险固件",
                subtitle = "A15/A16 固件 BL 可能已收紧 avb_custom_key 链路，刷写前请务必确认兼容。",
                icon = Icons.Rounded.WarningAmber,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBg = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        }
        if (snap?.freezerEnabled == true && !FreezerDetector.isIgnoringBatteryOptimizations(ctx)) {
            M3EGroupRow(
                title = "检测到 OriginOS 后台冻结",
                subtitle = "点击前往电池优化，将本应用与 KernelSU 设为「允许后台高耗电」以防卡死",
                icon = Icons.Rounded.AcUnit,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                showChevron = true,
                helpDoc = HelpDocs.FREEZER_ORIGINOS,
                onClick = { FreezerDetector.openBatteryOptimizationSettings(ctx) },
            )
        }

        // Section 1: Quick Actions
        M3ESectionLabel("快捷操作")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "重新体检本机状态",
                subtitle = "刷新活动槽位、AVB 链、KernelSU 与 DSU 公钥状态",
                icon = Icons.Rounded.Refresh,
                shape = m3eGroupShape(0, 2),
                showChevron = true,
                onClick = { if (!state.busy) vm.refreshDevice() },
            )
            M3EGroupRow(
                title = "自定义备份引导分区",
                subtitle = state.lastBackupDir ?: "自由勾选分区或一键最小备份 (init_boot) / 完整 AVB 链",
                icon = Icons.Rounded.Backup,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconBg = MaterialTheme.colorScheme.secondaryContainer,
                shape = m3eGroupShape(1, 2),
                showChevron = true,
                helpDoc = HelpDocs.BACKUP_PARTITIONS,
                onClick = { if (!state.busy) showBackupSheet = true },
            )
        }

        // Section 2: System & AVB Info
        if (snap != null) {
            M3ESectionLabel("环境与 AVB 详情")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                M3EGroupRow(
                    title = "当前活动槽位",
                    subtitle = "系统当前从槽位 $activeSlotLetter ($activeSlot) 启动，非活动备用槽位为 $inactiveSlotLetter ($inactiveSlot)",
                    icon = Icons.Rounded.SwapHoriz,
                    trailingText = "槽位 $activeSlotLetter",
                    shape = m3eGroupShape(0, 7),
                    helpDoc = HelpDocs.ACTIVE_SLOT,
                )
                M3EGroupRow(
                    title = "KernelSU",
                    subtitle = if (snap.initBootHasKsu == true) "init_boot 已检测到 KSU LKM" else "未检测到 init_boot KSU 补丁",
                    icon = Icons.Rounded.Memory,
                    trailingText = snap.ksuVersion?.substringBefore(" (") ?: "未安装",
                    shape = m3eGroupShape(1, 7),
                )
                M3EGroupRow(
                    title = "DSU 自签名公钥",
                    subtitle = if (snap.initBootAvbpubkeys.isNotEmpty()) {
                        snap.initBootAvbpubkeys.first()
                    } else {
                        "尚未注入 ramdisk，可在「工坊」一键注入"
                    },
                    icon = Icons.Rounded.Key,
                    iconTint = if (snap.initBootAvbpubkeys.isNotEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    iconBg = if (snap.initBootAvbpubkeys.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    trailingText = if (snap.initBootAvbpubkeys.isNotEmpty()) "已就绪" else "未注入",
                    shape = m3eGroupShape(2, 7),
                    monospaceSubtitle = snap.initBootAvbpubkeys.isNotEmpty(),
                    helpDoc = HelpDocs.DSU_PUBKEY,
                )
                M3EGroupRow(
                    title = "vbmeta 公钥指纹",
                    subtitle = snap.vbmetaPubkeySha1 ?: "无",
                    icon = Icons.Rounded.Fingerprint,
                    trailingText = "复制",
                    shape = m3eGroupShape(3, 7),
                    monospaceSubtitle = true,
                    helpDoc = HelpDocs.VBMETA_KEY,
                    onClick = {
                        snap.vbmetaPubkeySha1?.let { sha ->
                            clipboard.setText(AnnotatedString(sha))
                            onNotify("已复制 vbmeta 公钥 SHA-1")
                        }
                    },
                )
                M3EGroupRow(
                    title = "DSU 状态",
                    subtitle = "原生 /system/bin/gsi_tool 状态",
                    icon = Icons.Rounded.SystemUpdate,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                    trailingText = snap.dsuStatus,
                    shape = m3eGroupShape(4, 7),
                )
                M3EGroupRow(
                    title = "内核与底层",
                    subtitle = snap.kernelRelease,
                    icon = Icons.Rounded.DeveloperBoard,
                    trailingText = "VNDK ${snap.vndkVersion}",
                    shape = m3eGroupShape(5, 7),
                )
                M3EGroupRow(
                    title = "AVB 链式分区与 fstab",
                    subtitle = "${snap.vbmetaChainedPartitions.size} 个链式分区 · ${snap.fstabAvbKeys.size} 个 avb_keys",
                    icon = Icons.Rounded.Link,
                    shape = m3eGroupShape(6, 7),
                    trailing = {
                        Icon(
                            if (expandChain) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { expandChain = !expandChain },
                )
            }

            AnimatedVisibility(
                visible = expandChain,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("vbmeta 链式分区", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            snap.vbmetaChainedPartitions.forEach { M3EPillChip(text = it) }
                        }
                        Text("fstab avb_keys 路径", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            snap.fstabAvbKeys.forEach { M3EPillChip(text = it) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    if (showBackupSheet) {
        BackupPartitionsSheet(
            activeSlot = activeSlot.ifBlank { "_b" },
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

    // Default selection: minimal (init_boot + vbmeta) if already Yellow, full 7-partition chain if Green/first-time!
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("选择要备份的分区", style = MaterialTheme.typography.titleLarge)
                M3EHelpButton(doc = HelpDocs.BACKUP_PARTITIONS)
            }

            // Slot Selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                slotOptions.forEachIndexed { idx, (_, label) ->
                    SegmentedButton(
                        selected = slotIdx == idx,
                        onClick = { slotIdx = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, slotOptions.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            // Quick Presets
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                M3EPillChip(
                    text = "仅 KSU/DSU 最小 (~8 MB)",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = {
                        KNOWN_PARTITIONS.forEach { selected[it.name] = it.minimalDefault }
                    },
                )
                M3EPillChip(
                    text = "首次迁移完整 AVB 链 (7个)",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        KNOWN_PARTITIONS.forEach { selected[it.name] = true }
                    },
                )
                M3EPillChip(
                    text = "清空勾选",
                    onClick = {
                        KNOWN_PARTITIONS.forEach { selected[it.name] = false }
                    },
                )
            }

            // Partition Checkbox List (M3E Grouped Style)
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
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { selected[meta.name] = it },
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(meta.name, style = MaterialTheme.typography.titleSmall)
                                    if (meta.minimalDefault) {
                                        M3EPillChip(
                                            text = "核心必选",
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    } else if (inChain) {
                                        M3EPillChip(
                                            text = "首次迁移必选",
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    meta.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = formatBytes(actualBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Summary + Action
            M3EPrimaryButton(
                text = if (selectedList.isEmpty()) {
                    "请至少选择 1 个分区"
                } else {
                    "备份 ${selectedList.size} 个分区（约 ${formatBytes(totalBytes)}）"
                },
                icon = Icons.Rounded.Backup,
                onClick = {
                    onStartBackup(slotOptions[slotIdx].first, selectedList.map { it.name })
                },
                enabled = !busy && selectedList.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
