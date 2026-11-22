package io.github.fu.avbforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState

@Composable
fun ForgeScreen(state: UiState, vm: AvbForgeViewModel) {
    var stockDir by remember { mutableStateOf("/sdcard/Download/") }
    var includeKsuRoot by remember { mutableStateOf(true) }
    var patchedInitBootPath by remember { mutableStateOf("") }
    var injectDsuKey by remember { mutableStateOf(true) }
    var dsuKeyName by remember {
        mutableStateOf(
            state.snapshot?.fstabAvbKeys?.firstOrNull()?.substringAfterLast('/') ?: "q-gsi.avbpubkey"
        )
    }
    var targetSlot by remember { mutableStateOf("") }
    var showFlashConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Step 1 · 准备原厂散包目录", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = stockDir,
                    onValueChange = { stockDir = it },
                    label = { Text("原厂散包 / 备份目录（含 vbmeta.img 等）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Step 2 · 随本轮一并刷入 KernelSU Root", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = includeKsuRoot, onCheckedChange = { includeKsuRoot = it })
                }
                if (includeKsuRoot) {
                    Text(
                        "导出纯净 init_boot.img → 用你喜欢的任意 KernelSU 管理器（官版/Next/SukiSU 等）修补 → 填回修补产物路径，本应用将保留 KSU 补丁并重签。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { vm.exportInitBootForKsuManager() }, enabled = !state.busy) {
                        Text("导出当前 init_boot.img 给 KSU 管理器")
                    }
                    state.exportedInitBootPath?.let {
                        Text("已导出：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = patchedInitBootPath,
                        onValueChange = { patchedInitBootPath = it },
                        label = { Text("KSU 管理器修补后的 init_boot 路径") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "注意：不带 KernelSU 刷入并重启后将没有 Root，后续需要重新获取临时 Root 才能继续操作。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Step 3 · 注入 DSU 自签名公钥", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = injectDsuKey, onCheckedChange = { injectDsuKey = it })
                }
                if (injectDsuKey) {
                    OutlinedTextField(
                        value = dsuKeyName,
                        onValueChange = { dsuKeyName = it },
                        label = { Text("目标公钥名（匹配 fstab avb_keys）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        vm.forgeSelfSignedBundle(
                            stockDirPath = stockDir.trim(),
                            patchedInitBootPath = if (includeKsuRoot) patchedInitBootPath.trim().ifBlank { null } else null,
                            injectDsuKey = injectDsuKey,
                            dsuKeyName = dsuKeyName.trim(),
                        )
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("生成全套自签名镜像并强制离线全链验签")
                }
            }
        }

        state.lastForgeReport?.let { rep ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("离线全链验签已通过（${rep.chainVerifiedCount} 个镜像）", style = MaterialTheme.typography.titleMedium)
                    Text("产物目录：${rep.outputDir}", style = MaterialTheme.typography.bodySmall)
                    Text("含 KSU：${rep.initBootHasKsu} | 含 DSU 公钥：${rep.dsuKeyInjected}")
                    OutlinedTextField(
                        value = targetSlot,
                        onValueChange = { targetSlot = it },
                        label = { Text("目标槽位（留空为当前活动槽位，或填 _a / _b）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { showFlashConfirm = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("进入版本/状态安全门禁并直刷")
                    }
                }
            }
        }
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
                        vm.flashForgedBundle(state.lastForgeReport.outputDir, targetSlot.trim().ifBlank { null })
                    },
                    enabled = allChecked,
                ) {
                    Text("确认写入并回读核验")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFlashConfirm = false }) { Text("取消") }
            },
        )
    }
}
