package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddModerator
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.data.KeyEntry
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.M3EHelpButton
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var showAddSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<KeyEntry?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.keys.isEmpty()) {
            // Clean Expressive Empty State (Zero Form Wall!)
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Text("密钥保险箱为空", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "私钥将通过 Android Keystore (AES-256-GCM) 加密托管。点击右下角按钮生成新密钥或导入标准 PEM。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                M3ESectionLabel("已托管密钥（${state.keys.size}）", helpDoc = HelpDocs.PKMD_EXPORT)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.keys.forEachIndexed { idx, key ->
                        val matchesDevice = state.snapshot?.vbmetaPubkeySha1.equals(key.sha1, ignoreCase = true)
                        Surface(
                            shape = m3eGroupShape(idx, state.keys.size),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (key.isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.size(42.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.Key,
                                                contentDescription = null,
                                                tint = if (key.isDefault) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${key.alias} · RSA-${key.bits}", style = MaterialTheme.typography.titleMedium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (key.isDefault) {
                                                M3EPillChip(
                                                    text = "默认",
                                                    icon = Icons.Rounded.Star,
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                )
                                            }
                                            if (matchesDevice) {
                                                M3EPillChip(
                                                    text = "匹配本机 vbmeta",
                                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                        }
                                    }
                                    IconButton(onClick = { pendingDelete = key }) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }

                                M3EPillChip(
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
                                        Spacer(Modifier.width(6.dp))
                                        Text("导出 pkmd")
                                    }
                                    if (!key.isDefault) {
                                        OutlinedButton(onClick = { vm.setDefaultKey(key.id) }, shape = CircleShape) {
                                            Text("设为默认")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(150.dp))
            }
        }

        // Clean Extended FAB hovering above floating nav bar
        ExtendedFloatingActionButton(
            onClick = { showAddSheet = true },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("添加密钥") },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 92.dp),
        )
    }

    if (showAddSheet) {
        AddKeyBottomSheet(
            busy = state.busy,
            onGenerate = { alias, bits ->
                vm.generateKey(alias, bits)
                showAddSheet = false
            },
            onImport = { alias, path, pass ->
                vm.importStandardPem(alias, path, pass)
                showAddSheet = false
            },
            onPickFile = { uri -> UriPathResolver.resolveFileUri(ctx, uri) },
            onDismiss = { showAddSheet = false },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("删除密钥「${target.alias}」？") },
            text = { Text("删除后无法恢复，请确保已保留原 PEM 备份。") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteKey(target.id)
                        pendingDelete = null
                        onNotify("已删除密钥")
                    },
                    shape = CircleShape,
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeyBottomSheet(
    busy: Boolean,
    onGenerate: (alias: String, bits: Int) -> Unit,
    onImport: (alias: String, path: String, pass: String?) -> Unit,
    onPickFile: (android.net.Uri) -> String?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember { mutableIntStateOf(0) } // 0 = Generate, 1 = Import
    var genAlias by remember { mutableStateOf("neo9-avb") }
    var bitIdx by remember { mutableIntStateOf(0) }
    val bits = listOf(2048, 4096)

    var importPath by remember { mutableStateOf("") }
    var importAlias by remember { mutableStateOf("") }
    var importPass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onPickFile(uri)?.let { resolved ->
                importPath = resolved
                if (importAlias.isBlank()) {
                    importAlias = File(resolved).nameWithoutExtension
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("添加 AVB 签名密钥", style = MaterialTheme.typography.titleLarge)
                M3EHelpButton(doc = HelpDocs.KEY_BITS)
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("生成新密钥", "导入 PEM").forEachIndexed { idx, label ->
                    SegmentedButton(
                        selected = mode == idx,
                        onClick = { mode = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, 2),
                    ) { Text(label) }
                }
            }

            if (mode == 0) {
                OutlinedTextField(
                    value = genAlias,
                    onValueChange = { genAlias = it },
                    label = { Text("备注名") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    bits.forEachIndexed { idx, b ->
                        SegmentedButton(
                            selected = bitIdx == idx,
                            onClick = { bitIdx = idx },
                            shape = SegmentedButtonDefaults.itemShape(idx, 2),
                        ) { Text("RSA-$b" + if (b == 2048) " (推荐)" else "") }
                    }
                }
                M3EPrimaryButton(
                    text = "生成 RSA-${bits[bitIdx]} 并加密入库",
                    icon = Icons.Rounded.AddModerator,
                    onClick = { onGenerate(genAlias.trim(), bits[bitIdx]) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = importPath,
                        onValueChange = { importPath = it },
                        label = { Text("标准 PKCS#8 PEM 文件路径") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "选择文件")
                    }
                }
                OutlinedTextField(
                    value = importAlias,
                    onValueChange = { importAlias = it },
                    label = { Text("备注名") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = importPass,
                    onValueChange = { importPass = it },
                    label = { Text("密码（明文 PEM 留空）") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                M3EPrimaryButton(
                    text = "校验并导入保险箱",
                    icon = Icons.Rounded.FileUpload,
                    onClick = { onImport(importAlias.trim(), importPath.trim(), importPass.ifBlank { null }) },
                    enabled = !busy && importPath.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
