package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.WizardCalloutCard
import io.github.fu.avbforge.ui.components.WizardScaffold
import io.github.fu.avbforge.ui.components.WizardStepIntro
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File

/**
 * Full-screen 3-Step Guided Wizard: Lossless Update / Switch KernelSU on a Locked Yellow Device.
 * - Step 1: Auto-backup live init_boot & strip existing KSU LKM (`init.real -> init`) to export a clean stock base
 * - Step 2: Patch with ANY user-chosen KSU manager (Official / Next / SukiSU), pick back, auto-preserve DSU pubkey
 * - Step 3: Re-sign preserving original AVB metadata, offline verify, and single-flash 8 MB init_boot (ZERO data wipe!)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateKsuWizard(
    state: UiState,
    vm: AvbForgeViewModel,
    onOpenLogs: () -> Unit,
    onExit: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val snap = state.snapshot
    val activeSlot = snap?.activeSlot?.ifBlank { "_b" } ?: "_b"
    val isYellow = snap?.verifiedBootState.equals("yellow", ignoreCase = true)
    val defaultKey = vm.vault.getDefaultKey()
    val keyMatchesVbmeta = defaultKey != null &&
        defaultKey.sha1.equals(snap?.vbmetaPubkeySha1, ignoreCase = true)
    val dsuAlreadyPresent = snap?.initBootAvbpubkeys?.isNotEmpty() == true
    val fstabNames = snap?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()

    var step by remember { mutableIntStateOf(0) }
    var patchedPath by remember { mutableStateOf("") }
    var preserveDsuKey by remember { mutableStateOf(true) }
    var dsuKeyName by remember {
        mutableStateOf(
            snap?.initBootAvbpubkeys?.firstOrNull()?.substringAfterLast('/')
                ?: fstabNames.firstOrNull()
                ?: "q-gsi.avbpubkey"
        )
    }
    var confirmFlash by remember { mutableStateOf(false) }

    val stepTitles = remember {
        listOf("导出纯净底包", "修补并导回", "验签与无损单刷")
    }

    val ksuPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                patchedPath = it
                onNotify("已选择 KSU 修补产物：${File(it).name}")
            }
        }
    }

    WizardScaffold(
        wizardTitle = "无损更新 / 更换 KernelSU",
        stepIndex = step,
        totalSteps = stepTitles.size,
        stepTitles = stepTitles,
        state = state,
        onClearError = vm::clearError,
        onOpenLogs = onOpenLogs,
        onExit = onExit,
        onBack = { if (step > 0) step-- },
        onNext = if (step < stepTitles.lastIndex) {
            { step++ }
        } else {
            null
        },
        nextLabel = when (step) {
            0 -> if (state.cleanInitBootReport != null) "下一步：导入修补产物" else "已有纯净底包，跳过此步"
            1 -> if (patchedPath.isBlank()) "请先选择修补后的镜像" else "下一步：验签与写入"
            else -> "下一步"
        },
        nextEnabled = when (step) {
            0 -> true
            1 -> patchedPath.isNotBlank()
            else -> false
        },
    ) { currentStep ->
        when (currentStep) {
            // STEP 1: Export Clean Stock init_boot (Auto-Backup & Auto-Strip old KSU!)
            0 -> {
                WizardStepIntro(
                    stepNumber = 1,
                    title = "准备纯净 init_boot 底包",
                    description = "无论你想升级新版官版 KSU，还是切换到 KSU Next / SukiSU，第一步都要先拿一份不含旧 KSU 的干净底包。",
                )

                WizardCalloutCard(
                    title = "严禁在回锁机上点击 KSU 管理器的「直接安装」",
                    icon = Icons.Rounded.WarningAmber,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    bullets = listOf(
                        "KSU 管理器的「直接安装」会写入未经过你私钥签名的 init_boot，在 Yellow 回锁状态下重启会直接无法启动！",
                        "此外，若当前 init_boot 已载入旧版 KernelSU，切勿直接对它二次叠打修补。",
                        "点击下方按钮：本应用会先完整备份当前可用的 init_boot，再自动剥离旧版 KSU（还原 init.real → init 并移除 kernelsu.ko），导出 100% 干净的原厂底包。",
                    ),
                )

                if (!isYellow) {
                    WizardCalloutCard(
                        title = "提示：当前系统并非 Yellow 自签名状态",
                        icon = Icons.Rounded.WarningAmber,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        body = "本向导用于「已经进入 Yellow 自签名系统」后的日常无损更新。若你是首次迁移，请返回主页使用「首次迁移到自签名系统」向导。",
                    )
                }

                M3EGroupRow(
                    title = "当前槽位 ($activeSlot) 状态检测",
                    subtitle = buildString {
                        append("KernelSU：${snap?.ksuVersion?.substringBefore(" (") ?: if (snap?.initBootHasKsu == true) "已载入" else "未检测到"}")
                        append(" · DSU 公钥：${if (dsuAlreadyPresent) "已注入（稍后自动保留）" else "未注入"}")
                    },
                    icon = Icons.Rounded.Memory,
                    helpDoc = HelpDocs.KSU_STRIP_EXPLAIN,
                )

                M3EPrimaryButton(
                    text = "自动备份并导出纯净 init_boot.img",
                    icon = Icons.Rounded.FileDownload,
                    onClick = { vm.exportCleanInitBootForKsu(stripKsu = true) },
                    enabled = !state.busy,
                    loading = state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                state.cleanInitBootReport?.let { rep ->
                    WizardCalloutCard(
                        title = if (rep.strippedKernelsu) "已自动剥离旧 KSU，纯净底包就绪" else "纯净 init_boot 已导出",
                        icon = Icons.Rounded.CheckCircle,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        bullets = listOf(
                            "纯净底包路径：${rep.outputPath}",
                            rep.backupPath?.let { "当前工作备份：$it" } ?: "已保存在 Download/AVBForge",
                            "SHA-256：${rep.sha256.take(24)}...",
                        ),
                    )
                }
            }

            // STEP 2: Patch with User's Chosen KSU Manager & Pick Back
            1 -> {
                WizardStepIntro(
                    stepNumber = 2,
                    title = "用自选 KSU 管理器修补并导回",
                    description = "现在你可以切到桌面使用任意偏爱的 KernelSU 管理器修补底包，完成后回到这里选回产物。",
                )

                WizardCalloutCard(
                    title = "操作指引（约 30 秒）",
                    icon = Icons.Rounded.Memory,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    bullets = listOf(
                        "① 打开你想用的管理器（官方 KernelSU / KernelSU Next / SukiSU 等）。",
                        "② 点击『安装』→『选择并修补一个文件』（切勿选直接安装！）。",
                        "③ 在 Download/AVBForge 中选中刚才导出的 ${state.cleanInitBootReport?.outputPath?.let { File(it).name } ?: "init_boot_clean_for_ksu_*.img"}。",
                        "④ 等待修补生成 kernelsu_patched_*.img 后，切回 AVBForge 在下方选回该文件。",
                    ),
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    M3EGroupRow(
                        title = if (patchedPath.isBlank()) "选择 KSU 修补后的镜像文件" else "已选：${File(patchedPath).name}",
                        subtitle = patchedPath.ifBlank { "点击打开文件选择器，选取 kernelsu_patched_*.img" },
                        icon = Icons.Rounded.UploadFile,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        iconBg = MaterialTheme.colorScheme.secondaryContainer,
                        shape = m3eGroupShape(0, 2),
                        showChevron = true,
                        monospaceSubtitle = patchedPath.isNotBlank(),
                        onClick = { ksuPicker.launch(arrayOf("*/*")) },
                    )
                    M3EGroupRow(
                        title = if (dsuAlreadyPresent) "自动保留现有 DSU 公钥（强烈推荐）" else "顺带注入 DSU 公钥",
                        subtitle = if (preserveDsuKey) {
                            "目标：$dsuKeyName · 确保更新 KSU 后 DSU 功能依然正常"
                        } else {
                            "不注入 DSU 公钥，仅对 KSU 镜像进行 AVB 自签名"
                        },
                        icon = Icons.Rounded.Key,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBg = MaterialTheme.colorScheme.primaryContainer,
                        shape = m3eGroupShape(1, 2),
                        helpDoc = HelpDocs.DSU_PUBKEY,
                        trailing = {
                            Switch(checked = preserveDsuKey, onCheckedChange = { preserveDsuKey = it })
                        },
                    )
                }

                AnimatedVisibility(visible = preserveDsuKey && fstabNames.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        fstabNames.forEach { kname ->
                            M3EPillChip(
                                text = kname,
                                containerColor = if (dsuKeyName == kname) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                                contentColor = if (dsuKeyName == kname) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { dsuKeyName = kname },
                            )
                        }
                    }
                }
            }

            // STEP 3: Re-sign, Verify & Single-Flash init_boot (Zero Data Loss)
            2 -> {
                WizardStepIntro(
                    stepNumber = 3,
                    title = "自签名验签并单刷 init_boot",
                    description = "只需更新 8 MB 的 init_boot 分区，自动沿用原分区 AVB 元数据，不会清除你的任何手机数据。",
                )

                if (keyMatchesVbmeta) {
                    WizardCalloutCard(
                        title = "无损单刷就绪 · 不会清空用户数据",
                        icon = Icons.Rounded.Shield,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        bullets = listOf(
                            "密钥核对通过：默认私钥「${defaultKey?.alias}」与本机 vbmeta 公钥指纹 100% 一致。",
                            "仅刷写当前活动槽位 ($activeSlot) 的 init_boot（8 MB），重启后新版本 KernelSU 立即生效，个人数据零损失。",
                        ),
                    )
                } else {
                    WizardCalloutCard(
                        title = "警告：默认私钥与本机 vbmeta 公钥不一致！",
                        icon = Icons.Rounded.WarningAmber,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        bullets = listOf(
                            if (defaultKey == null) "当前尚未在「工具 → 密钥库」导入本机对应的私钥。"
                            else "默认密钥「${defaultKey.alias}」(${defaultKey.sha1.take(12)}...) 与实机 vbmeta (${snap?.vbmetaPubkeySha1?.take(12) ?: "未知"}...) 不匹配。",
                            "强行用不匹配的私钥单刷 init_boot 会导致 Bootloader 验签失败无法开机！请先到「工具 → 密钥库」导入/切换正确的默认私钥。",
                        ),
                    )
                }

                M3EPrimaryButton(
                    text = "① 自签名并离线验签 init_boot",
                    icon = Icons.Rounded.AutoFixHigh,
                    onClick = {
                        vm.prepareUpdatedKsuInitBoot(
                            patchedImgPath = patchedPath.trim(),
                            preserveDsuKey = preserveDsuKey,
                            dsuKeyName = dsuKeyName.trim(),
                        )
                    },
                    enabled = !state.busy && patchedPath.isNotBlank() && keyMatchesVbmeta,
                    loading = state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                state.lastKsuUpdateReport?.let { rep ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("离线自签名与 Ramdisk 回环校验通过", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "KernelSU 检测：${if (rep.hasKernelsu) "已包含 (正常)" else "未检测到 KSU！"}\nDSU 公钥：${if (rep.injectedPaths.isNotEmpty()) "已注入 (${rep.injectedPaths.first().substringAfterLast('/')})" else "未注入"}\nSHA-256：${rep.sha256.take(28)}...",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            M3EPrimaryButton(
                                text = "② 写入当前槽位 ($activeSlot) init_boot 并回读核验",
                                icon = Icons.Rounded.FlashOn,
                                onClick = { confirmFlash = true },
                                enabled = !state.busy && keyMatchesVbmeta,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmFlash && state.lastKsuUpdateReport != null) {
        AlertDialog(
            onDismissRequest = { confirmFlash = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("确认写入新版 KSU init_boot？") },
            text = {
                Text("仅写入活动槽位 $activeSlot 的 init_boot（8 MB），使用与本机一致的密钥签名，不会清空用户数据。写入后会自动清缓存回读核验 SHA-256 与 AVB 签名，完成后手动重启即可生效。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmFlash = false
                        vm.flashSingleInitBoot(
                            imagePath = state.lastKsuUpdateReport.outputPath,
                            successMessage = "新版 KSU init_boot 已写入并通过回读验签！现在重启手机即可生效（数据完好）。",
                        )
                    },
                    shape = CircleShape,
                ) { Text("确认写入") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFlash = false }) { Text("取消") }
            },
        )
    }
}
