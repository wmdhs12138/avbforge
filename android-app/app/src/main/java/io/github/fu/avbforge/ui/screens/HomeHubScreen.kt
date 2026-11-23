package io.github.fu.avbforge.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fu.avbforge.core.FreezerDetector
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.ActiveWizard
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EHelpButton
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.formatByteSize

private data class ScenarioEntry(
    val wizard: ActiveWizard,
    val title: String,
    val subtitle: String,
    val badge: String,
    val highlighted: Boolean,
    val icon: ImageVector,
)

/**
 * Clean Home Hub:
 * 1. Airy 4-Indicator Device Overview Card (Self-Signed State, Active Slot, KernelSU, DSU)
 * 2. Contextual Alerts ONLY when abnormal (Non-Neo9, A15+, OriginOS Freezer)
 * 3. State-Aware Scenario Launcher Cards opening dedicated full-screen step-by-step wizards
 */
@Composable
fun HomeHubScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onOpenWizard: (ActiveWizard) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snap = state.snapshot

    var hasAllFilesAccess by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val vbs = snap?.verifiedBootState?.lowercase().orEmpty()
    val isYellow = vbs == "yellow"
    val isNeo9 = snap?.let { SafetyGate.isSupportedNeo9(it) } ?: true
    val activeSlot = snap?.activeSlot?.ifBlank { "_a" } ?: ""
    val activeLetter = activeSlot.trimStart('_').uppercase()
    val dsuReady = snap?.initBootAvbpubkeys?.isNotEmpty() == true
    val hasKsu = snap?.initBootHasKsu == true || !snap?.ksuVersion.isNullOrBlank()
    val ksuShort = snap?.ksuVersion?.substringBefore(" (")?.ifBlank { null }
        ?: if (snap?.initBootHasKsu == true) "已注入" else "未安装"

    val defaultKey = vm.vault.getDefaultKey()
    val keyMatchesVbmeta = defaultKey != null &&
        defaultKey.sha1.equals(snap?.vbmetaPubkeySha1, ignoreCase = true)

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

    val dsuInfo = state.dsuInfo
    val dsuShortStatus = when {
        !dsuReady -> "未注入公钥"
        dsuInfo?.running == true -> "GSI 运行中"
        dsuInfo?.enabled == true -> if (dsuInfo.oneShot) "已启用 · 单次" else "已启用 · 持续"
        dsuInfo?.installed == true -> "已安装 · 未启用"
        snap?.dsuStatus?.contains("enabled", ignoreCase = true) == true -> "已启用 · 待重启"
        snap?.dsuStatus?.contains("installed", ignoreCase = true) == true -> "已安装 · 未启用"
        else -> "已就绪 · 未安装"
    }
    val dsuScenarioBadge = when {
        !dsuReady -> "首次适配"
        dsuInfo?.enabled == true -> "待重启进入"
        dsuInfo?.installed == true -> "已安装 GSI"
        else -> "已就绪"
    }
    val dsuScenarioSubtitle = when {
        !dsuReady -> "首次使用会先无损注入 DSU 公钥，再引导你签名并体验 GSI"
        dsuInfo?.enabled == true -> "GSI 已启用（占用 ${formatByteSize(dsuInfo.totalDiskBytes)}）· 点击一键重启、切换模式或清理"
        dsuInfo?.installed == true -> "已安装 GSI（占用 ${formatByteSize(dsuInfo.totalDiskBytes)}）· 点击一键启用、重置数据或安装新包"
        else -> "本机 DSU 已就绪 · 管理本地镜像、自动修复 SELinux 冲突并安装 GSI"
    }

    // State-aware smart ordering of scenarios
    val scenarios = if (isYellow) {
        listOf(
            ScenarioEntry(
                wizard = ActiveWizard.UPDATE_KSU,
                title = "无损更新 / 更换 KernelSU",
                subtitle = "自动剥离旧 KSU · 任选官版/Next/SukiSU · 保留 DSU 公钥 · 不清数据",
                badge = if (hasKsu) "常用推荐" else "安装 KSU",
                highlighted = true,
                icon = Icons.Rounded.Memory,
            ),
            ScenarioEntry(
                wizard = ActiveWizard.DSU_STUDIO,
                title = "GSI / DSU 双系统管理",
                subtitle = dsuScenarioSubtitle,
                badge = dsuScenarioBadge,
                highlighted = true,
                icon = Icons.Rounded.SystemUpdate,
            ),
            ScenarioEntry(
                wizard = ActiveWizard.FIRST_TIME_SELF_SIGN,
                title = "重建全套自签名 / 更换密钥",
                subtitle = "当前已是 Yellow 自签名；仅在更换新私钥或重新签全部分区时需要",
                badge = "已完成",
                highlighted = false,
                icon = Icons.Rounded.VerifiedUser,
            ),
        )
    } else {
        listOf(
            ScenarioEntry(
                wizard = ActiveWizard.FIRST_TIME_SELF_SIGN,
                title = "首次迁移到自签名系统（含 KSU）",
                subtitle = "分步引导备份原厂 7 分区、配置密钥、协同修补 KSU 并安全全链写入",
                badge = "首次必走 · 会清数据",
                highlighted = true,
                icon = Icons.Rounded.VerifiedUser,
            ),
            ScenarioEntry(
                wizard = ActiveWizard.DSU_STUDIO,
                title = "体验第三方 GSI（DSU 双系统）",
                subtitle = "无需解锁 BL，单次启动防变砖；需先完成自签名并注入 DSU 公钥",
                badge = if (dsuReady) "已就绪" else "需先自签名",
                highlighted = false,
                icon = Icons.Rounded.SystemUpdate,
            ),
            ScenarioEntry(
                wizard = ActiveWizard.UPDATE_KSU,
                title = "无损更新 / 更换 KernelSU",
                subtitle = "适用于已进入 Yellow 自签名后的日常 KSU 升级或分支切换",
                badge = "需先自签名",
                highlighted = false,
                icon = Icons.Rounded.Memory,
            ),
        )
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Airy Device Status Hub Card
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = heroBg,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = heroFg.copy(alpha = 0.14f),
                            modifier = Modifier.size(46.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = when {
                                        snap == null -> Icons.Rounded.Security
                                        !isNeo9 || snap.sdkInt >= 35 -> Icons.Rounded.WarningAmber
                                        isYellow -> Icons.Rounded.CheckCircle
                                        else -> Icons.Rounded.Shield
                                    },
                                    contentDescription = null,
                                    tint = heroFg,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = when {
                                    snap == null -> "等待 Root 权限"
                                    !isNeo9 -> "非目标机型"
                                    snap.sdkInt >= 35 -> "高版本固件警示"
                                    isYellow -> "自签名系统运行中"
                                    vbs == "green" -> "原厂系统（未自签名）"
                                    else -> "AVB: ${snap.verifiedBootState}"
                                },
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = heroFg,
                            )
                            Text(
                                text = if (snap == null) {
                                    "请授予本应用 Root 权限后点右上角刷新"
                                } else {
                                    "${snap.manufacturer} ${snap.model} · Android ${snap.androidRelease}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = heroFg.copy(alpha = 0.82f),
                            )
                        }
                    }
                }

                // 4 Crisp Status Indicators (2x2 clean grid)
                if (snap != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusMiniTile(
                                label = "AVB / 锁状态",
                                value = "${snap.verifiedBootState.uppercase()} · ${snap.deviceState.uppercase()}",
                                fg = heroFg,
                                modifier = Modifier.weight(1f),
                            )
                            StatusMiniTile(
                                label = "当前活动槽位",
                                value = if (activeLetter.isNotEmpty()) "槽位 $activeLetter ($activeSlot)" else "未知",
                                fg = heroFg,
                                highlight = true,
                                bg = heroFg,
                                highlightText = heroBg,
                                trailingIcon = Icons.Rounded.SwapHoriz,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusMiniTile(
                                label = "KernelSU Root",
                                value = ksuShort,
                                fg = heroFg,
                                modifier = Modifier.weight(1f),
                            )
                            StatusMiniTile(
                                label = "DSU 双系统",
                                value = dsuShortStatus,
                                fg = heroFg,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Key match status subtle pill row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = when {
                                defaultKey == null -> "密钥库：尚未导入/生成默认私钥"
                                keyMatchesVbmeta -> "密钥库：「${defaultKey.alias}」与本机 vbmeta 一致"
                                else -> "密钥库：「${defaultKey.alias}」与本机 vbmeta 不一致"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = heroFg.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        M3EHelpButton(doc = HelpDocs.ACTIVE_SLOT, tint = heroFg.copy(alpha = 0.85f))
                    }
                }
            }
        }

        // 2. Contextual Alerts (ONLY when abnormal)
        if (!hasAllFilesAccess) {
            M3EGroupRow(
                title = "请授予「所有文件访问权限」",
                subtitle = "用于直接读写 Download 中的私钥、分区备份和大体积 GSI 镜像；点击一键前往授权。",
                icon = Icons.Rounded.FolderOpen,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                iconBg = MaterialTheme.colorScheme.primaryContainer,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                showChevron = true,
                onClick = {
                    val pkgIntent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(pkgIntent) }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                },
            )
        }
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
                subtitle = "实测高版本固件 Bootloader 可能已收紧 avb_custom_key 链路，刷写前请务必确认兼容。",
                icon = Icons.Rounded.WarningAmber,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBg = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        }
        if (snap?.freezerEnabled == true && !FreezerDetector.isIgnoringBatteryOptimizations(ctx)) {
            M3EGroupRow(
                title = "建议关闭 OriginOS 后台冻结",
                subtitle = "点击前往电池优化，将 AVBForge 与 KernelSU 设为「允许后台高耗电」，避免签名中断或卡死。",
                icon = Icons.Rounded.AcUnit,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                showChevron = true,
                helpDoc = HelpDocs.FREEZER_ORIGINOS,
                onClick = { FreezerDetector.openBatteryOptimizationSettings(ctx) },
            )
        }

        // 3. State-Aware Scenario Launchers
        M3ESectionLabel(
            text = if (isYellow) "常用向导 · 点击开始分步引导" else "开始使用 · 选择你的目标",
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            scenarios.forEach { entry ->
                ScenarioCard(
                    entry = entry,
                    onClick = { onOpenWizard(entry.wizard) },
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun StatusMiniTile(
    label: String,
    value: String,
    fg: Color,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    bg: Color = fg.copy(alpha = 0.10f),
    highlightText: Color = fg,
    trailingIcon: ImageVector? = null,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (highlight) bg else fg.copy(alpha = 0.09f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = (if (highlight) highlightText else fg).copy(alpha = 0.72f),
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (trailingIcon != null) {
                    Icon(
                        trailingIcon,
                        contentDescription = null,
                        tint = if (highlight) highlightText else fg,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (highlight) highlightText else fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ScenarioCard(
    entry: ScenarioEntry,
    onClick: () -> Unit,
) {
    val containerColor = if (entry.highlighted) {
        MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val iconBg = if (entry.highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val iconTint = if (entry.highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = containerColor,
        tonalElevation = if (entry.highlighted) 1.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = iconBg,
                modifier = Modifier.size(50.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(entry.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(25.dp))
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (entry.highlighted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ) {
                        Text(
                            text = entry.badge,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = if (entry.highlighted) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
