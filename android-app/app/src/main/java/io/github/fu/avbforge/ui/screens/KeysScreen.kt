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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState

@Composable
fun KeysScreen(state: UiState, vm: AvbForgeViewModel) {
    var genAlias by remember { mutableStateOf("my-avb-2048") }
    var importPath by remember { mutableStateOf("/sdcard/Download/") }
    var importAlias by remember { mutableStateOf("imported-avb") }
    var importPass by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("生成新 AVB 密钥对（Keystore 加密托管）", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = genAlias,
                    onValueChange = { genAlias = it },
                    label = { Text("密钥备注名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.generateKey(genAlias, 2048) }, enabled = !state.busy) {
                        Text("生成 RSA-2048")
                    }
                    OutlinedButton(onClick = { vm.generateKey(genAlias, 4096) }, enabled = !state.busy) {
                        Text("生成 RSA-4096")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导入已有标准 PKCS#8 PEM 私钥", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = importPath,
                    onValueChange = { importPath = it },
                    label = { Text("PEM 文件路径") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = importAlias,
                    onValueChange = { importAlias = it },
                    label = { Text("备注名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = importPass,
                    onValueChange = { importPass = it },
                    label = { Text("密码（明文 PEM 留空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.importStandardPem(importAlias, importPath.trim(), importPass.ifBlank { null }) },
                    enabled = !state.busy,
                ) {
                    Text("校验并导入保险箱")
                }
            }
        }

        Text("已托管密钥（${state.keys.size}）", style = MaterialTheme.typography.titleMedium)
        state.keys.forEach { key ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${key.alias} (RSA-${key.bits})" + if (key.isDefault) " [默认]" else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("SHA-1: ${key.sha1}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.exportPkmdToDownloads(key) }) {
                            Text("导出 avb_pkmd.bin")
                        }
                        if (!key.isDefault) {
                            OutlinedButton(onClick = { vm.setDefaultKey(key.id) }) { Text("设为默认") }
                        }
                        OutlinedButton(onClick = { vm.deleteKey(key.id) }) { Text("删除") }
                    }
                }
            }
        }
    }
}
