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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EHelpButton
import io.github.fu.avbforge.ui.components.M3EHeroCard
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File
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
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                gsiInputPath = it
                onNotify("已选择 GSI：${File(it).name}")
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // DSU Status Hero Card with Inline Controls
        M3EHeroCard(
            title = "DSU 状态：${state.dsuStatusText}",
            subtitle = "无损隔离运行第三方 GSI，不影响原厂系统与用户数据",
            icon = Icons.Rounded.SystemUpdate,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
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
                FilledTonalButton(
                    onClick = { vm.runDsuSimple("disable") },
                    enabled = !state.busy,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("禁用")
                }
                FilledTonalButton(
                    onClick = { confirmWipe = true },
                    enabled = !state.busy,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("清理")
                }
            }
        }

        // Step 1: Pick & Sign GSI (Clean Group Row!)
        M3ESectionLabel("体验第三方 GSI", helpDoc = HelpDocs.GSI_SIGN)
        M3EGroupRow(
            title = if (gsiInputPath.isBlank()) "选择 arm64-ab GSI 镜像" else File(gsiInputPath).name,
            subtitle = gsiInputPath.ifBlank { "支持 .img / .img.xz / .img.gz 与 Android Sparse" },
            icon = Icons.Rounded.FolderOpen,
            shape = m3eGroupShape(0, 1),
            showChevron = true,
            monospaceSubtitle = gsiInputPath.isNotBlank(),
            onClick = { gsiPicker.launch(arrayOf("*/*")) },
        )

        AnimatedVisibility(
            visible = gsiInputPath.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            M3EPrimaryButton(
                text = "多线程计算 Hashtree 并签名验签",
                icon = Icons.Rounded.Bolt,
                onClick = { vm.signGsiImage(gsiInputPath.trim()) },
                enabled = !state.busy,
                loading = state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Step 2: Signed GSI Ready -> Configure & Install
        AnimatedVisibility(
            visible = state.lastGsiReport != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.lastGsiReport?.let { rep ->
                val gib = userdataSlider.roundToInt()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    M3ESectionLabel("安装已签名 GSI")
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        M3EGroupRow(
                            title = "AVB 签名与 Hashtree 校验通过",
                            subtitle = "Root: ${rep.rootDigest.take(24)}...",
                            icon = Icons.Rounded.CheckCircle,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            iconBg = MaterialTheme.colorScheme.secondaryContainer,
                            shape = m3eGroupShape(0, 3),
                            monospaceSubtitle = true,
                            onClick = { onNotify("已签镜像：${rep.outputPath}") },
                        )
                        Surface(
                            shape = m3eGroupShape(1, 3),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Text("独立 Userdata 大小", style = MaterialTheme.typography.titleMedium)
                                        M3EHelpButton(doc = HelpDocs.DSU_USERDATA)
                                    }
                                    M3EPillChip(
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
                                        M3EPillChip(
                                            text = "${preset}G",
                                            containerColor = if (gib == preset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = if (gib == preset) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            onClick = { userdataSlider = preset.toFloat() },
                                        )
                                    }
                                }
                            }
                        }
                        M3EGroupRow(
                            title = "单次启动模式（-s 防变砖）",
                            subtitle = "如 GSI 启动异常，强制重启一次自动回退 OriginOS",
                            icon = Icons.Rounded.Shield,
                            shape = m3eGroupShape(2, 3),
                            helpDoc = HelpDocs.DSU_SINGLE_BOOT,
                            trailing = {
                                Switch(checked = singleBoot, onCheckedChange = { singleBoot = it })
                            },
                        )
                    }

                    M3EPrimaryButton(
                        text = "通过原生 gsi_tool 安装到 DSU",
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

        Spacer(Modifier.height(96.dp))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("彻底清理 DSU？") },
            text = { Text("将删除 /data/gsi/dsu 下的所有 GSI 镜像与独立 userdata，不影响当前 OriginOS 数据。") },
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
