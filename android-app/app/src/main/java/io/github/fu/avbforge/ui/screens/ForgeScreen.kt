package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.RiskLevel
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EHeroCard
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ForgeScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var stockDir by remember { mutableStateOf(state.lastBackupDir ?: "") }
    var includeKsuRoot by remember { mutableStateOf(true) }
    var patchedInitBootPath by remember { mutableStateOf("") }
    var injectDsuKey by remember { mutableStateOf(true) }
    var dsuKeyName by remember {
        mutableStateOf(
            state.snapshot?.fstabAvbKeys?.firstOrNull()?.substringAfterLast('/') ?: "q-gsi.avbpubkey"
        )
    }
    var slotChoiceIdx by remember { mutableIntStateOf(0) }
    val slotChoices = listOf("" to "当前槽位", "_a" to "槽位 _a", "_b" to "槽位 _b")
    var showFlashConfirm by remember { mutableStateOf(false) }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveTreeUri(uri)?.let {
                stockDir = it
                onNotify("已选择原厂目录：${File(it).name}")
            } ?: onNotify("无法解析所选目录")
        }
    }

    val ksuPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                patchedInitBootPath = it
                onNotify("已选择修补产物：${File(it).name}")
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Section 1: Stock Image Source
        M3ESectionLabel("1 · 原厂镜像来源")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = if (stockDir.isBlank()) "选择原厂散包 / 备份目录" else File(stockDir).name,
                subtitle = stockDir.ifBlank { "点击打开系统目录选择器（需含 vbmeta.img 等）" },
                icon = Icons.Rounded.FolderOpen,
                shape = m3eGroupShape(0, if (state.lastBackupDir != null) 2 else 1),
                showChevron = true,
                monospaceSubtitle = stockDir.isNotBlank(),
                onClick = { dirPicker.launch(null) },
            )
            state.lastBackupDir?.let { bkpDir ->
                M3EGroupRow(
                    title = "使用最近一次黄金备份",
                    subtitle = bkpDir,
                    icon = Icons.Rounded.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    iconBg = MaterialTheme.colorScheme.secondaryContainer,
                    shape = m3eGroupShape(1, 2),
                    trailingText = "填入",
                    onClick = {
                        stockDir = bkpDir
                        onNotify("已填入最近备份目录")
                    },
                )
            }
        }

        // Section 2: KernelSU Collaboration
        M3ESectionLabel("2 · KernelSU Root 协作")
        val ksuRowCount = if (includeKsuRoot) 3 else 1
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "随本轮一并刷入 KernelSU",
                subtitle = if (includeKsuRoot) "推荐开启，防重启后丢 Root" else "关闭将刷入无 Root 纯净自签镜像",
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

        // Section 3: DSU Public Key Injection
        M3ESectionLabel("3 · DSU 公钥注入")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "向 init_boot 注入 DSU 公钥",
                subtitle = if (injectDsuKey) "目标：$dsuKeyName（回锁可直接跑自签 GSI）" else "仅重签分区，不注入 DSU 公钥",
                icon = Icons.Rounded.Key,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                shape = m3eGroupShape(0, 1),
                trailing = {
                    Switch(checked = injectDsuKey, onCheckedChange = { injectDsuKey = it })
                },
            )
        }

        val fstabNames = state.snapshot?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()
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

        // Verified Bundle Result Card
        AnimatedVisibility(
            visible = state.lastForgeReport != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.lastForgeReport?.let { rep ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    M3EHeroCard(
                        title = "离线全链验签通过",
                        subtitle = "${rep.chainVerifiedCount} 个镜像已验签 · KSU: ${if (rep.initBootHasKsu) "已含" else "无"} · DSU公钥: ${if (rep.dsuKeyInjected) "已注入" else "无"}",
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
                                ) { Text(label) }
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
        }

        Spacer(Modifier.height(96.dp))
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (assessment != null) {
                        Text(assessment.sdkBannerDetail, style = MaterialTheme.typography.bodySmall)
                        Text(assessment.stateNotice, style = MaterialTheme.typography.bodySmall)
                        assessment.requiredChecklist.forEachIndexed { i, item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checks[i], onCheckedChange = { checks[i] = it })
                                Text(item, style = MaterialTheme.typography.bodySmall)
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
