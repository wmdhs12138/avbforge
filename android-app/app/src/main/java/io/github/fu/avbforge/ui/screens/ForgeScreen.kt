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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import io.github.fu.avbforge.ui.components.ExpressiveCard
import io.github.fu.avbforge.ui.components.ExpressivePathField
import io.github.fu.avbforge.ui.components.ExpressivePillChip
import io.github.fu.avbforge.ui.components.ExpressivePrimaryButton
import io.github.fu.avbforge.ui.components.StepBadgeHeader

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
            val resolved = UriPathResolver.resolveTreeUri(uri)
            if (resolved != null) {
                stockDir = resolved
                onNotify("已选择散包目录")
            } else {
                onNotify("无法解析目录，请手动输入路径")
            }
        }
    }

    val ksuImgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolved = UriPathResolver.resolveFileUri(ctx, uri)
            if (resolved != null) {
                patchedInitBootPath = resolved
                onNotify("已选择 KSU 修补后的 init_boot")
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Step 1: Stock folder
        ExpressiveCard {
            StepBadgeHeader(
                stepNumber = "01",
                title = "原厂散包 / 备份目录",
                subtitle = "需包含 vbmeta.img、vbmeta_system.img 及各引导分区镜像",
            )
            ExpressivePathField(
                value = stockDir,
                onValueChange = { stockDir = it },
                label = "选择或输入目录路径",
                onPick = { dirPicker.launch(null) },
            )
            state.lastBackupDir?.let { bkpDir ->
                ExpressivePillChip(
                    text = "一键填入最近备份目录",
                    onClick = {
                        stockDir = bkpDir
                        onNotify("已填入最近备份目录")
                    },
                )
            }
        }

        // Step 2: KSU Root Collaboration
        ExpressiveCard {
            StepBadgeHeader(
                stepNumber = "02",
                title = "随本轮一并刷入 KernelSU Root",
                subtitle = "推荐开启，支持自选任意 KSU 管理器协作修补",
                trailing = {
                    Switch(checked = includeKsuRoot, onCheckedChange = { includeKsuRoot = it })
                },
            )
            AnimatedVisibility(visible = includeKsuRoot, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "① 点下方导出纯净 init_boot.img → ② 打开你喜欢的 KernelSU 管理器（官版 / Next / SukiSU 等）修补它 → ③ 选回修补产物。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { vm.exportInitBootForKsuManager() },
                        enabled = !state.busy,
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("导出当前 init_boot.img")
                    }
                    state.exportedInitBootPath?.let { exported ->
                        ExpressivePillChip(
                            text = "已导出：$exported",
                            copyValue = exported,
                            onCopied = { onNotify("已复制导出路径") },
                        )
                    }
                    ExpressivePathField(
                        value = patchedInitBootPath,
                        onValueChange = { patchedInitBootPath = it },
                        label = "KSU 管理器修补后的 init_boot 镜像",
                        onPick = { ksuImgPicker.launch(arrayOf("*/*")) },
                    )
                }
            }
            AnimatedVisibility(visible = !includeKsuRoot, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(
                        "关闭后将刷入纯净无 Root 镜像，重启后如需继续操作必须重新获取临时 Root。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Step 3: DSU Key Injection + Forge Action
        ExpressiveCard {
            StepBadgeHeader(
                stepNumber = "03",
                title = "注入 DSU 自签名公钥并全链重签",
                subtitle = "使回锁 Yellow 系统可直接启动自签名 GSI DSU",
                trailing = {
                    Switch(checked = injectDsuKey, onCheckedChange = { injectDsuKey = it })
                },
            )
            AnimatedVisibility(visible = injectDsuKey, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dsuKeyName,
                        onValueChange = { dsuKeyName = it },
                        label = { Text("目标公钥名（匹配 fstab avb_keys）") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val fstabNames = state.snapshot?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()
                    if (fstabNames.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            fstabNames.forEach { kname ->
                                ExpressivePillChip(
                                    text = kname,
                                    icon = Icons.Rounded.Key,
                                    containerColor = if (dsuKeyName == kname) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (dsuKeyName == kname) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    onClick = { dsuKeyName = kname },
                                )
                            }
                        }
                    }
                }
            }
            ExpressivePrimaryButton(
                text = "构建全套自签名包并强制离线验签",
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
        }

        // Step 4: Verified Forge Result + Flash Gate
        AnimatedVisibility(
            visible = state.lastForgeReport != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.lastForgeReport?.let { rep ->
                ExpressiveCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text("离线全链验签通过（${rep.chainVerifiedCount} 个镜像）", style = MaterialTheme.typography.titleMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExpressivePillChip(text = "KSU: ${if (rep.initBootHasKsu) "已含" else "无"}")
                        ExpressivePillChip(text = "DSU公钥: ${if (rep.dsuKeyInjected) "已注入" else "无"}")
                        ExpressivePillChip(
                            text = "SHA-1: ${rep.pubkeySha1.take(10)}",
                            copyValue = rep.pubkeySha1,
                            onCopied = { onNotify("已复制公钥指纹") },
                        )
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        slotChoices.forEachIndexed { idx, (_, label) ->
                            SegmentedButton(
                                selected = slotChoiceIdx == idx,
                                onClick = { slotChoiceIdx = idx },
                                shape = SegmentedButtonDefaults.itemShape(idx, slotChoices.size),
                            ) { Text(label) }
                        }
                    }
                    ExpressivePrimaryButton(
                        text = "进入安全门禁并写入目标槽位",
                        icon = Icons.Rounded.FlashOn,
                        onClick = { showFlashConfirm = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(92.dp))
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
            title = { Text(assessment?.sdkBannerTitle ?: "确认刷入自签名镜像包") },
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
