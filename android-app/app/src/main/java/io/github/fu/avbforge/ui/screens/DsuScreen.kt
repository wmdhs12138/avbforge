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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.ExpressiveCard
import io.github.fu.avbforge.ui.components.ExpressivePathField
import io.github.fu.avbforge.ui.components.ExpressivePillChip
import io.github.fu.avbforge.ui.components.ExpressivePrimaryButton
import io.github.fu.avbforge.ui.components.StepBadgeHeader
import kotlin.math.roundToInt

@Composable
fun DsuScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var gsiInputPath by remember { mutableStateOf("") }
    var userdataSlider by remember { mutableFloatStateOf(16f) }
    var singleBoot by remember { mutableStateOf(true) }
    var confirmWipe by remember { mutableStateOf(false) }

    val gsiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolved = UriPathResolver.resolveFileUri(ctx, uri)
            if (resolved != null) {
                gsiInputPath = resolved
                onNotify("已选择 GSI 镜像文件")
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Step 1: Sign GSI
        ExpressiveCard {
            StepBadgeHeader(
                stepNumber = "01",
                title = "GSI 极速自签名",
                subtitle = "支持 .img / .img.xz / .img.gz 与 Android Sparse，多线程计算 SHA-256 Hashtree",
            )
            ExpressivePathField(
                value = gsiInputPath,
                onValueChange = { gsiInputPath = it },
                label = "选择或输入 arm64-ab GSI 文件",
                onPick = { gsiPicker.launch(arrayOf("*/*")) },
            )
            ExpressivePrimaryButton(
                text = "流式解压、计算 Hashtree 并签名验签",
                icon = Icons.Rounded.Bolt,
                onClick = { vm.signGsiImage(gsiInputPath.trim()) },
                enabled = !state.busy && gsiInputPath.isNotBlank(),
                loading = state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Step 2: Install to DSU
        AnimatedVisibility(
            visible = state.lastGsiReport != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.lastGsiReport?.let { rep ->
                val gib = userdataSlider.roundToInt()
                ExpressiveCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                ) {
                    StepBadgeHeader(
                        stepNumber = "02",
                        title = "已签名 GSI 写入 DSU",
                        subtitle = "AVB 验签已通过，不修改原厂 super 与 userdata",
                    )
                    ExpressivePillChip(
                        text = "Root: ${rep.rootDigest.take(18)}...",
                        icon = Icons.Rounded.CheckCircle,
                        copyValue = rep.rootDigest,
                        onCopied = { onNotify("已复制 Hashtree Root Digest") },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("分配给 GSI 的 Userdata 空间", style = MaterialTheme.typography.labelLarge)
                            ExpressivePillChip(
                                text = "$gib GiB",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Slider(
                            value = userdataSlider,
                            onValueChange = { userdataSlider = it },
                            valueRange = 4f..64f,
                            steps = 14,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(8, 16, 32).forEach { preset ->
                                ExpressivePillChip(
                                    text = "${preset}G",
                                    containerColor = if (gib == preset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = if (gib == preset) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    onClick = { userdataSlider = preset.toFloat() },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("单次启动模式（-s 防变砖）", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "开启后如 GSI 启动异常，强制重启一次自动回退 OriginOS",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = singleBoot, onCheckedChange = { singleBoot = it })
                    }
                    ExpressivePrimaryButton(
                        text = "通过原生 gsi_tool 安装 DSU",
                        icon = Icons.Rounded.SystemUpdate,
                        onClick = {
                            vm.installSignedDsu(
                                signedPath = rep.outputPath,
                                userdataGiB = gib,
                                singleBoot = singleBoot,
                            )
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Control Deck
        ExpressiveCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("DSU 控制台", style = MaterialTheme.typography.titleMedium)
                ExpressivePillChip(
                    text = state.dsuStatusText,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { vm.runDsuSimple("status") },
                    enabled = !state.busy,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("刷新")
                }
                OutlinedButton(
                    onClick = { vm.runDsuSimple("disable") },
                    enabled = !state.busy,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("禁用")
                }
                OutlinedButton(
                    onClick = { confirmWipe = true },
                    enabled = !state.busy,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("清理", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(92.dp))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("彻底清理 DSU？") },
            text = { Text("将删除 /data/gsi/dsu 下的所有 GSI 镜像和独立 userdata，不影响当前 OriginOS 数据。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmWipe = false
                        vm.runDsuSimple("wipe")
                    },
                    shape = CircleShape,
                ) { Text("确认清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("取消") }
            },
        )
    }
}
