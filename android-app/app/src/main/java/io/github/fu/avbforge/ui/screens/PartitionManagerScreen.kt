package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.data.KeyEntry
import io.github.fu.avbforge.data.LocalPartitionBackup
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.AddKeyBottomSheet
import io.github.fu.avbforge.ui.components.DryRunCommandDialog
import io.github.fu.avbforge.ui.components.formatByteSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PartitionMeta(val name: String, val role: String, val fallbackSize: String)

private val ALL_TARGET_PARTITIONS = listOf(
    PartitionMeta("devinfo", "锁态与 user key (0x94/0x98)", "4 KB"),
    PartitionMeta("vbmeta", "顶层信任根", "64 KB"),
    PartitionMeta("vbmeta_system", "逻辑分区链", "64 KB"),
    PartitionMeta("init_boot", "Ramdisk", "8 MB"),
    PartitionMeta("boot", "内核镜像", "96 MB"),
    PartitionMeta("vendor_boot", "Vendor Ramdisk", "96 MB"),
    PartitionMeta("dtbo", "设备树覆盖", "24 MB"),
    PartitionMeta("recovery", "恢复模式", "100 MB"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartitionManagerScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snap = state.snapshot
    val activeSlot = snap?.activeSlot?.ifBlank { "_b" } ?: "_b"
    val defaultKey = state.keys.firstOrNull { it.isDefault } ?: state.keys.firstOrNull()

    var autoSignAndVerify by remember { mutableStateOf(true) }
    var partitionsExpanded by remember { mutableStateOf(false) }

    // Modals & sheets
    var showAddKeySheet by remember { mutableStateOf(false) }
    var showKeyVaultSheet by remember { mutableStateOf(false) }
    var pendingDeleteKey by remember { mutableStateOf<KeyEntry?>(null) }

    var showRootBootMenu by remember { mutableStateOf(false) }
    var showRootUpdateDialog by remember { mutableStateOf(false) }
    var rootPatchedPath by remember { mutableStateOf("") }

    var showCustomBootDialog by remember { mutableStateOf(false) }
    var customBootPart by remember { mutableStateOf("init_boot") }
    var customBootPath by remember { mutableStateOf("") }

    var showFullChainDialog by remember { mutableStateOf(false) }
    var fullChainStockDir by remember { mutableStateOf(state.localPartitionBackups.firstOrNull()?.path ?: "") }
    var fullChainWriteDevinfo by remember { mutableStateOf(true) }

    var showNewBackupMenu by remember { mutableStateOf(false) }
    var pendingBackupSpec by remember { mutableStateOf<Pair<String, List<String>>?>(null) } // defaultName -> parts
    var backupNameInput by remember { mutableStateOf("") }

    var activeBackupItem by remember { mutableStateOf<LocalPartitionBackup?>(null) }
    var pendingRenameBackup by remember { mutableStateOf<LocalPartitionBackup?>(null) }
    var renameBackupInput by remember { mutableStateOf("") }
    var pendingDeleteBackup by remember { mutableStateOf<LocalPartitionBackup?>(null) }

    var activeSinglePart by remember { mutableStateOf<PartitionMeta?>(null) }
    var singlePartFlashTarget by remember { mutableStateOf<String?>(null) }

    // Dry-run confirmation state: (title, summary, commands, onConfirm)
    var dryRunRequest by remember { mutableStateOf<DryRunPayload?>(null) }

    // Pickers
    val rootImgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                rootPatchedPath = it
                onNotify("已选择：${File(it).name}")
            }
        }
    }
    val customImgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                customBootPath = it
                onNotify("已选择：${File(it).name}")
            }
        }
    }
    val singlePartImgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val part = singlePartFlashTarget
        if (uri != null && part != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let { imgPath ->
                val imgName = File(imgPath).name
                dryRunRequest = DryRunPayload(
                    title = "刷前预演（${part}$activeSlot）",
                    summary = "私钥「${defaultKey?.alias ?: "默认"}」· ${if (autoSignAndVerify) "自动签名并离线验签" else "仅离线验签"} · 刷后回读校验",
                    commands = listOf(
                        if (autoSignAndVerify) "avbforge-core avb add-hash-footer --partition-name $part --image $imgName" else "avbforge-core avb verify --image $imgName",
                        "su -c 'avbforge-core flash-partition --partition $part --slot $activeSlot --image $imgName --fsync'",
                    ),
                    onConfirm = {
                        vm.resignAndFlashSinglePartition(
                            partition = part,
                            imagePath = imgPath,
                            autoSign = autoSignAndVerify,
                            targetSlot = activeSlot,
                        )
                    },
                )
            }
        }
    }
    val stockDirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveTreeUri(uri)?.let {
                fullChainStockDir = it
                onNotify("已选择目录：${File(it).name}")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Unified Hero Card: Active Signing Key
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = defaultKey?.alias ?: "尚未配置私钥",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "默认签名私钥 · ${state.keys.size} 把可用",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (defaultKey != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        text = "RSA-${defaultKey.bits}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }

                        if (defaultKey != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                    val matchesDevice = !snap?.vbmetaPubkeySha1.isNullOrBlank() &&
                                        defaultKey.sha1.equals(snap?.vbmetaPubkeySha1, ignoreCase = true)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "公钥 SHA-1 指纹",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (!snap?.vbmetaPubkeySha1.isNullOrBlank()) {
                                            Text(
                                                text = if (matchesDevice) "✓ 与本机 vbmeta 一致" else "⚠ 与本机不一致",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (matchesDevice) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                    Text(
                                        text = defaultKey.sha1,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            HeroActionMiniButton(Icons.Rounded.Add, "生成", Modifier.weight(1f)) { showAddKeySheet = true }
                            HeroActionMiniButton(Icons.Rounded.UploadFile, "导入", Modifier.weight(1f)) { showAddKeySheet = true }
                            HeroActionMiniButton(Icons.Rounded.Share, "导出", Modifier.weight(1f)) {
                                defaultKey?.let {
                                    vm.exportPkmdToDownloads(it)
                                    onNotify("已导出 ${it.alias}_avb_pkmd.bin")
                                } ?: onNotify("请先生成或导入私钥")
                            }
                            HeroActionMiniButton(Icons.Rounded.Tune, "管理", Modifier.weight(1f)) { showKeyVaultSheet = true }
                        }
                    }
                }
            }
        }

        // Unified 2-Column Primary Action Bar: Real Self-Signed Workflows
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { showRootBootMenu = true },
                    enabled = !state.busy && defaultKey != null,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Root / Boot", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }

                Button(
                    onClick = {
                        if (fullChainStockDir.isBlank()) {
                            fullChainStockDir = state.localPartitionBackups.firstOrNull()?.path ?: ""
                        }
                        showFullChainDialog = true
                    },
                    enabled = !state.busy && defaultKey != null,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("全链自签名", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Grouped List 1: Partition Backup Management
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "分区备份管理 (AVBForge)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "+ 新建备份",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = !state.busy) { showNewBackupMenu = true },
                    )
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.localPartitionBackups.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无本地分区备份",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column {
                            state.localPartitionBackups.forEachIndexed { idx, bkp ->
                                if (idx > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                                val dateStr = remember(bkp.lastModified) {
                                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(bkp.lastModified))
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeBackupItem = bkp }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = bkp.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${bkp.partitions.size} 分区 · ${formatByteSize(bkp.totalBytes)} · $dateStr",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Collapsible Advanced Single-Partition Card (devinfo + 7 chained)
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
                            .clickable { partitionsExpanded = !partitionsExpanded }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Column {
                                Text("底层分区 (devinfo + 7 链式)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "槽位 $activeSlot",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            imageVector = if (partitionsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(visible = partitionsExpanded) {
                        Column {
                            ALL_TARGET_PARTITIONS.forEach { part ->
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                val liveSize = snap?.partitionSizes?.firstOrNull { it.name == part.name }?.sizeBytes
                                val sizeText = if (liveSize != null) formatByteSize(liveSize) else part.fallbackSize
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeSinglePart = part }
                                        .padding(horizontal = 16.dp, vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = part.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                        )
                                        Text(
                                            text = part.role,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = sizeText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Flash Policy Switch
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("刷入时自动签名并离线验签", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                    }
                    Switch(checked = autoSignAndVerify, onCheckedChange = { autoSignAndVerify = it })
                }
            }
        }
    }

    // 1. Add Key Bottom Sheet
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

    // 2. Key Vault Management ModalBottomSheet
    if (showKeyVaultSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showKeyVaultSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("密钥库管理 (${state.keys.size})", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    IconButton(onClick = { showKeyVaultSheet = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }

                state.keys.forEach { k ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${k.alias} · RSA-${k.bits}", style = MaterialTheme.typography.titleSmall)
                                if (k.isDefault) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(
                                            text = "当前默认",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                } else {
                                    TextButton(onClick = {
                                        vm.setDefaultKey(k.id)
                                        onNotify("已切换默认私钥为「${k.alias}」")
                                    }) { Text("设为默认") }
                                }
                            }
                            Text(
                                text = "SHA-1: ${k.sha1}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(k.sha1))
                                    onNotify("已复制 SHA-1 指纹")
                                }) { Text("复制指纹") }
                                TextButton(onClick = {
                                    vm.exportPkmdToDownloads(k)
                                    onNotify("已导出 ${k.alias}_avb_pkmd.bin")
                                }) { Text("导出 pkmd") }
                                if (state.keys.size > 1) {
                                    TextButton(
                                        onClick = { pendingDeleteKey = k },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) { Text("删除") }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        showKeyVaultSheet = false
                        showAddKeySheet = true
                    },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("生成新密钥 / 导入 PEM")
                }
            }
        }
    }

    // Confirmed Key Deletion
    pendingDeleteKey?.let { keyToDel ->
        AlertDialog(
            onDismissRequest = { pendingDeleteKey = null },
            title = { Text("确认删除私钥「${keyToDel.alias}」？") },
            text = { Text("删除后无法再用此私钥重签或增量更新对应设备镜像，请确认已做好备份。") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteKey(keyToDel.id)
                        pendingDeleteKey = null
                        onNotify("已删除私钥「${keyToDel.alias}」")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteKey = null }) { Text("取消") } },
        )
    }

    // 3. Root / Boot Maintenance 2-Option Selector (Strictly NO DSU pubkey mixing!)
    if (showRootBootMenu) {
        AlertDialog(
            onDismissRequest = { showRootBootMenu = false },
            title = { Text("Root / Boot 维护") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showRootBootMenu = false
                                showRootUpdateDialog = true
                            },
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("1. 更新 / 更换 Root", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("导出纯净底包 → 管理器修补 → 重签单刷 init_boot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showRootBootMenu = false
                                showCustomBootDialog = true
                            },
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("2. 重签自改 Boot / Init_boot", style = MaterialTheme.typography.titleSmall)
                            Text("手动修改内核或镜像数据后，同钥重签并单刷", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRootBootMenu = false }) { Text("关闭") } },
        )
    }

    // 3a. Update / Switch Root Dialog
    if (showRootUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showRootUpdateDialog = false },
            title = { Text("更新 / 更换 Root") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            showRootUpdateDialog = false
                            vm.exportCleanInitBootForKsu(stripKsu = true)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("① 导出纯净 init_boot（剥离旧 Root）")
                    }
                    OutlinedButton(
                        onClick = { rootImgPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (rootPatchedPath.isBlank()) "② 选择修补后的 init_boot" else File(rootPatchedPath).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRootUpdateDialog = false
                        val imgName = File(rootPatchedPath).name
                        dryRunRequest = DryRunPayload(
                            title = "更新 Root 刷前预演",
                            summary = "私钥「${defaultKey?.alias ?: "默认"}」· 仅刷 init_boot$activeSlot（主 vbmeta 不动）",
                            commands = listOf(
                                "avbforge-core patch-dsu-key --input $imgName --inject-dsu-key false",
                                "su -c 'avbforge-core flash-partition --partition init_boot --slot $activeSlot --fsync'",
                            ),
                            onConfirm = {
                                vm.resignAndFlashSinglePartition(
                                    partition = "init_boot",
                                    imagePath = rootPatchedPath,
                                    autoSign = true,
                                    targetSlot = activeSlot,
                                )
                            },
                        )
                    },
                    enabled = rootPatchedPath.isNotBlank() && !state.busy,
                ) { Text("重签并单刷") }
            },
            dismissButton = { TextButton(onClick = { showRootUpdateDialog = false }) { Text("取消") } },
        )
    }

    // 3b. Custom Boot / Init_boot Re-sign & Flash Dialog
    if (showCustomBootDialog) {
        AlertDialog(
            onDismissRequest = { showCustomBootDialog = false },
            title = { Text("重签自改 Boot / Init_boot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("init_boot", "boot", "vendor_boot").forEach { p ->
                            val sel = customBootPart == p
                            Surface(
                                shape = CircleShape,
                                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.clickable { customBootPart = p },
                            ) {
                                Text(
                                    text = p,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { customImgPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (customBootPath.isBlank()) "选择修改后的 .img 文件" else File(customBootPath).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCustomBootDialog = false
                        val imgName = File(customBootPath).name
                        dryRunRequest = DryRunPayload(
                            title = "重签并单刷 ${customBootPart}$activeSlot",
                            summary = "私钥「${defaultKey?.alias ?: "默认"}」· 同钥重签并回读核验",
                            commands = listOf(
                                "avbforge-core avb add-hash-footer --partition-name $customBootPart --image $imgName",
                                "su -c 'avbforge-core flash-partition --partition $customBootPart --slot $activeSlot --fsync'",
                            ),
                            onConfirm = {
                                vm.resignAndFlashSinglePartition(
                                    partition = customBootPart,
                                    imagePath = customBootPath,
                                    autoSign = true,
                                    targetSlot = activeSlot,
                                )
                            },
                        )
                    },
                    enabled = customBootPath.isNotBlank() && !state.busy,
                ) { Text("同钥重签并单刷") }
            },
            dismissButton = { TextButton(onClick = { showCustomBootDialog = false }) { Text("取消") } },
        )
    }

    // 4. Full-Chain Self-Sign Dialog (devinfo + 7 chained partitions)
    if (showFullChainDialog) {
        AlertDialog(
            onDismissRequest = { showFullChainDialog = false },
            title = { Text("全链自签名（devinfo + 7 分区）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { stockDirPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (fullChainStockDir.isBlank()) "选择原厂底包目录" else File(fullChainStockDir).name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = fullChainWriteDevinfo, onCheckedChange = { fullChainWriteDevinfo = it })
                        Text("写入 devinfo user key (保持 is_unlocked=0)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFullChainDialog = false
                        val dirName = File(fullChainStockDir).name
                        val cmds = buildList {
                            add("avbforge-core forge-bundle --stock-dir $dirName --inject-dsu-key false")
                            if (fullChainWriteDevinfo) {
                                add("su -c 'avbforge-core write-devinfo-key --keep-locked true --pkmd ${defaultKey?.alias ?: "default"}_pkmd.bin'")
                            }
                            add("su -c 'avbforge-core flash-bundle --slot $activeSlot --fsync'")
                        }
                        dryRunRequest = DryRunPayload(
                            title = "全链自签名刷前预演",
                            summary = "私钥「${defaultKey?.alias ?: "默认"}」· 保持锁定 · 全链离线验签后写入",
                            commands = cmds,
                            onConfirm = {
                                vm.forgeAndFlashFullChain(
                                    stockDirPath = fullChainStockDir,
                                    writeDevinfoKey = fullChainWriteDevinfo,
                                    targetSlot = activeSlot,
                                )
                            },
                        )
                    },
                    enabled = fullChainStockDir.isNotBlank() && !state.busy,
                ) { Text("预览命令") }
            },
            dismissButton = { TextButton(onClick = { showFullChainDialog = false }) { Text("取消") } },
        )
    }

    // 5. New Backup Type Menu
    if (showNewBackupMenu) {
        val dateTag = remember { SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) }
        AlertDialog(
            onDismissRequest = { showNewBackupMenu = false },
            title = { Text("新建分区备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showNewBackupMenu = false
                                val defName = "full_${activeSlot.trimStart('_')}_$dateTag"
                                backupNameInput = defName
                                pendingBackupSpec = defName to ALL_TARGET_PARTITIONS.map { it.name }
                            },
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("完整备份（devinfo + 7 链式分区）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("约 449 MB · 用于回滚原厂", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showNewBackupMenu = false
                                val defName = "min_init_boot_$dateTag"
                                backupNameInput = defName
                                pendingBackupSpec = defName to listOf("init_boot", "vbmeta")
                            },
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("最小备份（init_boot + vbmeta）", style = MaterialTheme.typography.titleSmall)
                            Text("约 8.1 MB · 更新 Root 前留底", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNewBackupMenu = false }) { Text("取消") } },
        )
    }

    // Backup Naming Confirmation Dialog
    pendingBackupSpec?.let { (defName, parts) ->
        AlertDialog(
            onDismissRequest = { pendingBackupSpec = null },
            title = { Text("确认备份名称") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = backupNameInput,
                        onValueChange = { backupNameInput = it },
                        singleLine = true,
                        label = { Text("备份目录名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存路径：Download/AVBForge/${backupNameInput.ifBlank { defName }}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val chosen = backupNameInput.ifBlank { defName }
                    pendingBackupSpec = null
                    vm.backupPartitionsNamed(folderName = chosen, slot = activeSlot, partitions = parts)
                }) { Text("确认备份") }
            },
            dismissButton = { TextButton(onClick = { pendingBackupSpec = null }) { Text("取消") } },
        )
    }

    // 6. Backup Item Management Dialog (Restore / Rename / Delete)
    activeBackupItem?.let { bkp ->
        val pubkeyMismatch = !bkp.pubkeySha1.isNullOrBlank() &&
            !snap?.vbmetaPubkeySha1.isNullOrBlank() &&
            !bkp.pubkeySha1.equals(snap?.vbmetaPubkeySha1, ignoreCase = true)
        AlertDialog(
            onDismissRequest = { activeBackupItem = null },
            title = { Text(bkp.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "槽位 ${bkp.slot} · ${bkp.partitions.size} 分区 · ${formatByteSize(bkp.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = bkp.partitions.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pubkeyMismatch) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                            Text(
                                text = "公钥与当前 vbmeta 不一致：单刷会拒启，请先用当前私钥重签或整套恢复。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                activeBackupItem = null
                                renameBackupInput = bkp.name
                                pendingRenameBackup = bkp
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("重命名") }
                        OutlinedButton(
                            onClick = {
                                activeBackupItem = null
                                pendingDeleteBackup = bkp
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("删除")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeBackupItem = null
                        dryRunRequest = DryRunPayload(
                            title = "从备份恢复（${bkp.name}）",
                            summary = "槽位 ${bkp.slot} · 共 ${bkp.partitions.size} 个分区 · 刷后回读核验",
                            commands = listOf("su -c 'avbforge-core flash-bundle --bundle-dir ${bkp.name} --slot ${bkp.slot} --fsync'"),
                            onConfirm = { vm.flashForgedBundle(bkp.path, bkp.slot) },
                        )
                    },
                    enabled = !pubkeyMismatch && bkp.partitions.contains("vbmeta"),
                ) { Text("恢复刷入") }
            },
            dismissButton = { TextButton(onClick = { activeBackupItem = null }) { Text("关闭") } },
        )
    }

    // Rename Backup Dialog
    pendingRenameBackup?.let { bkp ->
        AlertDialog(
            onDismissRequest = { pendingRenameBackup = null },
            title = { Text("重命名备份") },
            text = {
                OutlinedTextField(
                    value = renameBackupInput,
                    onValueChange = { renameBackupInput = it },
                    singleLine = true,
                    label = { Text("新名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.renamePartitionBackup(bkp.path, renameBackupInput)
                    pendingRenameBackup = null
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pendingRenameBackup = null }) { Text("取消") } },
        )
    }

    // Confirmed Backup Deletion Dialog
    pendingDeleteBackup?.let { bkp ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBackup = null },
            title = { Text("确认删除分区备份？") },
            text = { Text("将永久删除 ${bkp.name}（释放 ${formatByteSize(bkp.totalBytes)}），不可恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deletePartitionBackup(bkp.path)
                        pendingDeleteBackup = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteBackup = null }) { Text("取消") } },
        )
    }

    // 7. Single-Partition Quick Action Dialog
    activeSinglePart?.let { part ->
        val dateTag = remember { SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) }
        AlertDialog(
            onDismissRequest = { activeSinglePart = null },
            title = { Text("${part.name}${if (part.name == "devinfo") "" else activeSlot}") },
            text = { Text("${part.role} · ${part.fallbackSize}") },
            confirmButton = {
                Button(
                    onClick = {
                        activeSinglePart = null
                        singlePartFlashTarget = part.name
                        singlePartImgPicker.launch(arrayOf("*/*"))
                    },
                    enabled = !state.busy && part.name != "devinfo",
                ) {
                    Icon(Icons.Rounded.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷入此分区")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        activeSinglePart = null
                        val defName = "${part.name}_$dateTag"
                        backupNameInput = defName
                        pendingBackupSpec = defName to listOf(part.name)
                    }) { Text("备份此分区") }
                    TextButton(onClick = { activeSinglePart = null }) { Text("取消") }
                }
            },
        )
    }

    // Shared Dry-Run Command Dialog
    dryRunRequest?.let { req ->
        DryRunCommandDialog(
            title = req.title,
            safetySummary = req.summary,
            plannedCommands = req.commands,
            onConfirm = {
                dryRunRequest = null
                req.onConfirm()
            },
            onDismiss = { dryRunRequest = null },
        )
    }
}

private data class DryRunPayload(
    val title: String,
    val summary: String,
    val commands: List<String>,
    val onConfirm: () -> Unit,
)

@Composable
private fun HeroActionMiniButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}
