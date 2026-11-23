package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.WizardCalloutCard
import io.github.fu.avbforge.ui.components.WizardScaffold
import io.github.fu.avbforge.ui.components.WizardStepIntro
import io.github.fu.avbforge.ui.components.formatByteSize
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File
import kotlin.math.roundToInt

/**
 * Full-screen DSU / GSI Studio Wizard:
 * - Automatic Pre-check Step when device init_boot doesn't yet have DSU avbpubkey injected
 * - 3 Focused Steps once ready: (1) Pick GSI + Selection Guide -> (2) Multithreaded Sign & Verify -> (3) Userdata + Single-Boot Install
 * - Top-right "管理 DSU" BottomSheet for status / disable / wipe / re-inject pubkey
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DsuWizard(
    state: UiState,
    vm: AvbForgeViewModel,
    onOpenLogs: () -> Unit,
    onExit: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val snap = state.snapshot
    val dsuReady = snap?.initBootAvbpubkeys?.isNotEmpty() == true
    val isYellow = snap?.verifiedBootState.equals("yellow", ignoreCase = true)
    val defaultKey = vm.vault.getDefaultKey()
    val keyMatchesVbmeta = defaultKey != null &&
        defaultKey.sha1.equals(snap?.vbmetaPubkeySha1, ignoreCase = true)
    val fstabNames = snap?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()

    var showSetupMode by remember(dsuReady) { mutableStateOf(!dsuReady) }
    var targetKeyName by remember { mutableStateOf(fstabNames.firstOrNull() ?: "q-gsi.avbpubkey") }
    var confirmFlashDsuInit by remember { mutableStateOf(false) }

    var step by remember { mutableIntStateOf(0) }
    var gsiInputPath by remember { mutableStateOf("") }
    var fixVendorGenfs by remember { mutableStateOf(true) }
    var userdataSlider by remember { mutableFloatStateOf(16f) }
    var singleBoot by remember { mutableStateOf(true) }

    var showManageSheet by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    val gsiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                gsiInputPath = it
                vm.clearLastGsiReport()
                onNotify("已选择 GSI：${File(it).name}")
            }
        }
    }

    val stepTitles = if (showSetupMode) {
        listOf("首次启用自签名 DSU")
    } else {
        listOf("选择 GSI 镜像", "计算与自签名", "配置空间与安装")
    }

    WizardScaffold(
        wizardTitle = if (showSetupMode) "首次适配 DSU" else "体验第三方 GSI (DSU)",
        stepIndex = if (showSetupMode) 0 else step,
        totalSteps = stepTitles.size,
        stepTitles = stepTitles,
        state = state,
        onClearError = vm::clearError,
        onOpenLogs = onOpenLogs,
        onExit = onExit,
        onBack = {
            if (!showSetupMode && step > 0) step-- else onExit()
        },
        onNext = when {
            showSetupMode -> {
                { showSetupMode = false }
            }
            step < 2 -> {
                { step++ }
            }
            else -> null
        },
        nextLabel = when {
            showSetupMode -> "跳过适配，直接去签名 GSI"
            step == 0 -> if (gsiInputPath.isBlank()) "请先选择 GSI 镜像" else "下一步：多线程签名"
            step == 1 -> if (state.lastGsiReport == null) "请先完成签名验签" else "下一步：配置并安装"
            else -> "下一步"
        },
        nextEnabled = when {
            showSetupMode -> true
            step == 0 -> gsiInputPath.isNotBlank()
            step == 1 -> state.lastGsiReport != null
            else -> false
        },
        topBarActions = {
            IconButton(onClick = { showManageSheet = true }) {
                Icon(Icons.Rounded.Tune, contentDescription = "管理本机 DSU")
            }
        },
    ) { currentStep ->
        if (showSetupMode) {
            // PRE-CHECK / FIRST-TIME DSU ENABLEMENT
            WizardStepIntro(
                stepNumber = 1,
                title = "首次启用自签名 DSU 支持",
                description = "国行 OriginOS 默认没有内置 DSU 校验公钥，只需向 init_boot 注入你的公钥，即可永久解锁无损体验 GSI。",
            )

            WizardCalloutCard(
                title = "完全无损 · 保留 KernelSU · 不清空数据",
                icon = Icons.Rounded.Shield,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                bullets = listOf(
                    "只修改当前活动槽位的 init_boot（8 MB），不碰其他分区，不会格式化用户数据。",
                    "自动先备份当前工作中的 init_boot 到 Download/AVBForge，100% 保留你现有的 KernelSU Root 补丁。",
                    "将你的 avb_pkmd.bin 无损写入 ramdisk 的 /avb/$targetKeyName 并用同一把私钥重签验签。",
                    "刷回并重启生效一次后，以后就能无限次切换各种第三方 GSI 系统！",
                ),
            )

            if (!isYellow || !keyMatchesVbmeta) {
                WizardCalloutCard(
                    title = if (!isYellow) "前置条件未满足：需先处于 Yellow 自签名状态" else "密钥未匹配：请先导入与本机一致的私钥",
                    icon = Icons.Rounded.WarningAmber,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    body = if (!isYellow) {
                        "当前系统为原厂 ${snap?.verifiedBootState ?: "Green"} 状态，需先通过主页的「首次迁移到自签名系统」完成自签名后才能启用回锁 DSU。"
                    } else {
                        "请在「工具 → 密钥库」中导入与本机 vbmeta 公钥一致的默认私钥后再继续，否则写入 init_boot 会导致验签失败。"
                    },
                )
            }

            if (fstabNames.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("选择要注入的公钥文件名（自动读取自 fstab）", style = MaterialTheme.typography.titleSmall)
                            M3EHelpButton(doc = HelpDocs.DSU_PUBKEY)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            fstabNames.forEach { kname ->
                                M3EPillChip(
                                    text = kname,
                                    containerColor = if (targetKeyName == kname) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = if (targetKeyName == kname) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = { targetKeyName = kname },
                                )
                            }
                        }
                    }
                }
            }

            M3EPrimaryButton(
                text = "① 备份并生成适配 DSU 的 init_boot",
                icon = Icons.Rounded.AutoFixHigh,
                onClick = { vm.prepareDsuInitBoot(targetKeyName) },
                enabled = !state.busy && isYellow && keyMatchesVbmeta,
                loading = state.busy,
                modifier = Modifier.fillMaxWidth(),
            )

            state.lastDsuPatchReport?.let { rep ->
                WizardCalloutCard(
                    title = "DSU init_boot 生成并验签通过",
                    icon = Icons.Rounded.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    body = "KSU 保留：${if (rep.hasKernelsu) "是" else "否"} · 注入路径：${rep.injectedPaths.joinToString(", ")}",
                )
                M3EPrimaryButton(
                    text = "② 写入活动槽位 init_boot 并回读校验",
                    icon = Icons.Rounded.FlashOn,
                    onClick = { confirmFlashDsuInit = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            when (currentStep) {
                // STEP 1: Pick GSI & Clear Selection Guide
                0 -> {
                    WizardStepIntro(
                        stepNumber = 1,
                        title = "选择 GSI 镜像文件",
                        description = "DSU 会在手机内部独立空间启动一个全新的类原生系统，完全不影响原厂 OriginOS 数据。",
                    )

                    WizardCalloutCard(
                        title = "iQOO Neo9 选包指南（避免下载错包）",
                        icon = Icons.Rounded.Info,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        bullets = listOf(
                            "架构与分区：必须选择 arm64 + A/B (ab) 版本，例如 arm64_bgN 或 arm64_bvN。",
                            "Root 变体：推荐选择后缀为 N（无内置 su）的包，不要选带内置 su (S) 的包，兼容更稳。",
                            "支持格式：可直接选择 .img、.img.xz、.img.gz 以及 Android Sparse 稀疏镜像，引擎会自动流式解压。",
                            "常见推荐：基于 TrebleDroid 的 Android 14 / 15 / 16 / 17 GSI（如 Ponces AOSP、LineageOS TD、Infinity-X 等）；下一步开启「自动修复 SELinux 冲突」即可自动消除高版本 GSI 与本机 Vendor 33 的策略冲突。",
                        ),
                    )

                    val pickedFile = gsiInputPath.takeIf { it.isNotBlank() }?.let { File(it) }
                    M3EGroupRow(
                        title = pickedFile?.name ?: "点击选择 arm64-ab GSI 镜像",
                        subtitle = if (pickedFile != null && pickedFile.exists()) {
                            "${pickedFile.absolutePath}（${formatByteSize(pickedFile.length())}）"
                        } else {
                            "支持 .img / .img.xz / .img.gz / Sparse"
                        },
                        icon = Icons.Rounded.FolderOpen,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBg = MaterialTheme.colorScheme.primaryContainer,
                        showChevron = true,
                        monospaceSubtitle = pickedFile != null,
                        onClick = { gsiPicker.launch(arrayOf("*/*")) },
                    )
                }

                // STEP 2: Multithreaded Hashtree & AVB Signing
                1 -> {
                    WizardStepIntro(
                        stepNumber = 2,
                        title = "多线程计算 Hashtree 并自签名",
                        description = "在回锁 Yellow 状态下，DSU 仅信任由你私钥签名的 system 镜像。引擎将在本地离线完成解压、建树、签名与全树验签。",
                    )

                    WizardCalloutCard(
                        title = "Rust 原生引擎处理流程",
                        icon = Icons.Rounded.Bolt,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        bullets = listOf(
                            "流式解压 .xz / .gz 并自动展开 Android Sparse 镜像，低内存占用不 OOM。",
                            "使用多核并行计算 4096 B 分块的 dm-verity SHA-256 Merkle Hashtree。",
                            "使用默认私钥「${defaultKey?.alias ?: "未配置"}」追加 AVB Footer，并执行一次强制离线全树校验。",
                        ),
                    )

                    M3EGroupRow(
                        title = "自动修复 Android 15/16/17 SELinux 冲突（推荐）",
                        subtitle = if (fixVendorGenfs) {
                            "自动检测并剔除与本机 vendor_sepolicy.cil 冲突的 plat_sepolicy_genfs 规则，无冲突则零改动跳过。"
                        } else {
                            "已关闭：仅解压并签名，不做 SELinux 策略冲突检测（无需 Root）。"
                        },
                        icon = Icons.Rounded.AutoFixHigh,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBg = MaterialTheme.colorScheme.primaryContainer,
                        helpDoc = HelpDocs.GSI_GENFS_FIX,
                        trailing = {
                            Switch(checked = fixVendorGenfs, onCheckedChange = { fixVendorGenfs = it })
                        },
                    )

                    M3EPrimaryButton(
                        text = if (state.lastGsiReport == null) "开始计算 Hashtree 并签名验签" else "重新签名所选 GSI",
                        icon = Icons.Rounded.Bolt,
                        onClick = { vm.signGsiImage(gsiInputPath.trim(), fixVendorGenfs = fixVendorGenfs) },
                        enabled = !state.busy && gsiInputPath.isNotBlank() && defaultKey != null,
                        loading = state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val isRawImg = gsiInputPath.endsWith(".img", ignoreCase = true)
                    if (isRawImg) {
                        OutlinedButton(
                            onClick = { vm.verifyExistingSignedGsi(gsiInputPath.trim()) },
                            enabled = !state.busy && defaultKey != null,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = "已是本机密钥签过的镜像：仅验签并跳过重签",
                                modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                            )
                        }
                    }

                    state.lastGsiReport?.let { rep ->
                        WizardCalloutCard(
                            title = if (rep.rootDigest == "verified-existing-image") {
                                "已有自签名镜像验签通过（已跳过重签）！"
                            } else {
                                "GSI 自签名与全树校验通过！"
                            },
                            icon = Icons.Rounded.CheckCircle,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            bullets = if (rep.rootDigest == "verified-existing-image") {
                                listOf(
                                    "镜像体积：${formatByteSize(rep.finalSize)}",
                                    "签名密钥匹配本机默认私钥，无需重复签名",
                                    "镜像路径：${rep.outputPath}",
                                )
                            } else {
                                buildList {
                                    add("原始镜像：${formatByteSize(rep.originalSize)} → 签后体积：${formatByteSize(rep.finalSize)}")
                                    add("Hashtree 大小：${formatByteSize(rep.treeSize)}")
                                    if (rep.genfsConflictsPatched > 0) {
                                        add("SELinux 冲突自动修复：已剔除 ${rep.genfsConflictsPatched} 条与本机 Vendor 冲突的 genfscon 规则")
                                    }
                                    add("Root Digest：${rep.rootDigest.take(28)}...")
                                    add("输出文件：${rep.outputPath}")
                                }
                            },
                        )
                    }
                }

                // STEP 3: Configure Userdata & Single-Boot Install
                2 -> {
                    val rep = state.lastGsiReport
                    val gib = userdataSlider.roundToInt()

                    WizardStepIntro(
                        stepNumber = 3,
                        title = "配置独立空间并安装到 DSU",
                        description = "为临时 GSI 分配一块与原系统完全隔离的虚拟存储，再通过系统原生 gsi_tool 写入。",
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            Text(
                                text = "带 GApps 的 Android 15/16 GSI 建议至少 16 GiB；退出或清理 DSU 后空间会自动释放，绝不碰原系统数据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        title = "单次启动模式（-s 防变砖保险）",
                        subtitle = if (singleBoot) {
                            "推荐开启：若 GSI 黑屏或卡开机，长按电源键强制重启一次即可自动回到原厂 OriginOS！"
                        } else {
                            "已关闭：每次重启都会继续进入 GSI，直到手动禁用或清理。"
                        },
                        icon = Icons.Rounded.Shield,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        iconBg = MaterialTheme.colorScheme.secondaryContainer,
                        helpDoc = HelpDocs.DSU_SINGLE_BOOT,
                        trailing = {
                            Switch(checked = singleBoot, onCheckedChange = { singleBoot = it })
                        },
                    )

                    if (rep != null) {
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
                            loading = state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.dsuStatusText.contains("installed", ignoreCase = true) ||
                        state.dsuStatusText.contains("enabled", ignoreCase = true)
                    ) {
                        WizardCalloutCard(
                            title = "DSU 当前状态：${state.dsuStatusText}",
                            icon = Icons.Rounded.CheckCircle,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            body = "现在手动重启手机即可直接进入你刚安装的第三方 GSI！如需退出，直接再次重启即可回到 OriginOS。",
                        )
                    }
                }
            }
        }
    }

    // Top-right Manage DSU BottomSheet
    if (showManageSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showManageSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("管理本机 DSU（当前：${state.dsuStatusText}）", style = MaterialTheme.typography.titleLarge)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    M3EGroupRow(
                        title = "刷新 DSU 状态",
                        subtitle = "查询 /system/bin/gsi_tool status",
                        icon = Icons.Rounded.Refresh,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(0, 4),
                        showChevron = true,
                        onClick = {
                            showManageSheet = false
                            if (!state.busy) vm.runDsuSimple("status")
                        },
                    )
                    M3EGroupRow(
                        title = "重新注入 / 更换 DSU 公钥",
                        subtitle = "如果更换过私钥或需要重新检查 init_boot 内的 .avbpubkey",
                        icon = Icons.Rounded.Key,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(1, 4),
                        showChevron = true,
                        onClick = {
                            showManageSheet = false
                            showSetupMode = true
                        },
                    )
                    M3EGroupRow(
                        title = "禁用已安装的 DSU",
                        subtitle = "保留镜像文件，但下次重启不再进入 GSI",
                        icon = Icons.Rounded.StopCircle,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(2, 4),
                        showChevron = true,
                        onClick = {
                            showManageSheet = false
                            if (!state.busy) vm.runDsuSimple("disable")
                        },
                    )
                    M3EGroupRow(
                        title = "彻底清理 DSU 与独立空间",
                        subtitle = "删除 /data/gsi/dsu 下所有文件，不影响原厂系统",
                        icon = Icons.Rounded.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        iconBg = MaterialTheme.colorScheme.errorContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(3, 4),
                        showChevron = true,
                        onClick = {
                            showManageSheet = false
                            confirmWipe = true
                        },
                    )
                }
            }
        }
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
