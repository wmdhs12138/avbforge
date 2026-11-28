package io.github.fu.avbforge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.ui.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceInfoScreen(
    state: UiState,
) {
    val snap = state.snapshot
    val isYellow = snap?.verifiedBootState.equals("yellow", ignoreCase = true)
    val activeSlot = snap?.activeSlot?.ifBlank { "_b" } ?: "--"
    val deviceState = snap?.deviceState?.replaceFirstChar { it.uppercase() }?.ifBlank { "--" } ?: "--"
    val widevine = snap?.widevineLevel?.ifBlank { "Unknown" } ?: "--"
    val dsuInfo = state.dsuInfo

    var principleExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Unified Hero Card (Model + Verified Boot + 3-col Slot / Bootloader / Widevine)
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                    MaterialTheme.colorScheme.surfaceContainerLowest,
                                ),
                            ),
                        )
                        .padding(18.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (snap != null) "${snap.manufacturer} ${snap.model}".trim() else "等待设备检测",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = snap?.device?.ifBlank { "V2338A / PD2338" } ?: "--",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (isYellow) Color(0xFF3D2E08) else MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = if (isYellow) "YELLOW 自签名" else "${snap?.verifiedBootState?.uppercase()?.ifBlank { "GREEN" } ?: "GREEN"} 原厂",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = if (isYellow) Color(0xFFFFD97D) else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HeroMetricTile("启动槽位", activeSlot, Modifier.weight(1f))
                            HeroMetricTile("Bootloader", deviceState, Modifier.weight(1f))
                            HeroMetricTile("Widevine", widevine, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Group 1: System & Kernel (SPL and VNDK shown as separate rows)
        item {
            SectionCaption("系统与内核")
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    InfoKeyValueRow(
                        icon = Icons.Rounded.Memory,
                        label = "安卓版本",
                        value = if (snap != null) "Android ${snap.androidRelease} (SDK ${snap.sdkInt})" else "--",
                    )
                    RowDivider()
                    InfoKeyValueRow(
                        icon = Icons.Rounded.SystemUpdate,
                        label = "固件版本",
                        value = snap?.firmwareVersion?.ifBlank { "--" } ?: "--",
                        mono = true,
                    )
                    RowDivider()
                    InfoKeyValueRow(
                        icon = Icons.Rounded.Terminal,
                        label = "内核版本",
                        value = snap?.kernelRelease?.ifBlank { "--" } ?: "--",
                        mono = true,
                    )
                    RowDivider()
                    InfoKeyValueRow(
                        icon = Icons.Rounded.Security,
                        label = "安全补丁",
                        value = snap?.securityPatch?.ifBlank { "--" } ?: "--",
                        mono = true,
                    )
                    RowDivider()
                    InfoKeyValueRow(
                        icon = Icons.Rounded.Layers,
                        label = "VNDK",
                        value = snap?.vndkVersion?.takeIf { it.isNotBlank() }?.let { "Vendor $it" } ?: "--",
                        mono = true,
                    )
                }
            }
        }

        // Group 2: AVB & DSU
        item {
            SectionCaption("AVB 与 DSU")
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    val rootName = snap?.rootProvider?.ifBlank { null }
                        ?: snap?.ksuVersion?.substringBefore(" (")?.let { "KernelSU $it" }
                        ?: if (snap != null) "Root" else "未授权"
                    val isTempRoot = snap?.rootIsTemporary == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text("Root 状态", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = rootName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isTempRoot) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF3D2E08),
                                ) {
                                    Text(
                                        text = "临时",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color(0xFFFFD97D),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }

                    RowDivider()
                    val dsuSummary = when {
                        !isYellow -> "未解锁"
                        dsuInfo?.installed == true && dsuInfo.enabled ->
                            if (dsuInfo.oneShot) "已启用 · 单次 (-s)" else "已启用 · 持续"
                        dsuInfo?.installed == true -> "已安装 · 已停用"
                        else -> "未安装"
                    }
                    InfoKeyValueRow(
                        icon = Icons.Rounded.Storage,
                        label = "DSU 状态",
                        value = dsuSummary,
                    )

                    RowDivider()
                    InfoKeyValueRow(
                        icon = Icons.Rounded.Fingerprint,
                        label = "vbmeta 指纹",
                        value = snap?.vbmetaPubkeySha1?.take(16)?.let { "$it..." } ?: "--",
                        mono = true,
                    )

                    RowDivider()
                    InfoKeyValueRow(
                        icon = Icons.Rounded.VerifiedUser,
                        label = "DSU 公钥",
                        value = snap?.initBootAvbpubkeys?.firstOrNull()?.ifBlank { null }
                            ?: snap?.fstabAvbKeys?.firstOrNull()
                            ?: "未注入",
                        mono = true,
                    )

                    RowDivider()
                    val se = snap?.selinuxMode?.ifBlank { "Unknown" } ?: "--"
                    val rb = snap?.vbmetaRollbackIndex?.let { "RB=$it" } ?: "RB=--"
                    InfoKeyValueRow(
                        icon = Icons.Rounded.Shield,
                        label = "SELinux / 回滚",
                        value = "$se · $rb",
                        mono = true,
                    )
                }
            }
        }

        // Collapsible PD2338 avb_custom_key Principle Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { principleExpanded = !principleExpanded }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "本机 avb_custom_key 原理",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Icon(
                            imageVector = if (principleExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(visible = principleExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(4.dp))

                            // Stage 1: devinfo
                            PrincipleNodeCard(stepNum = "1", title = "devinfo 分区 (4096 B)") {
                                Text(
                                    text = "0x0D is_unlocked=0 → 不触发 unlock2 拦截\n0x94 / 0x98 写入 520B user key (avb_pkmd.bin)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            FlowArrow()

                            // Stage 2: ABL 3-way branch
                            PrincipleNodeCard(stepNum = "2", title = "ABL 公钥校验分支") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AblBranchChip("GREEN", "OEM 公钥", !isYellow, Modifier.weight(1f))
                                    AblBranchChip("YELLOW", "devinfo key", isYellow, Modifier.weight(1f))
                                    AblBranchChip("RED", "拒启", false, Modifier.weight(1f), isError = true)
                                }
                            }

                            FlowArrow()

                            // Stage 3: vbmeta chain
                            PrincipleNodeCard(stepNum = "3", title = "vbmeta 链（只存公钥，不存 Hash）") {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    listOf("boot", "init_boot", "vendor_boot", "dtbo", "recovery", "vbmeta_system").forEach { part ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (part == "init_boot") {
                                                MaterialTheme.colorScheme.tertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLowest
                                            },
                                        ) {
                                            Text(
                                                text = part,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = if (part == "init_boot") {
                                                    MaterialTheme.colorScheme.onTertiaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "内容 Hash 在各自 footer 里 → 换 init_boot 只需同钥重签自身，主 vbmeta 不用动",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionCaption(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun InfoKeyValueRow(
    icon: ImageVector,
    label: String,
    value: String,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun PrincipleNodeCard(
    stepNum: String,
    title: String,
    body: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(20.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stepNum,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
            Column(
                modifier = Modifier.padding(start = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                body()
            }
        }
    }
}

@Composable
private fun FlowArrow() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Rounded.ArrowDownward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AblBranchChip(
    title: String,
    subtitle: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val bg = when {
        active && title == "YELLOW" -> Color(0xFF3D2E08)
        active -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val fg = when {
        isError -> MaterialTheme.colorScheme.error
        active && title == "YELLOW" -> Color(0xFFFFD97D)
        active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = fg,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}
