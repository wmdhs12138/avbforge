package io.github.fu.avbforge.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddModerator
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.ui.HelpDocs
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKeyBottomSheet(
    busy: Boolean,
    initialMode: Int = 0,
    onGenerate: (alias: String, bits: Int) -> Unit,
    onImport: (alias: String, path: String, pass: String?) -> Unit,
    onPickFile: (Uri) -> String?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember(initialMode) { mutableIntStateOf(initialMode) }
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
                listOf("生成新密钥", "导入标准 PEM").forEachIndexed { idx, label ->
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
