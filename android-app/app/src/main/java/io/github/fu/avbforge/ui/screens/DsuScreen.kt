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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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

/**
 * Dedicated First-Time DSU Setup + GSI Signing & Management Page:
 * Section 1: First-Time DSU Enablement (1-click inject pubkey into active init_boot, auto-collapsed once ready!)
 * Section 2: Sign & Install New GSI via native gsi_tool
 * Section 3: Manage Current DSU (status / disable / wipe)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DsuScreen(
    state: UiState,
    vm: AvbForgeViewModel,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val snap = state.snapshot
    val dsuReady = snap?.initBootAvbpubkeys?.isNotEmpty() == true
    val fstabNames = snap?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()
    var targetKeyName by remember { mutableStateOf(fstabNames.firstOrNull() ?: "q-gsi.avbpubkey") }
    var expandReinject by remember { mutableStateOf(false) }
    var confirmFlashDsuInit by remember { mutableStateOf(false) }

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
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Section 1: First-Time DSU Enablement (inject avbpubkey into current init_boot)
        M3ESectionLabel("1 · 首次使用 DSU 适配", helpDoc = HelpDocs.DSU_FIRST_SETUP)

        if (dsuReady && !expandReinject) {
            M3EGroupRow(
                title = "本机已支持自签名 DSU",
                subtitle = "init_boot 已包含 ${snap?.initBootAvbpubkeys?.firstOrNull()?.substringAfterLast('/') ?: "avbpubkey"} · 可直接往下签名并安装 GSI",
                icon = Icons.Rounded.CheckCircle,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconBg = MaterialTheme.colorScheme.secondaryContainer,
                trailingText = "重新注入",
                onClick = { expandReinject = true },
            )
        } else {
            M3EHeroCard(
                title = if (dsuReady) "重新注入 DSU 公钥" else "首次启用自签名 DSU",
                subtitle = "仅备份并修改当前槽位 init_boot（8 MB），保留现有 KSU 补丁，不会清除用户数据",
                icon = Icons.Rounded.Key,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                if (fstabNames.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        fstabNames.forEach { kname ->
                            M3EPillChip(
                                text = kname,
                                containerColor = if (targetKeyName == kname) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f)
                                },
                                contentColor = if (targetKeyName == kname) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                onClick = { targetKeyName = kname },
                            )
                        }
                    }
                }
                M3EPrimaryButton(
                    text = "备份并生成适配 DSU 的 init_boot",
                    icon = Icons.Rounded.AutoFixHigh,
                    onClick = { vm.prepareDsuInitBoot(targetKeyName) },
                    enabled = !state.busy,
                    loading = state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AnimatedVisibility(
            visible = state.lastDsuPatchReport != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.lastDsuPatchReport?.let { rep ->
                M3EGroupRow(
                    title = "DSU init_boot 已验签就绪",
                    subtitle = "KSU保留=${rep.hasKernelsu} · 点击写入当前槽位 init_boot（不清空数据）",
                    icon = Icons.Rounded.FlashOn,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    iconBg = MaterialTheme.colorScheme.secondaryContainer,
                    trailingText = "写入",
                    onClick = { confirmFlashDsuInit = true },
                )
            }
        }

        // Section 2: Sign & Install New GSI
        M3ESectionLabel("2 · 签名并安装新 GSI", helpDoc = HelpDocs.GSI_SIGN)
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

        AnimatedVisibility(
            visible = state.lastGsiReport != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.lastGsiReport?.let { rep ->
                val gib = userdataSlider.roundToInt()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        M3EGroupRow(
                            title = "GSI 签名与 Hashtree 校验通过",
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

        // Section 3: Manage Current DSU
        M3ESectionLabel("3 · 管理本机 DSU（当前：${state.dsuStatusText}）")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3EGroupRow(
                title = "刷新 DSU 状态",
                subtitle = "查询 /system/bin/gsi_tool status",
                icon = Icons.Rounded.Refresh,
                shape = m3eGroupShape(0, 3),
                showChevron = true,
                onClick = { if (!state.busy) vm.runDsuSimple("status") },
            )
            M3EGroupRow(
                title = "禁用已安装的 DSU",
                subtitle = "保留镜像文件但下次重启不再进入 GSI",
                icon = Icons.Rounded.StopCircle,
                shape = m3eGroupShape(1, 3),
                showChevron = true,
                onClick = { if (!state.busy) vm.runDsuSimple("disable") },
            )
            M3EGroupRow(
                title = "彻底清理 DSU 与空间",
                subtitle = "删除 /data/gsi/dsu 下所有 GSI 与虚拟 userdata，不影响原系统",
                icon = Icons.Rounded.DeleteForever,
                iconTint = MaterialTheme.colorScheme.error,
                iconBg = MaterialTheme.colorScheme.errorContainer,
                shape = m3eGroupShape(2, 3),
                showChevron = true,
                onClick = { if (!state.busy) confirmWipe = true },
            )
        }

        Spacer(Modifier.height(96.dp))
    }

    if (confirmFlashDsuInit && state.lastDsuPatchReport != null) {
        AlertDialog(
            onDismissRequest = { confirmFlashDsuInit = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("写入适配 DSU 的 init_boot？") },
            text = {
                Text("仅写入当前活动槽位 init_boot（8 MB），保留 KernelSU，不清除用户数据。写入后会自动清缓存回读验签，完成后重启一次手机即可生效。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmFlashDsuInit = false
                        vm.flashPreparedDsuInitBoot(state.lastDsuPatchReport.outputPath)
                    },
                    shape = CircleShape,
                ) { Text("确认写入并校验") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFlashDsuInit = false }) { Text("取消") }
            },
        )
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
