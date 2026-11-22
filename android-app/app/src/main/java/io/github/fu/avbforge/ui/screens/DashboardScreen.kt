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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.FreezerDetector
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.ExpressiveCard
import io.github.fu.avbforge.ui.components.ExpressiveHeroCard
import io.github.fu.avbforge.ui.components.ExpressiveMetricTile
import io.github.fu.avbforge.ui.components.ExpressivePillChip
import io.github.fu.avbforge.ui.components.ExpressivePrimaryButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val snap = state.snapshot
    var showChainDetails by remember { mutableStateOf(false) }

    val vbs = snap?.verifiedBootState?.lowercase().orEmpty()
    val isYellow = vbs == "yellow"
    val isGreen = vbs == "green"
    val isNeo9 = snap?.let { SafetyGate.isSupportedNeo9(it) } ?: true

    val heroStart = when {
        snap == null -> MaterialTheme.colorScheme.surfaceContainerHigh
        !isNeo9 || snap.sdkInt >= 35 -> MaterialTheme.colorScheme.errorContainer
        isYellow -> MaterialTheme.colorScheme.primaryContainer
        isGreen -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val heroEnd = when {
        snap == null -> MaterialTheme.colorScheme.surfaceContainer
        !isNeo9 || snap.sdkInt >= 35 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
        isYellow -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
        isGreen -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val heroFg = when {
        snap == null -> MaterialTheme.colorScheme.onSurface
        !isNeo9 || snap.sdkInt >= 35 -> MaterialTheme.colorScheme.onErrorContainer
        isYellow -> MaterialTheme.colorScheme.onPrimaryContainer
        isGreen -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ExpressiveHeroCard(
            title = if (snap == null) "等待设备体检" else "${snap.manufacturer.uppercase()} ${snap.model}",
            subtitle = if (snap == null) {
                "请授予 Root（含临时 Root）后点击刷新"
            } else {
                "Android ${snap.androidRelease} (SDK ${snap.sdkInt}) · VNDK ${snap.vndkVersion} · ${snap.kernelRelease.substringBefore('-')}"
            },
            icon = when {
                snap == null -> Icons.Rounded.Security
                !isNeo9 || snap.sdkInt >= 35 -> Icons.Rounded.WarningAmber
                isYellow -> Icons.Rounded.VerifiedUser
                else -> Icons.Rounded.Shield
            },
            accentStart = heroStart,
            accentEnd = heroEnd,
            contentColor = heroFg,
            isPulsing = state.busy,
            badgeText = snap?.let { "槽位 ${it.activeSlot.ifEmpty { "—" }}" },
        ) {
            if (snap != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExpressivePillChip(
                        text = "${snap.verifiedBootState.uppercase()} · ${snap.deviceState.uppercase()}",
                        icon = Icons.Rounded.Shield,
                        containerColor = heroFg.copy(alpha = 0.14f),
                        contentColor = heroFg,
                    )
                    if (snap.sdkInt == 34 && isNeo9) {
                        ExpressivePillChip(
                            text = "Neo9 A14 已验证",
                            icon = Icons.Rounded.CheckCircle,
                            containerColor = heroFg.copy(alpha = 0.14f),
                            contentColor = heroFg,
                        )
                    }
                    snap.vbmetaPubkeySha1?.let { sha ->
                        ExpressivePillChip(
                            text = "vbmeta ${sha.take(10)}",
                            icon = Icons.Rounded.Key,
                            containerColor = heroFg.copy(alpha = 0.14f),
                            contentColor = heroFg,
                            copyValue = sha,
                            onCopied = { onNotify("已复制 vbmeta 公钥 SHA-1") },
                        )
                    }
                }
            }
        }

        // Non-Neo9 Warning
        AnimatedVisibility(
            visible = snap != null && !isNeo9,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            if (snap != null) {
                ExpressiveCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "非目标机型警示（仅针对 iQOO Neo9 开发）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Text(
                        "当前机型为 ${snap.manufacturer} ${snap.model} (${snap.device})。本应用仅针对 iQOO Neo9 (V2338A/PD2338) Android 14 验证，切勿在其他设备上盲目刷写分区！",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Android 15+ High Risk Warning
        AnimatedVisibility(
            visible = snap != null && snap.sdkInt >= 35,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            if (snap != null) {
                ExpressiveCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "Android ${snap.androidRelease} 高版本固件警示",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Text(
                        "高版本固件（如 vivo/iQOO A15/A16）Bootloader 可能已收紧 avb_custom_key 链路，刷写自签名镜像前请务必核实兼容性。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // 2x2 Expressive Metric Grid
        if (snap != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveMetricTile(
                    label = "KernelSU",
                    value = snap.ksuVersion?.substringBefore(" (") ?: if (snap.initBootHasKsu == true) "已注入 LKM" else "未检测到",
                    icon = Icons.Rounded.Memory,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                ExpressiveMetricTile(
                    label = "DSU 公钥",
                    value = if (snap.initBootAvbpubkeys.isNotEmpty()) "已就绪 (${snap.initBootAvbpubkeys.size})" else "未注入",
                    icon = Icons.Rounded.Key,
                    tint = if (snap.initBootAvbpubkeys.isNotEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveMetricTile(
                    label = "DSU 运行态",
                    value = snap.dsuStatus,
                    icon = Icons.Rounded.SystemUpdate,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                ExpressiveMetricTile(
                    label = "托管密钥数",
                    value = "${state.keys.size} 把",
                    icon = Icons.Rounded.VerifiedUser,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Primary Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExpressivePrimaryButton(
                text = "重新体检",
                icon = Icons.Rounded.Refresh,
                onClick = { vm.refreshDevice() },
                enabled = !state.busy,
                loading = state.busy && state.progress == null,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { vm.backupActiveSlot() },
                enabled = !state.busy,
                shape = CircleShape,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("黄金备份", style = MaterialTheme.typography.labelLarge)
            }
        }

        // OriginOS Freezer Advice
        if (snap?.freezerEnabled == true && !FreezerDetector.isIgnoringBatteryOptimizations(ctx)) {
            ExpressiveCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.AcUnit, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "检测到系统后台冻结（OriginOS 墓碑）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    "建议将 AVBForge 和 KernelSU 管理器均设为「允许后台高耗电」，避免 Root 子进程被冻进 freezer 导致卡死。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                FilledTonalButton(
                    onClick = { FreezerDetector.openBatteryOptimizationSettings(ctx) },
                    shape = CircleShape,
                ) {
                    Text("前往电池优化设置")
                }
            }
        }

        // Expandable AVB Chain & fstab Details
        if (snap != null) {
            ExpressiveCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showChainDetails = !showChainDetails },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("AVB 链与 fstab 详情", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${snap.vbmetaChainedPartitions.size} 个链式分区 · ${snap.fstabAvbKeys.size} 个 fstab 公钥路径",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (showChainDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(visible = showChainDetails, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("vbmeta 链式验证分区：", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            snap.vbmetaChainedPartitions.forEach { part ->
                                ExpressivePillChip(text = part)
                            }
                        }
                        Text("fstab avb_keys：", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            snap.fstabAvbKeys.forEach { keyPath ->
                                ExpressivePillChip(text = keyPath)
                            }
                        }
                        if (snap.initBootAvbpubkeys.isNotEmpty()) {
                            Text("init_boot 已注入路径：", style = MaterialTheme.typography.labelMedium)
                            snap.initBootAvbpubkeys.forEach { p ->
                                Text("• $p", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // Last Backup Card
        AnimatedVisibility(visible = state.lastBackup != null) {
            state.lastBackup?.let { bkp ->
                ExpressiveCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Text("最近原厂黄金备份（槽位 ${bkp.slot}）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "已备份 ${bkp.partitions.size} 个分区：${bkp.partitions.joinToString { it.partition }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.lastBackupDir?.let { dir ->
                        ExpressivePillChip(
                            text = dir,
                            copyValue = dir,
                            onCopied = { onNotify("已复制备份目录路径") },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(92.dp))
    }
}
