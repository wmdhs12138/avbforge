package io.github.fu.avbforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState

@Composable
fun DsuScreen(state: UiState, vm: AvbForgeViewModel) {
    var gsiInputPath by remember { mutableStateOf("/sdcard/Download/") }
    var userdataGiB by remember { mutableStateOf("8") }
    var singleBoot by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. GSI 极速自签名（支持 .img / .xz / .gz / Sparse）", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = gsiInputPath,
                    onValueChange = { gsiInputPath = it },
                    label = { Text("GSI 文件路径") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.signGsiImage(gsiInputPath.trim()) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Rust 多线程计算 Hashtree 并签名验签")
                }
            }
        }

        state.lastGsiReport?.let { rep ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. 安装已签名 GSI 到 DSU", style = MaterialTheme.typography.titleMedium)
                    Text("已签名文件：${rep.outputPath}", style = MaterialTheme.typography.bodySmall)
                    Text("Hashtree Root：${rep.rootDigest.take(24)}...", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = userdataGiB,
                        onValueChange = { userdataGiB = it },
                        label = { Text("分配给 GSI 的 Userdata 大小 (GiB)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("单次启动模式（-s，翻车重启自动回原系统）", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = singleBoot, onCheckedChange = { singleBoot = it })
                    }
                    Button(
                        onClick = {
                            vm.installSignedDsu(
                                signedPath = rep.outputPath,
                                userdataGiB = userdataGiB.toIntOrNull()?.coerceIn(2, 128) ?: 8,
                                singleBoot = singleBoot,
                            )
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("通过原生 gsi_tool 安装 DSU")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DSU 状态与清理（当前：${state.dsuStatusText}）", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.runDsuSimple("status") }, enabled = !state.busy) { Text("刷新状态") }
                    OutlinedButton(onClick = { vm.runDsuSimple("disable") }, enabled = !state.busy) { Text("禁用") }
                    OutlinedButton(onClick = { vm.runDsuSimple("wipe") }, enabled = !state.busy) { Text("彻底清理") }
                }
            }
        }
    }
}
