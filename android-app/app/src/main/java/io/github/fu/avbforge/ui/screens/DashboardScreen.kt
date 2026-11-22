package io.github.fu.avbforge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.FreezerDetector
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EHeroCard
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.m3eGroupShape

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

    val vbs = snap?.verifiedBootState?.lowercase().orEmpty()
    val isYellow = vbs == "yellow"
    val isNeo9 = snap?.let { SafetyGate.isSupportedNeo9(it) } ?: true

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
        "${snap.manufacturer} ${snap.model} · Android ${snap.androidRelease} · 槽位 ${snap.activeSlot}"
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // KernelSU-style Clean Solid Hero Card
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
        )

        // Risk / System Notices (only shown when relevant!)
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
                onClick = { FreezerDetector.openBatteryOptimizationSettings(ctx) },
            )
        }

        // Section 1: Quick Actions (M3E Grouped List)
        M3ESectionLabel("快捷操作")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "重新体检本机状态",
                subtitle = "刷新 AVB 签名链、KernelSU 与 DSU 公钥状态",
                icon = Icons.Rounded.Refresh,
                shape = m3eGroupShape(0, 2),
                showChevron = true,
                onClick = { if (!state.busy) vm.refreshDevice() },
            )
            M3EGroupRow(
                title = "备份当前槽位全部引导分区",
                subtitle = state.lastBackupDir ?: "导出 boot / init_boot / vendor_boot / vbmeta 等到 Download/AVBForge",
                icon = Icons.Rounded.Backup,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconBg = MaterialTheme.colorScheme.secondaryContainer,
                shape = m3eGroupShape(1, 2),
                showChevron = true,
                onClick = { if (!state.busy) vm.backupActiveSlot() },
            )
        }

        // Section 2: System & AVB Info (Signature Android 16 Grouped List)
        if (snap != null) {
            M3ESectionLabel("环境与 AVB 详情")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                M3EGroupRow(
                    title = "KernelSU",
                    subtitle = if (snap.initBootHasKsu == true) "init_boot 已检测到 KSU LKM" else "未检测到 init_boot KSU 补丁",
                    icon = Icons.Rounded.Memory,
                    trailingText = snap.ksuVersion?.substringBefore(" (") ?: "未安装",
                    shape = m3eGroupShape(0, 6),
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
                    shape = m3eGroupShape(1, 6),
                    monospaceSubtitle = snap.initBootAvbpubkeys.isNotEmpty(),
                )
                M3EGroupRow(
                    title = "vbmeta 公钥指纹",
                    subtitle = snap.vbmetaPubkeySha1 ?: "无",
                    icon = Icons.Rounded.Fingerprint,
                    trailingText = "复制",
                    shape = m3eGroupShape(2, 6),
                    monospaceSubtitle = true,
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
                    shape = m3eGroupShape(3, 6),
                )
                M3EGroupRow(
                    title = "内核与底层",
                    subtitle = snap.kernelRelease,
                    icon = Icons.Rounded.DeveloperBoard,
                    trailingText = "VNDK ${snap.vndkVersion}",
                    shape = m3eGroupShape(4, 6),
                )
                M3EGroupRow(
                    title = "AVB 链式分区与 fstab",
                    subtitle = "${snap.vbmetaChainedPartitions.size} 个链式分区 · ${snap.fstabAvbKeys.size} 个 avb_keys",
                    icon = Icons.Rounded.Link,
                    shape = m3eGroupShape(5, 6),
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
}
