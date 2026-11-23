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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.FreezerDetector
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EHelpButton
import io.github.fu.avbforge.ui.components.M3EHeroCard
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3ESectionLabel

/**
 * Pure Read-Only Home Dashboard:
 * Card 1: Self-Signed System Status (with prominent A/B Active Slot badge)
 * Card 2: DSU Status & Pubkey Readiness
 * Compact Read-Only Spec Summary + Collapsible Low-Level AVB/fstab Inspector
 */
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
    var expandAdvanced by remember { mutableStateOf(false) }

    val vbs = snap?.verifiedBootState?.lowercase().orEmpty()
    val isYellow = vbs == "yellow"
    val isNeo9 = snap?.let { SafetyGate.isSupportedNeo9(it) } ?: true
    val activeSlot = snap?.activeSlot?.ifBlank { "_a" } ?: ""
    val activeLetter = activeSlot.trimStart('_').uppercase()
    val inactiveLetter = if (activeSlot == "_b") "A" else "B"
    val dsuReady = snap?.initBootAvbpubkeys?.isNotEmpty() == true
    val defaultKey = vm.vault.getDefaultKey()
    val keyMatchesVbmeta = defaultKey != null &&
        defaultKey.sha1.equals(snap?.vbmetaPubkeySha1, ignoreCase = true)

    val sysHeroBg = when {
        snap == null -> MaterialTheme.colorScheme.surfaceContainerHigh
        !isNeo9 || snap.sdkInt >= 35 -> MaterialTheme.colorScheme.errorContainer
        isYellow -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val sysHeroFg = when {
        snap == null -> MaterialTheme.colorScheme.onSurface
        !isNeo9 || snap.sdkInt >= 35 -> MaterialTheme.colorScheme.onErrorContainer
        isYellow -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Self-Signed System Status Card (Pure Read-Only)
        M3EHeroCard(
            title = when {
                snap == null -> "等待 Root 授权"
                !isNeo9 -> "非目标机型"
                snap.sdkInt >= 35 -> "高版本固件警示"
                isYellow -> "自签名系统运行中"
                vbs == "green" -> "原厂系统"
                else -> "AVB: ${snap.verifiedBootState}"
            },
            subtitle = if (snap == null) {
                "请在 KernelSU 授予权限后点右上角刷新"
            } else {
                "${snap.manufacturer} ${snap.model} · Android ${snap.androidRelease}"
            },
            icon = when {
                snap == null -> Icons.Rounded.Security
                !isNeo9 || snap.sdkInt >= 35 -> Icons.Rounded.WarningAmber
                isYellow -> Icons.Rounded.CheckCircle
                else -> Icons.Rounded.Shield
            },
            containerColor = sysHeroBg,
            contentColor = sysHeroFg,
            badgeText = snap?.let { "${it.verifiedBootState.uppercase()} · ${it.deviceState.uppercase()}" },
            isPulsing = state.busy,
        ) {
            if (snap != null && activeLetter.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = CircleShape, color = sysHeroFg) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = sysHeroBg, modifier = Modifier.size(16.dp))
                            Text(
                                text = "活动槽位 $activeLetter ($activeSlot)",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = sysHeroBg,
                            )
                        }
                    }
                    Surface(shape = CircleShape, color = sysHeroFg.copy(alpha = 0.14f)) {
                        Text(
                            text = "备用 $inactiveLetter",
                            style = MaterialTheme.typography.labelMedium,
                            color = sysHeroFg,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    M3EHelpButton(doc = HelpDocs.ACTIVE_SLOT, tint = sysHeroFg.copy(alpha = 0.85f))
                }
            }
        }

        // 2. DSU Status Card (Pure Read-Only)
        if (snap != null) {
            val dsuBg = if (dsuReady) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            val dsuFg = if (dsuReady) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            M3EHeroCard(
                title = if (dsuReady) "DSU 自签名已就绪" else "DSU 尚未适配",
                subtitle = if (dsuReady) {
                    "已注入 ${snap.initBootAvbpubkeys.first().substringAfterLast('/')} · 可直接体验 GSI"
                } else {
                    "尚未注入公钥 · 前往「DSU」页一键启用"
                },
                icon = Icons.Rounded.SystemUpdate,
                containerColor = dsuBg,
                contentColor = dsuFg,
                badgeText = "DSU: ${snap.dsuStatus}",
            )
        }

        // Alerts ONLY when abnormal
        if (snap != null && !isNeo9) {
            M3EGroupRow(
                title = "非 iQOO Neo9 机型警示",
                subtitle = "本应用仅针对 iQOO Neo9 (V2338A/PD2338) Android 14 验证，切勿在其他机型刷写分区。",
                icon = Icons.Rounded.WarningAmber,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBg = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        }
        if (snap != null && snap.sdkInt >= 35) {
            M3EGroupRow(
                title = "Android ${snap.androidRelease} 高风险固件",
                subtitle = "高版本固件 BL 可能已收紧 avb_custom_key 链路，请先核实兼容再刷写。",
                icon = Icons.Rounded.WarningAmber,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBg = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        }
        if (snap?.freezerEnabled == true && !FreezerDetector.isIgnoringBatteryOptimizations(ctx)) {
            M3EGroupRow(
                title = "检测到 OriginOS 后台冻结",
                subtitle = "点击前往电池优化，建议将 AVBForge 与 KernelSU 设为「允许后台高耗电」",
                icon = Icons.Rounded.AcUnit,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                showChevron = true,
                helpDoc = HelpDocs.FREEZER_ORIGINOS,
                onClick = { FreezerDetector.openBatteryOptimizationSettings(ctx) },
            )
        }

        // 3. Compact Read-Only Key-Value Summary (Airy & Uncluttered)
        if (snap != null) {
            M3ESectionLabel("核心状态")
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    InfoKVRow(
                        label = "KernelSU",
                        value = snap.ksuVersion?.substringBefore(" (") ?: if (snap.initBootHasKsu == true) "已注入" else "未安装",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    InfoKVRow(
                        label = "密钥匹配",
                        value = when {
                            defaultKey == null -> "未导入"
                            keyMatchesVbmeta -> "与本机一致"
                            else -> "与本机不同"
                        },
                        helpDoc = HelpDocs.VBMETA_KEY,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    InfoKVRow(
                        label = "DSU 公钥",
                        value = if (dsuReady) "已注入" else "未注入",
                        helpDoc = HelpDocs.DSU_PUBKEY,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    InfoKVRow(
                        label = "底层环境",
                        value = "VNDK ${snap.vndkVersion} · SDK ${snap.sdkInt}",
                    )
                }
            }

            // 4. Collapsible Low-Level Technical Details
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .clickable { expandAdvanced = !expandAdvanced },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text("底层 AVB 链与 fstab 详情", style = MaterialTheme.typography.titleMedium)
                        M3EHelpButton(doc = HelpDocs.AVB_CHAIN_FSTAB)
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (expandAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(
                        visible = expandAdvanced,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            snap.vbmetaPubkeySha1?.let { sha ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("vbmeta 公钥 SHA-1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(sha, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                                    }
                                    IconButton(onClick = {
                                        clipboard.setText(AnnotatedString(sha))
                                        onNotify("已复制 vbmeta 公钥 SHA-1")
                                    }) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Text("vbmeta 链式验证分区（${snap.vbmetaChainedPartitions.size}）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                snap.vbmetaChainedPartitions.forEach { M3EPillChip(text = it) }
                            }
                            Text("fstab avb_keys 白名单（${snap.fstabAvbKeys.size}）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                snap.fstabAvbKeys.forEach { M3EPillChip(text = it) }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun InfoKVRow(
    label: String,
    value: String,
    helpDoc: io.github.fu.avbforge.ui.HelpDoc? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (helpDoc != null) {
                M3EHelpButton(doc = helpDoc)
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
