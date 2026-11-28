package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.data.LocalGsiImage
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.DryRunCommandDialog
import io.github.fu.avbforge.ui.components.formatByteSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DsuManagerScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onGoPartitions: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val snap = state.snapshot
    val isYellow = snap?.verifiedBootState.equals("yellow", ignoreCase = true)
    val dsuInfo = state.dsuInfo
    val dsuInstalled = dsuInfo?.installed == true
    val dsuEnabled = dsuInfo?.enabled == true
    val pubkeyInjected = snap?.initBootAvbpubkeys?.isNotEmpty() == true
    val targetKeyName = snap?.fstabAvbKeys?.firstOrNull()?.substringAfterLast('/') ?: "q-gsi.avbpubkey"
    val defaultKey = state.keys.firstOrNull { it.isDefault } ?: state.keys.firstOrNull()

    var singleBoot by remember(dsuInfo?.oneShot) { mutableStateOf(dsuInfo?.oneShot ?: true) }
    var userdataSlider by remember { mutableFloatStateOf(16f) }
    var fixVendorGenfs by remember { mutableStateOf(true) }

    // Modals
    var showPubkeyDialog by remember { mutableStateOf(false) }
    var showFlashDialog by remember { mutableStateOf(false) }
    var flashImagePath by remember { mutableStateOf(state.localGsiImages.firstOrNull()?.path ?: "") }
    var showSignDialog by remember { mutableStateOf(false) }
    var signImagePath by remember { mutableStateOf("") }
    var activeLocalImg by remember { mutableStateOf<LocalGsiImage?>(null) }
    var pendingDeleteImg by remember { mutableStateOf<LocalGsiImage?>(null) }
    var showDsuBackupDialog by remember { mutableStateOf(false) }
    var dsuBackupName by remember { mutableStateOf("") }
    var confirmWipeData by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmBoot by remember { mutableStateOf(false) }
    var dryRunInstall by remember { mutableStateOf<Pair<String, Int>?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshDsuInfo(silent = true)
    }

    val flashPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                flashImagePath = it
                onNotify("已选择：${File(it).name}")
            }
        }
    }
    val signPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                signImagePath = it
                onNotify("已选择：${File(it).name}")
            }
        }
    }

    // Gate: DSU Management unlocks only in Yellow self-signed state
    if (!isYellow) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Text("仅 Yellow 自签名系统可用", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = "当前为 ${snap?.verifiedBootState?.uppercase()?.ifBlank { "GREEN" } ?: "GREEN"} 原厂状态，完成自签名后自动解锁",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onGoPartitions,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("前往分区管理") }
                }
            }
        }
        return
    }

    val activeImageLabel = state.lastInstalledDsuImageName
        ?: dsuInfo?.slotName?.let { "system_gsi ($it)" }
        ?: "system_gsi"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Unified Hero Card: DSU Status + Active Target Image + 3-col tiles
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
                            Column {
                                Text("DSU 动态系统", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                Text(
                                    text = "${dsuInfo?.slotName ?: "空闲槽位"} · gsi_tool",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val (pillBg, pillFg, pillText) = when {
                                !dsuInstalled -> Triple(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurfaceVariant, "未安装")
                                dsuEnabled -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, if (singleBoot) "已启用 · 单次 -s" else "已启用 · 持续")
                                else -> Triple(Color(0xFF3D2E08), Color(0xFFFFD97D), "已停用")
                            }
                            Surface(shape = CircleShape, color = pillBg) {
                                Text(
                                    text = pillText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                                    color = pillFg,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }

                        // Active Installed Image Banner
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Album,
                                    contentDescription = null,
                                    tint = if (dsuInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when {
                                            dsuInstalled && dsuEnabled -> "下次重启将进入镜像"
                                            dsuInstalled -> "已安装镜像（已停用）"
                                            else -> "当前槽位镜像"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = if (dsuInstalled) activeImageLabel else "未安装任何 GSI 镜像",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // 3-col Metrics Row (System / Userdata / DSU Pubkey)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DsuHeroTile(
                                label = "System",
                                value = if (dsuInstalled && (dsuInfo?.systemSizeBytes ?: 0L) > 0L) formatByteSize(dsuInfo!!.systemSizeBytes) else "--",
                                modifier = Modifier.weight(1f),
                            )
                            DsuHeroTile(
                                label = "Userdata",
                                value = if (dsuInstalled && (dsuInfo?.userdataSizeBytes ?: 0L) > 0L) formatByteSize(dsuInfo!!.userdataSizeBytes) else "--",
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showPubkeyDialog = true },
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                                    Text("DSU 公钥 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = if (pubkeyInjected) "已注入" else "未注入",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = if (pubkeyInjected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Unified 2-Column Primary Action Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (!pubkeyInjected) {
                            showPubkeyDialog = true
                            onNotify("当前 init_boot 未检测到 DSU 公钥，请先注入")
                        } else {
                            if (flashImagePath.isBlank()) flashImagePath = state.localGsiImages.firstOrNull()?.path ?: ""
                            showFlashDialog = true
                        }
                    },
                    enabled = !state.busy,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷入 GSI", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        if (!pubkeyInjected) {
                            showPubkeyDialog = true
                            onNotify("当前 init_boot 缺少 DSU 公钥，请先补注")
                        } else if (!dsuInstalled) {
                            onNotify("当前未安装 DSU 镜像，请先刷入 GSI")
                        } else {
                            confirmBoot = true
                        }
                    },
                    enabled = !state.busy,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("启动 DSU", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Grouped List 1: Local GSI Images
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
                        text = "本地 GSI 镜像 (AVBForge)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "+ 签名新镜像",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = !state.busy) { showSignDialog = true },
                    )
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.localGsiImages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("暂无本地镜像", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Column {
                            state.localGsiImages.forEachIndexed { idx, img ->
                                if (idx > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                                val isCurrent = dsuInstalled && state.lastInstalledDsuImageName == img.name
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeLocalImg = img }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = img.name,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (isCurrent) {
                                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                                    Text(
                                                        text = "当前槽位",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = formatByteSize(img.sizeBytes),
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

        // Grouped List 2: Boolean Boot Switches
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "启动开关",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text("启用 DSU（下次重启生效）", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                            }
                            Switch(
                                checked = dsuEnabled,
                                enabled = dsuInstalled && !state.busy,
                                onCheckedChange = { enable ->
                                    if (enable) vm.enableDsu(singleBoot) else vm.runDsuSimple("disable")
                                },
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text("默认单次启动 (-s)", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                            }
                            Switch(
                                checked = singleBoot,
                                enabled = !state.busy,
                                onCheckedChange = { next ->
                                    singleBoot = next
                                    if (dsuInstalled && dsuEnabled) vm.enableDsu(next)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Grouped List 3: Maintenance Actions (All with confirmation / naming)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "维护操作",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        MaintenanceActionRow(Icons.Rounded.Archive, "备份当前 DSU", MaterialTheme.colorScheme.primary) {
                            if (!dsuInstalled) {
                                onNotify("当前未安装 DSU")
                            } else {
                                val tag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                                dsuBackupName = "dsu_backup_$tag"
                                showDsuBackupDialog = true
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp))
                        MaintenanceActionRow(Icons.Rounded.CleaningServices, "重置 Userdata 数据", MaterialTheme.colorScheme.primary) {
                            if (!dsuInstalled) onNotify("当前未安装 DSU") else confirmWipeData = true
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp))
                        MaintenanceActionRow(Icons.Rounded.DeleteForever, "卸载 DSU 并释放空间", MaterialTheme.colorScheme.error, isError = true) {
                            if (!dsuInstalled) onNotify("当前未安装 DSU") else confirmWipe = true
                        }
                    }
                }
            }
        }
    }

    // DSU Pubkey Dedicated Management Dialog
    if (showPubkeyDialog) {
        AlertDialog(
            onDismissRequest = { showPubkeyDialog = false },
            title = { Text("DSU 公钥状态") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (pubkeyInjected) "已注入：${snap?.initBootAvbpubkeys?.joinToString(", ")}" else "当前 init_boot 未检测到 DSU 公钥",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (pubkeyInjected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "目标路径：/avb/$targetKeyName\n注入密钥：${defaultKey?.alias ?: "未配置"}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.lastDsuPatchReport?.let { rep ->
                        Button(
                            onClick = {
                                showPubkeyDialog = false
                                vm.flashPreparedDsuInitBoot(rep.outputPath)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("② 将生成的 init_boot 写入当前槽位") }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.prepareDsuInitBoot(targetKeyName) },
                    enabled = !state.busy && defaultKey != null,
                ) { Text("① 注入公钥并重签") }
            },
            dismissButton = { TextButton(onClick = { showPubkeyDialog = false }) { Text("关闭") } },
        )
    }

    // Flash GSI Config Dialog
    if (showFlashDialog) {
        val gib = userdataSlider.roundToInt()
        AlertDialog(
            onDismissRequest = { showFlashDialog = false },
            title = { Text("刷入 GSI 到 DSU") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { flashPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (flashImagePath.isBlank()) "选择已签名的 .img" else File(flashImagePath).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("独立 Userdata 大小", style = MaterialTheme.typography.bodySmall)
                        Text("$gib GiB", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = userdataSlider, onValueChange = { userdataSlider = it }, valueRange = 4f..64f, steps = 14)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFlashDialog = false
                        dryRunInstall = flashImagePath to gib
                    },
                    enabled = flashImagePath.isNotBlank() && !state.busy,
                ) { Text("预览命令") }
            },
            dismissButton = { TextButton(onClick = { showFlashDialog = false }) { Text("取消") } },
        )
    }

    // Dry-Run Install Dialog
    dryRunInstall?.let { (imgPath, gib) ->
        val imgName = File(imgPath).name
        val enCmd = if (singleBoot) "gsi_tool enable -s && gsi_tool status" else "gsi_tool enable && gsi_tool status"
        DryRunCommandDialog(
            title = "DSU 刷入命令预演",
            safetySummary = "Userdata $gib GiB · ${if (singleBoot) "单次启动 (-s)" else "持续启动"} · 隔离原厂系统",
            plannedCommands = listOf(
                "avbforge-core avb verify --image $imgName",
                "su -c 'avbforge-core dsu install --image $imgName --userdata-size ${gib}G --single-boot $singleBoot'",
                "su -c '$enCmd'",
            ),
            onConfirm = {
                dryRunInstall = null
                vm.installSignedDsu(signedPath = imgPath, userdataGiB = gib, singleBoot = singleBoot)
            },
            onDismiss = { dryRunInstall = null },
        )
    }

    // Sign GSI Dialog
    if (showSignDialog) {
        AlertDialog(
            onDismissRequest = { showSignDialog = false },
            title = { Text("签名 GSI 镜像") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { signPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (signImagePath.isBlank()) "选择 .img / .xz / .gz" else File(signImagePath).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fixVendorGenfs, onCheckedChange = { fixVendorGenfs = it })
                        Text("自动修复 SELinux genfscon 冲突", style = MaterialTheme.typography.bodySmall)
                    }
                    if (defaultKey == null) {
                        Text(
                            text = "尚未配置默认私钥，请先在「分区管理」生成或导入私钥",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignDialog = false
                        vm.signGsiImage(signImagePath, fixVendorGenfs = fixVendorGenfs)
                    },
                    enabled = signImagePath.isNotBlank() && !state.busy && defaultKey != null,
                ) { Text("开始签名") }
            },
            dismissButton = { TextButton(onClick = { showSignDialog = false }) { Text("取消") } },
        )
    }

    // Local GSI Image Action Dialog
    activeLocalImg?.let { img ->
        AlertDialog(
            onDismissRequest = { activeLocalImg = null },
            title = { Text(img.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text("大小：${formatByteSize(img.sizeBytes)}") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        activeLocalImg = null
                        signImagePath = img.path
                        showSignDialog = true
                    }) { Text("签名") }
                    Button(onClick = {
                        activeLocalImg = null
                        flashImagePath = img.path
                        showFlashDialog = true
                    }) { Text("刷入") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        activeLocalImg = null
                        pendingDeleteImg = img
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
        )
    }

    // Confirmed Local GSI Deletion
    pendingDeleteImg?.let { img ->
        AlertDialog(
            onDismissRequest = { pendingDeleteImg = null },
            title = { Text("确认删除本地镜像？") },
            text = { Text("将删除 ${img.name}（释放 ${formatByteSize(img.sizeBytes)}），不影响已安装的 DSU。") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteLocalGsiImage(img.path)
                        pendingDeleteImg = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteImg = null }) { Text("取消") } },
        )
    }

    // DSU Backup Naming Confirmation Dialog
    if (showDsuBackupDialog) {
        AlertDialog(
            onDismissRequest = { showDsuBackupDialog = false },
            title = { Text("备份当前 DSU") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dsuBackupName,
                        onValueChange = { dsuBackupName = it },
                        singleLine = true,
                        label = { Text("备份目录名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存路径：Download/AVBForge/${dsuBackupName.ifBlank { "dsu_backup" }}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val chosen = dsuBackupName.ifBlank { "dsu_backup" }
                    showDsuBackupDialog = false
                    vm.backupDsuNamed(chosen)
                }) { Text("确认备份") }
            },
            dismissButton = { TextButton(onClick = { showDsuBackupDialog = false }) { Text("取消") } },
        )
    }

    // Boot Confirmation (Displays Target Image Name!)
    if (confirmBoot) {
        DryRunCommandDialog(
            title = "确认重启进入 DSU？",
            safetySummary = "目标镜像：$activeImageLabel · ${if (singleBoot) "单次防变砖 (-s)" else "持续启动"}",
            plannedCommands = listOf(
                "su -c 'gsi_tool ${if (singleBoot) "enable -s" else "enable"}'",
                "su -c 'svc power reboot'",
            ),
            onConfirm = {
                confirmBoot = false
                vm.rebootToDsu()
            },
            onDismiss = { confirmBoot = false },
        )
    }

    // Secondary Confirmation: Wipe Userdata
    if (confirmWipeData) {
        AlertDialog(
            onDismissRequest = { confirmWipeData = false },
            title = { Text("确认重置 DSU Userdata？") },
            text = { Text("将清空 DSU 独立用户数据，保留当前 System 镜像，不影响原厂系统。\n\n$ su -c 'gsi_tool wipe-data'") },
            confirmButton = {
                Button(onClick = {
                    confirmWipeData = false
                    vm.wipeDsuData()
                }) { Text("确认重置") }
            },
            dismissButton = { TextButton(onClick = { confirmWipeData = false }) { Text("取消") } },
        )
    }

    // Secondary Confirmation: Wipe DSU
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("确认卸载 DSU 并释放空间？") },
            text = { Text("将彻底删除当前 DSU 镜像（$activeImageLabel）与独立 Userdata，不影响原厂系统。\n\n$ su -c 'gsi_tool wipe'") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmWipe = false
                        vm.runDsuSimple("wipe")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("确认卸载") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DsuHeroTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MaintenanceActionRow(
    icon: ImageVector,
    title: String,
    tint: Color,
    isError: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
