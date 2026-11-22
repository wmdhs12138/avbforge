package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.AddModerator
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.data.KeyEntry
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.ExpressiveCard
import io.github.fu.avbforge.ui.components.ExpressivePathField
import io.github.fu.avbforge.ui.components.ExpressivePillChip
import io.github.fu.avbforge.ui.components.ExpressivePrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var genAlias by remember { mutableStateOf("neo9-avb") }
    var selectedBitIdx by remember { mutableIntStateOf(0) }
    val bitOptions = listOf(2048, 4096)

    var importPath by remember { mutableStateOf("") }
    var importAlias by remember { mutableStateOf("imported-avb") }
    var importPass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<KeyEntry?>(null) }

    val pemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolved = UriPathResolver.resolveFileUri(ctx, uri)
            if (resolved != null) {
                importPath = resolved
                onNotify("已选择私钥文件")
            } else {
                onNotify("无法解析文件路径，请手动输入")
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Keygen Card
        ExpressiveCard {
            Text("生成新 AVB 密钥对", style = MaterialTheme.typography.titleMedium)
            Text(
                "私钥采用 Android Keystore (AES-256-GCM) 加密托管，仅签名瞬间在内存解密。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = genAlias,
                onValueChange = { genAlias = it },
                label = { Text("密钥备注名") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                bitOptions.forEachIndexed { idx, bits ->
                    SegmentedButton(
                        selected = selectedBitIdx == idx,
                        onClick = { selectedBitIdx = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, bitOptions.size),
                    ) {
                        Text("RSA-$bits" + if (bits == 2048) " (Neo9 默认)" else "")
                    }
                }
            }
            ExpressivePrimaryButton(
                text = "生成 RSA-${bitOptions[selectedBitIdx]} 并加密入库",
                icon = Icons.Rounded.AddModerator,
                onClick = { vm.generateKey(genAlias, bitOptions[selectedBitIdx]) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Import Card
        ExpressiveCard {
            Text("导入已有标准 PKCS#8 PEM 私钥", style = MaterialTheme.typography.titleMedium)
            ExpressivePathField(
                value = importPath,
                onValueChange = { importPath = it },
                label = "PEM 私钥路径（点右侧图标选取）",
                onPick = { pemPicker.launch(arrayOf("*/*")) },
            )
            OutlinedTextField(
                value = importAlias,
                onValueChange = { importAlias = it },
                label = { Text("备注名") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = importPass,
                onValueChange = { importPass = it },
                label = { Text("解密密码（明文 PEM 留空）") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(
                            if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = { vm.importStandardPem(importAlias, importPath.trim(), importPass.ifBlank { null }) },
                enabled = !state.busy && importPath.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("校验并导入保险箱")
            }
        }

        // Vault List
        Text("已托管密钥（${state.keys.size}）", style = MaterialTheme.typography.titleMedium)
        if (state.keys.isEmpty()) {
            ExpressiveCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Text(
                    "保险箱为空。请先生成新密钥或导入你现有的 avb-plaintext.pem。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.keys.forEach { key ->
            val matchesDevice = state.snapshot?.vbmetaPubkeySha1.equals(key.sha1, ignoreCase = true)
            ExpressiveCard(
                containerColor = if (key.isDefault) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                borderColor = if (key.isDefault) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else null,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${key.alias} · RSA-${key.bits}", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (key.isDefault) {
                                ExpressivePillChip(
                                    text = "默认密钥",
                                    icon = Icons.Rounded.Star,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            if (matchesDevice) {
                                ExpressivePillChip(
                                    text = "与本机 vbmeta 一致",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
                ExpressivePillChip(
                    text = "SHA-1: ${key.sha1}",
                    copyValue = key.sha1,
                    onCopied = { onNotify("已复制公钥 SHA-1") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            vm.exportPkmdToDownloads(key)
                            onNotify("已导出 avb_pkmd.bin 到 Download/AVBForge")
                        },
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("导出 pkmd")
                    }
                    if (!key.isDefault) {
                        OutlinedButton(onClick = { vm.setDefaultKey(key.id) }, shape = CircleShape) {
                            Text("设为默认")
                        }
                    }
                    IconButton(onClick = { pendingDelete = key }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "删除密钥",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(92.dp))
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("确认删除密钥「${target.alias}」？") },
            text = { Text("删除后将无法恢复，请确保已妥善备份 PEM 私钥。") },
            confirmButton = {
                Button(onClick = {
                    vm.deleteKey(target.id)
                    pendingDelete = null
                    onNotify("已删除密钥")
                }) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
