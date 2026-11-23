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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.FreezerDetector
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.data.KeyEntry
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.AddKeyBottomSheet
import io.github.fu.avbforge.ui.components.BackupPartitionsSheet
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EHelpButton
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.m3eGroupShape

/**
 * Clean Tools Tab:
 * 1. Key Vault (Generate / Import PEM / Export avb_pkmd.bin / Set Default / Delete)
 * 2. Custom Partition Backup & System Maintenance (Battery Optimization, Engine Logs)
 * 3. Low-Level Read-Only AVB Chain & fstab Diagnostics
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onOpenLogs: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snap = state.snapshot
    val activeSlot = snap?.activeSlot?.ifBlank { "_b" } ?: "_b"
    val isYellow = snap?.verifiedBootState.equals("yellow", ignoreCase = true)

    var showAddKeySheet by remember { mutableStateOf(false) }
    var activeKeyForSheet by remember { mutableStateOf<KeyEntry?>(null) }
    var pendingDeleteKey by remember { mutableStateOf<KeyEntry?>(null) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var expandAvbInspector by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Section 1: AVB Key Vault
        M3ESectionLabel("AVB 密钥保险箱（${state.keys.size}）", helpDoc = HelpDocs.PKMD_EXPORT)
        val keyRowCount = state.keys.size + 1
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.keys.forEachIndexed { idx, key ->
                val matchesDevice = snap?.vbmetaPubkeySha1.equals(key.sha1, ignoreCase = true)
                val tag = buildString {
                    append("RSA-${key.bits}")
                    if (key.isDefault) append(" · 默认")
                    if (matchesDevice) append(" · 匹配本机")
                }
                M3EGroupRow(
                    title = key.alias,
                    subtitle = "$tag · ${key.sha1.take(16)}...",
                    icon = Icons.Rounded.Key,
                    iconTint = if (key.isDefault) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconBg = if (key.isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = m3eGroupShape(idx, keyRowCount),
                    showChevron = true,
                    onClick = { activeKeyForSheet = key },
                )
            }
            M3EGroupRow(
                title = "生成新密钥 / 导入标准 PEM",
                subtitle = "私钥通过 Android Keystore (AES-256-GCM) 加密托管",
                icon = Icons.Rounded.Add,
                iconTint = MaterialTheme.colorScheme.primary,
                iconBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                shape = m3eGroupShape(state.keys.size, keyRowCount),
                showChevron = true,
                onClick = { showAddKeySheet = true },
            )
        }

        // Section 2: Partition Backup & Utilities
        M3ESectionLabel("分区备份与系统维护", helpDoc = HelpDocs.BACKUP_PARTITIONS)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "自定义分区备份",
                subtitle = "当前槽位 $activeSlot · 支持按需勾选任意引导分区并查看真实体积",
                icon = Icons.Rounded.Backup,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconBg = MaterialTheme.colorScheme.secondaryContainer,
                shape = m3eGroupShape(0, 3),
                showChevron = true,
                onClick = { if (!state.busy) showBackupSheet = true },
            )
            M3EGroupRow(
                title = "OriginOS 防后台冻结设置",
                subtitle = "打开电池优化，建议将 AVBForge 与 KernelSU 设为「允许后台高耗电」",
                icon = Icons.Rounded.AcUnit,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                shape = m3eGroupShape(1, 3),
                showChevron = true,
                helpDoc = HelpDocs.FREEZER_ORIGINOS,
                onClick = { FreezerDetector.openBatteryOptimizationSettings(ctx) },
            )
            M3EGroupRow(
                title = "实时引擎日志（${state.logs.size} 条）",
                subtitle = "查看 Rust AVB/Boot 引擎的详细执行输出与回读校验记录",
                icon = Icons.Rounded.Terminal,
                shape = m3eGroupShape(2, 3),
                showChevron = true,
                onClick = onOpenLogs,
            )
        }

        // Section 3: Low-Level Read-Only Diagnostics
        if (snap != null) {
            M3ESectionLabel("底层只读诊断", helpDoc = HelpDocs.AVB_CHAIN_FSTAB)
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .clickable { expandAvbInspector = !expandAvbInspector },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AVB 链式分区与 fstab 详情", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "VNDK ${snap.vndkVersion} · SDK ${snap.sdkInt} · ${snap.kernelRelease.take(28)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (expandAvbInspector) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(
                        visible = expandAvbInspector,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            snap.vbmetaPubkeySha1?.let { sha ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("vbmeta 公钥 SHA-1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            M3EHelpButton(doc = HelpDocs.VBMETA_KEY)
                                        }
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
                            Text(
                                "vbmeta 链式验证分区（${snap.vbmetaChainedPartitions.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                snap.vbmetaChainedPartitions.forEach { M3EPillChip(text = it) }
                            }
                            Text(
                                "fstab avb_keys 白名单（${snap.fstabAvbKeys.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                snap.fstabAvbKeys.forEach { M3EPillChip(text = it) }
                            }
                            if (snap.initBootAvbpubkeys.isNotEmpty()) {
                                Text(
                                    "当前 init_boot 已注入的 DSU 公钥",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    snap.initBootAvbpubkeys.forEach {
                                        M3EPillChip(
                                            text = it,
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    // Key Action Sheet
    activeKeyForSheet?.let { key ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val matchesDevice = snap?.vbmetaPubkeySha1.equals(key.sha1, ignoreCase = true)
        ModalBottomSheet(
            onDismissRequest = { activeKeyForSheet = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${key.alias} · RSA-${key.bits}", style = MaterialTheme.typography.titleLarge)
                    if (key.isDefault) {
                        M3EPillChip(
                            text = "默认",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (matchesDevice) {
                        M3EPillChip(
                            text = "匹配本机",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    text = "SHA-1: ${key.sha1}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val actionCount = if (key.isDefault) 3 else 4
                var idx = 0
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    M3EGroupRow(
                        title = "复制完整公钥 SHA-1",
                        icon = Icons.Rounded.ContentCopy,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(idx++, actionCount),
                        onClick = {
                            clipboard.setText(AnnotatedString(key.sha1))
                            activeKeyForSheet = null
                            onNotify("已复制公钥 SHA-1")
                        },
                    )
                    M3EGroupRow(
                        title = "导出 avb_pkmd.bin 公钥",
                        subtitle = "保存到 Download/AVBForge",
                        icon = Icons.Rounded.Download,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(idx++, actionCount),
                        onClick = {
                            vm.exportPkmdToDownloads(key)
                            activeKeyForSheet = null
                            onNotify("已导出 avb_pkmd.bin")
                        },
                    )
                    if (!key.isDefault) {
                        M3EGroupRow(
                            title = "设为默认签名密钥",
                            icon = Icons.Rounded.Star,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = m3eGroupShape(idx++, actionCount),
                            onClick = {
                                vm.setDefaultKey(key.id)
                                activeKeyForSheet = null
                                onNotify("已设「${key.alias}」为默认密钥")
                            },
                        )
                    }
                    M3EGroupRow(
                        title = "删除此密钥",
                        icon = Icons.Rounded.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        iconBg = MaterialTheme.colorScheme.errorContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(idx, actionCount),
                        onClick = {
                            activeKeyForSheet = null
                            pendingDeleteKey = key
                        },
                    )
                }
            }
        }
    }

    if (showAddKeySheet) {
        AddKeyBottomSheet(
            busy = state.busy,
            onGenerate = { alias, bits ->
                vm.generateKey(alias, bits)
                showAddKeySheet = false
            },
            onImport = { alias, path, pass ->
                vm.importStandardPem(alias, path, pass)
                showAddKeySheet = false
            },
            onPickFile = { uri -> UriPathResolver.resolveFileUri(ctx, uri) },
            onDismiss = { showAddKeySheet = false },
        )
    }

    if (showBackupSheet) {
        BackupPartitionsSheet(
            activeSlot = activeSlot,
            defaultSelectAll = !isYellow,
            liveSizes = snap?.partitionSizes.orEmpty(),
            busy = state.busy,
            onStartBackup = { chosenSlot, selectedParts ->
                showBackupSheet = false
                vm.backupPartitions(slot = chosenSlot, partitions = selectedParts)
            },
            onDismiss = { showBackupSheet = false },
        )
    }

    pendingDeleteKey?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteKey = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("删除密钥「${target.alias}」？") },
            text = { Text("删除后无法恢复，请确保已保留原 PEM 备份。") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteKey(target.id)
                        pendingDeleteKey = null
                        onNotify("已删除密钥")
                    },
                    shape = CircleShape,
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteKey = null }) { Text("取消") }
            },
        )
    }
}
