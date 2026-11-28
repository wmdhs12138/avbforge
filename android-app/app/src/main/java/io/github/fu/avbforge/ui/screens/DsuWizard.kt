package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.data.LocalGsiImage
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.DryRunCommandDialog
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.WhiteBoxConsoleCard
import io.github.fu.avbforge.ui.components.M3EHelpButton
import io.github.fu.avbforge.ui.components.M3EHeroCard
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.M3ESectionLabel
import io.github.fu.avbforge.ui.components.WizardCalloutCard
import io.github.fu.avbforge.ui.components.WizardScaffold
import io.github.fu.avbforge.ui.components.WizardStepIntro
import io.github.fu.avbforge.ui.components.formatByteSize
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class DsuViewMode {
    HUB,
    SETUP_KEY,
    INSTALL_WIZARD,
}

/**
 * Full-screen Material 3 Expressive GSI / DSU Studio:
 * - HUB Mode: Live DSU dashboard (Hero status + System/Userdata sizes + AVB key match,
 *   1-tap Enable/Disable/Reboot/Wipe-Data/Wipe, Local GSI Image Library in Download/AVBForge,
 *   and init_boot DSU key management).
 * - SETUP_KEY Mode: First-time lossless DSU pubkey injection into init_boot (preserving KSU).
 * - INSTALL_WIZARD Mode: Focused 3-step GSI installer (Pick -> Auto-Fix SELinux & Sign / Fast-Verify -> Userdata & Install).
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
    val consoleState by vm.consoleState.collectAsState()
    val snap = state.snapshot
    val dsuReady = snap?.initBootAvbpubkeys?.isNotEmpty() == true
    val isYellow = snap?.verifiedBootState.equals("yellow", ignoreCase = true)
    val defaultKey = vm.vault.getDefaultKey()
    val keyMatchesVbmeta = defaultKey != null &&
        defaultKey.sha1.equals(snap?.vbmetaPubkeySha1, ignoreCase = true)
    val fstabNames = snap?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()

    var viewMode by remember(dsuReady) {
        mutableStateOf(if (dsuReady) DsuViewMode.HUB else DsuViewMode.SETUP_KEY)
    }
    var targetKeyName by remember { mutableStateOf(fstabNames.firstOrNull() ?: "q-gsi.avbpubkey") }
    var confirmFlashDsuInit by remember { mutableStateOf(false) }
    var confirmInstallDsu by remember { mutableStateOf(false) }

    var step by remember { mutableIntStateOf(0) }
    var gsiInputPath by remember { mutableStateOf("") }
    var fixVendorGenfs by remember { mutableStateOf(true) }
    var userdataSlider by remember { mutableFloatStateOf(16f) }
    var singleBoot by remember { mutableStateOf(true) }

    var confirmWipe by remember { mutableStateOf(false) }
    var confirmWipeData by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    var activeLocalImage by remember { mutableStateOf<LocalGsiImage?>(null) }
    var pendingDeleteLocalImage by remember { mutableStateOf<LocalGsiImage?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshDsuInfo(silent = true)
    }

    val gsiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                gsiInputPath = it
                vm.clearLastGsiReport()
                onNotify("已选择 GSI：${File(it).name}")
            }
        }
    }

    AnimatedContent(
        targetState = viewMode,
        transitionSpec = {
            val forward = targetState != DsuViewMode.HUB
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(240)) { it / 6 * dir } + fadeIn(tween(200)))
                .togetherWith(slideOutHorizontally(tween(180)) { -it / 8 * dir } + fadeOut(tween(140)))
        },
        label = "dsu_mode_transition",
    ) { mode ->
        when (mode) {
            DsuViewMode.HUB -> {
                BackHandler { onExit() }
                DsuManagementHub(
                    state = state,
                    vm = vm,
                    dsuReady = dsuReady,
                    defaultKeySha1 = defaultKey?.sha1,
                    onRefresh = { vm.refreshDsuInfo(silent = false) },
                    onOpenLogs = onOpenLogs,
                    onExit = onExit,
                    onStartInstallNew = {
                        step = 0
                        viewMode = DsuViewMode.INSTALL_WIZARD
                    },
                    onOpenKeySetup = { viewMode = DsuViewMode.SETUP_KEY },
                    onRequestReboot = { confirmReboot = true },
                    onRequestWipeData = { confirmWipeData = true },
                    onRequestWipe = { confirmWipe = true },
                    onSelectLocalImage = { activeLocalImage = it },
                )
            }

            DsuViewMode.SETUP_KEY -> {
                WizardScaffold(
                    wizardTitle = if (dsuReady) "管理 init_boot DSU 公钥" else "首次适配 DSU",
                    stepIndex = 0,
                    totalSteps = 1,
                    stepTitles = listOf("注入自签名 DSU 公钥"),
                    state = state,
                    onClearError = vm::clearError,
                    onOpenLogs = onOpenLogs,
                    onExit = { if (dsuReady) viewMode = DsuViewMode.HUB else onExit() },
                    onBack = { if (dsuReady) viewMode = DsuViewMode.HUB else onExit() },
                    onNext = { viewMode = DsuViewMode.HUB },
                    nextLabel = if (dsuReady) "返回 GSI 管理中心" else "跳过适配，进入管理中心",
                    nextEnabled = true,
                ) {
                    WizardStepIntro(
                        stepNumber = 1,
                        title = "启用 / 更新自签名 DSU 公钥",
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
                            text = "② 预览命令并写入 init_boot（含回读校验）",
                            icon = Icons.Rounded.FlashOn,
                            onClick = { confirmFlashDsuInit = true },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    WhiteBoxConsoleCard(
                        state = consoleState,
                        onToggleRawMode = vm::setWhiteBoxRawJsonMode,
                        title = "DSU 公钥注入与写入 · 白盒控制台",
                        busy = state.busy,
                        onReboot = { vm.rebootDevice() },
                        rebootLabel = "安全重启使 DSU 公钥生效",
                    )
                }
            }

            DsuViewMode.INSTALL_WIZARD -> {
                val stepTitles = listOf("选择 GSI 镜像", "修复与自签名", "配置空间与安装")
                WizardScaffold(
                    wizardTitle = "安装新的 GSI 镜像",
                    stepIndex = step,
                    totalSteps = stepTitles.size,
                    stepTitles = stepTitles,
                    state = state,
                    onClearError = vm::clearError,
                    onOpenLogs = onOpenLogs,
                    onExit = { viewMode = DsuViewMode.HUB },
                    onBack = {
                        if (step > 0) step-- else viewMode = DsuViewMode.HUB
                    },
                    onNext = if (step < 2) {
                        { step++ }
                    } else null,
                    nextLabel = when (step) {
                        0 -> if (gsiInputPath.isBlank()) "请先选择 GSI 镜像" else "下一步：修复与签名"
                        1 -> if (state.lastGsiReport == null) "请先完成签名或验签" else "下一步：配置并安装"
                        else -> "完成"
                    },
                    nextEnabled = when (step) {
                        0 -> gsiInputPath.isNotBlank()
                        1 -> state.lastGsiReport != null
                        else -> false
                    },
                ) { currentStep ->
                    when (currentStep) {
                        // STEP 1: Pick GSI & Selection Guide
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

                            if (state.localGsiImages.isNotEmpty()) {
                                M3ESectionLabel("快速选用 Download/AVBForge 已有镜像")
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    state.localGsiImages.forEachIndexed { idx, img ->
                                        val selected = gsiInputPath == img.path
                                        M3EGroupRow(
                                            title = img.name,
                                            subtitle = formatByteSize(img.sizeBytes),
                                            icon = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Description,
                                            iconTint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                            iconBg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                            shape = m3eGroupShape(idx, state.localGsiImages.size),
                                            trailingText = if (selected) "已选" else "选用",
                                            onClick = {
                                                gsiInputPath = img.path
                                                vm.clearLastGsiReport()
                                                onNotify("已选用本地镜像：${img.name}")
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // STEP 2: Auto-Fix SELinux & Multithreaded Hashtree Signing
                        1 -> {
                            WizardStepIntro(
                                stepNumber = 2,
                                title = "自动修复冲突并自签名",
                                description = "在回锁 Yellow 状态下，DSU 仅信任由你私钥签名的 system 镜像。引擎将在本地离线完成解压、冲突修复、建树、签名与全树验签。",
                            )

                            WizardCalloutCard(
                                title = "Rust 原生引擎处理流程",
                                icon = Icons.Rounded.Bolt,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                bullets = listOf(
                                    "流式解压 .xz / .gz 并自动展开 Android Sparse 镜像，低内存占用不 OOM。",
                                    "使用多核并行计算 4096 B 分块的 dm-verity SHA-256 Merkle Hashtree。",
                                    "自动写入满足回锁防回滚校验的 security_patch 属性，并使用默认私钥「${defaultKey?.alias ?: "未配置"}」完成签名与全树校验。",
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

                            WhiteBoxConsoleCard(
                                state = consoleState,
                                onToggleRawMode = vm::setWhiteBoxRawJsonMode,
                                title = "GSI 修复与签名 · 白盒控制台",
                                busy = state.busy,
                            )
                        }

                        // STEP 3: Configure Userdata & Single-Boot Install
                        2 -> {
                            val rep = state.lastGsiReport
                            val gib = userdataSlider.roundToInt()

                            WizardStepIntro(
                                stepNumber = 3,
                                title = "配置独立空间并安装到 DSU",
                                description = "为临时 GSI 分配一块与原系统完全隔离的虚拟存储，再通过系统原生 gsi_tool 写入（不会自动强制重启）。",
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
                                        text = "带 GApps 的 Android 15/16/17 GSI 建议至少 16 GiB；退出或卸载 DSU 后空间会自动释放，绝不碰原系统数据。",
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
                                    text = "预览命令并通过 gsi_tool 写入 DSU",
                                    icon = Icons.Rounded.SystemUpdate,
                                    onClick = { confirmInstallDsu = true },
                                    enabled = !state.busy,
                                    loading = state.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            WhiteBoxConsoleCard(
                                state = consoleState,
                                onToggleRawMode = vm::setWhiteBoxRawJsonMode,
                                title = "DSU 安装与状态回读 · 白盒控制台",
                                busy = state.busy,
                            )

                            if (state.dsuInfo?.enabled == true ||
                                state.dsuStatusText.contains("enabled", ignoreCase = true)
                            ) {
                                WizardCalloutCard(
                                    title = "DSU 已安装并启用，准备就绪！",
                                    icon = Icons.Rounded.CheckCircle,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    body = "重启手机即可进入刚安装的 GSI；单次模式下只需再次重启就能自动回到原厂 OriginOS。",
                                    extra = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Button(
                                                onClick = { confirmReboot = true },
                                                enabled = !state.busy && !consoleState.rebootBlocked,
                                                shape = CircleShape,
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("立即重启进入")
                                            }
                                            OutlinedButton(
                                                onClick = { viewMode = DsuViewMode.HUB },
                                                shape = CircleShape,
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text("返回管理中心")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Local GSI Image Action Sheet
    activeLocalImage?.let { img ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val dateText = remember(img.lastModified) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(img.lastModified))
        }
        ModalBottomSheet(
            onDismissRequest = { activeLocalImage = null },
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
                Text(img.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "大小：${formatByteSize(img.sizeBytes)} · 更新时间：$dateText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    M3EGroupRow(
                        title = "使用此镜像安装到 DSU",
                        subtitle = if (img.name.endsWith(".img", ignoreCase = true)) {
                            "自动预填路径进入第 2 步，可直接选「仅验签跳过重签」或重新签名"
                        } else {
                            "自动预填路径进入第 2 步解压、自动修复冲突并签名"
                        },
                        icon = Icons.Rounded.SystemUpdate,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconBg = MaterialTheme.colorScheme.primaryContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(0, 2),
                        showChevron = true,
                        onClick = {
                            activeLocalImage = null
                            gsiInputPath = img.path
                            vm.clearLastGsiReport()
                            step = 1
                            viewMode = DsuViewMode.INSTALL_WIZARD
                        },
                    )
                    M3EGroupRow(
                        title = "删除文件释放空间（${formatByteSize(img.sizeBytes)}）",
                        subtitle = "仅删除 Download/AVBForge 下的本地镜像，不影响已安装的 DSU",
                        icon = Icons.Rounded.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        iconBg = MaterialTheme.colorScheme.errorContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = m3eGroupShape(1, 2),
                        showChevron = true,
                        onClick = {
                            activeLocalImage = null
                            pendingDeleteLocalImage = img
                        },
                    )
                }
            }
        }
    }

    // Confirmation Dialogs
    if (confirmFlashDsuInit && state.lastDsuPatchReport != null) {
        val activeSlot = snap?.activeSlot?.ifBlank { "_b" } ?: "_b"
        val imgName = File(state.lastDsuPatchReport.outputPath).name
        DryRunCommandDialog(
            title = "刷前命令预演确认（DSU init_boot）",
            safetySummary = "活动槽位 $activeSlot · 保留 KernelSU · 注入 $targetKeyName · 不清空用户数据",
            plannedCommands = listOf(
                "avbforge-core avb verify --image $imgName --expected-pubkey ${defaultKey?.alias ?: "default"}_pkmd.bin",
                "su -c 'avbforge-core flash-partition --partition init_boot --slot $activeSlot --image $imgName --fsync'",
                "su -c 'avbforge-core verify-readback --device /dev/block/by-name/init_boot$activeSlot'",
            ),
            onConfirm = {
                confirmFlashDsuInit = false
                vm.flashPreparedDsuInitBoot(state.lastDsuPatchReport.outputPath)
            },
            onDismiss = { confirmFlashDsuInit = false },
        )
    }

    if (confirmInstallDsu && state.lastGsiReport != null) {
        val rep = state.lastGsiReport
        val gib = userdataSlider.roundToInt()
        val userdataBytes = gib.toLong() * 1024L * 1024L * 1024L
        val imgName = File(rep.outputPath).name
        val enableCmd = if (singleBoot) "gsi_tool enable -s && gsi_tool status" else "gsi_tool enable && gsi_tool status"
        DryRunCommandDialog(
            title = "DSU 安装命令预演确认",
            safetySummary = "独立 Userdata ${gib} GiB · ${if (singleBoot) "单次防变砖启动 (-s)" else "持续启动模式"} · 不修改原厂 OriginOS 分区",
            plannedCommands = listOf(
                "avbforge-core avb verify --image $imgName",
                "su -c 'avbforge-core dsu stage --image $imgName -> /data/gsi/'",
                "su -c 'gsi_tool install --gsi-size ${rep.finalSize} --userdata-size $userdataBytes --wipe --no-reboot'",
                "su -c '$enableCmd'",
            ),
            onConfirm = {
                confirmInstallDsu = false
                vm.installSignedDsu(
                    signedPath = rep.outputPath,
                    userdataGiB = gib,
                    singleBoot = singleBoot,
                )
            },
            onDismiss = { confirmInstallDsu = false },
        )
    }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
            title = { Text("立即重启进入 GSI？") },
            text = {
                val modeTip = if (state.dsuInfo?.oneShot == true) {
                    "当前为单次防变砖模式（-s）：体验完或遇到异常，只需再次重启即可自动回到原厂 OriginOS。"
                } else {
                    "当前为持续启动模式：重启后将进入已启用的第三方 GSI 系统。"
                }
                Text(modeTip)
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmReboot = false
                        vm.rebootToDsu()
                    },
                    shape = CircleShape,
                ) { Text("立即重启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReboot = false }) { Text("稍后手动重启") }
            },
        )
    }

    if (confirmWipeData) {
        AlertDialog(
            onDismissRequest = { confirmWipeData = false },
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Icons.Rounded.CleaningServices, contentDescription = null) },
            title = { Text("仅重置 GSI 用户数据？") },
            text = {
                Text("将清空第三方 GSI 内部的独立 /data（应用与设置），保留已安装的 System 镜像；完全不影响原厂 OriginOS 数据。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmWipeData = false
                        vm.wipeDsuData()
                    },
                    shape = CircleShape,
                ) { Text("确认重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipeData = false }) { Text("取消") }
            },
        )
    }

    if (confirmWipe) {
        val totalSize = state.dsuInfo?.totalDiskBytes ?: 0L
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("彻底卸载 DSU 并释放空间？") },
            text = {
                val spaceNote = if (totalSize > 0L) "（预计释放 ${formatByteSize(totalSize)} 存储空间）" else ""
                Text("将删除 /data/gsi/dsu 下的所有 GSI 系统与数据镜像$spaceNote，不会影响当前 OriginOS 数据。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmWipe = false
                        vm.runDsuSimple("wipe")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = CircleShape,
                ) { Text("确认卸载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("取消") }
            },
        )
    }

    pendingDeleteLocalImage?.let { img ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLocalImage = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("删除本地镜像？") },
            text = {
                Text("将永久删除 ${img.name}（释放 ${formatByteSize(img.sizeBytes)}）。不会影响当前已安装到 DSU 的系统。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteLocalImage = null
                        vm.deleteLocalGsiImage(img.path)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shape = CircleShape,
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLocalImage = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DsuManagementHub(
    state: UiState,
    vm: AvbForgeViewModel,
    dsuReady: Boolean,
    defaultKeySha1: String?,
    onRefresh: () -> Unit,
    onOpenLogs: () -> Unit,
    onExit: () -> Unit,
    onStartInstallNew: () -> Unit,
    onOpenKeySetup: () -> Unit,
    onRequestReboot: () -> Unit,
    onRequestWipeData: () -> Unit,
    onRequestWipe: () -> Unit,
    onSelectLocalImage: (LocalGsiImage) -> Unit,
) {
    val info = state.dsuInfo
    val installed = info?.installed == true
    val enabled = info?.enabled == true
    val running = info?.running == true
    val oneShot = info?.oneShot == true
    val pubkeys = state.snapshot?.initBootAvbpubkeys.orEmpty()
    val avbMatchesDefault = !info?.avbPubkeySha1.isNullOrBlank() &&
        !defaultKeySha1.isNullOrBlank() &&
        info?.avbPubkeySha1.equals(defaultKeySha1, ignoreCase = true)

    val heroBg = when {
        running || enabled -> MaterialTheme.colorScheme.secondaryContainer
        installed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val heroFg = when {
        running || enabled -> MaterialTheme.colorScheme.onSecondaryContainer
        installed -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val heroTitle = when {
        running -> "当前正运行于第三方 GSI"
        enabled -> "GSI 已启用 · 重启即进入"
        installed -> "已安装 GSI（当前未启用）"
        else -> "尚未安装第三方 GSI"
    }
    val heroSubtitle = when {
        running -> "重启一次手机即可切回原厂 OriginOS，双方数据完全隔离。"
        enabled -> if (oneShot) {
            "单次防变砖模式已生效：重启进入体验，再重启即可自动返回 OriginOS。"
        } else {
            "持续启动模式已生效：每次重启均会进入 GSI，直到手动禁用。"
        }
        installed -> "系统与数据镜像已就位，无需重装；点击下方按钮即可一键启用并重启进入。"
        else -> "可在独立虚拟空间无损体验 Android 14/15/16/17 类原生系统，零风险不碰原系统。"
    }
    val heroBadge = when {
        running -> "运行中"
        enabled -> if (oneShot) "单次启动 (-s)" else "持续启动"
        installed && info.totalDiskBytes > 0L -> "已占 ${formatByteSize(info.totalDiskBytes)}"
        installed -> "已安装"
        else -> "空闲"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Rounded.Close, contentDescription = "返回主页")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "GSI / DSU 管理中心",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = if (dsuReady) "本机 DSU 自签名已就绪" else "需先注入 DSU 公钥",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新 DSU 状态")
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Rounded.Terminal, contentDescription = "实时日志")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Busy Banner
            AnimatedVisibility(
                visible = state.busy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val p = state.progress
                        if (p != null && p.total > 0) {
                            val anim by animateFloatAsState(
                                targetValue = (p.current.toFloat() / p.total.toFloat()).coerceIn(0f, 1f),
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                                label = "hub_prog",
                            )
                            LinearProgressIndicator(
                                progress = { anim },
                                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                        }
                    }
                }
            }

            // Error Banner
            AnimatedVisibility(
                visible = state.errorBanner != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.errorBanner?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = vm::clearError) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            // 1. Live DSU Hero Status Card
            M3EHeroCard(
                title = heroTitle,
                subtitle = heroSubtitle,
                icon = when {
                    running || enabled -> Icons.Rounded.CheckCircle
                    installed -> Icons.Rounded.SystemUpdate
                    else -> Icons.Rounded.Storage
                },
                containerColor = heroBg,
                contentColor = heroFg,
                badgeText = heroBadge,
                bottomContent = if (installed && info != null) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DsuMetricTile(
                                    label = "System 镜像",
                                    value = if (info.systemSizeBytes > 0L) formatByteSize(info.systemSizeBytes) else "已就绪",
                                    fg = heroFg,
                                    modifier = Modifier.weight(1f),
                                )
                                DsuMetricTile(
                                    label = "独立 Userdata",
                                    value = if (info.userdataSizeBytes > 0L) formatByteSize(info.userdataSizeBytes) else "已就绪",
                                    fg = heroFg,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DsuMetricTile(
                                    label = "AVB 签名密钥",
                                    value = when {
                                        avbMatchesDefault -> "✓ 匹配默认私钥"
                                        !info.avbPubkeySha1.isNullOrBlank() -> info.avbPubkeySha1.take(12) + "..."
                                        else -> "未签名 / 未知"
                                    },
                                    fg = heroFg,
                                    modifier = Modifier.weight(1f),
                                )
                                DsuMetricTile(
                                    label = "DSU 槽位",
                                    value = info.slotName ?: "dsu",
                                    fg = heroFg,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else null,
            )

            // 2. Contextual Primary Action Buttons
            if (installed) {
                if (enabled) {
                    M3EPrimaryButton(
                        text = "立即重启进入 GSI",
                        icon = Icons.Rounded.PowerSettingsNew,
                        onClick = onRequestReboot,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { vm.runDsuSimple("disable") },
                            enabled = !state.busy,
                            shape = CircleShape,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("暂停 / 禁用")
                        }
                        OutlinedButton(
                            onClick = { vm.enableDsu(singleBoot = !oneShot) },
                            enabled = !state.busy,
                            shape = CircleShape,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (oneShot) "改为持续启动" else "改为单次 (-s)")
                        }
                    }
                } else {
                    M3EPrimaryButton(
                        text = "启用 GSI（单次防变砖模式 -s）",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = { vm.enableDsu(singleBoot = true) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { vm.enableDsu(singleBoot = false) },
                        enabled = !state.busy,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("持续启用（每次重启都进入 GSI）")
                    }
                }
            } else {
                M3EPrimaryButton(
                    text = "导入并安装新的 GSI 镜像",
                    icon = Icons.Rounded.Add,
                    onClick = onStartInstallNew,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 3. Installed DSU Maintenance Actions
            if (installed) {
                M3ESectionLabel("已安装 DSU 维护", helpDoc = HelpDocs.DSU_WIPE_DATA)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    M3EGroupRow(
                        title = "安装 / 更换新的 GSI 镜像",
                        subtitle = "进入 3 步向导：自动修复 Android 15/16/17 SELinux 冲突、签名并覆盖安装",
                        icon = Icons.Rounded.SystemUpdate,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBg = MaterialTheme.colorScheme.primaryContainer,
                        shape = m3eGroupShape(0, 3),
                        showChevron = true,
                        onClick = onStartInstallNew,
                    )
                    M3EGroupRow(
                        title = "仅重置 GSI 数据（保留系统镜像）",
                        subtitle = "执行 gsi_tool wipe-data，清空 GSI 内用户数据，无需重装系统",
                        icon = Icons.Rounded.CleaningServices,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        iconBg = MaterialTheme.colorScheme.secondaryContainer,
                        shape = m3eGroupShape(1, 3),
                        showChevron = true,
                        onClick = { if (!state.busy) onRequestWipeData() },
                    )
                    M3EGroupRow(
                        title = "彻底卸载 DSU 并释放空间",
                        subtitle = if (info != null && info.totalDiskBytes > 0L) {
                            "删除 /data/gsi/dsu，预计释放 ${formatByteSize(info.totalDiskBytes)}，不影响原厂系统"
                        } else {
                            "删除 /data/gsi/dsu 下的全部镜像与独立空间，不影响原厂系统"
                        },
                        icon = Icons.Rounded.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        iconBg = MaterialTheme.colorScheme.errorContainer,
                        shape = m3eGroupShape(2, 3),
                        showChevron = true,
                        onClick = { if (!state.busy) onRequestWipe() },
                    )
                }
            }

            // 4. Local GSI Image Library (Download/AVBForge)
            val localTotalBytes = state.localGsiImages.sumOf { it.sizeBytes }
            M3ESectionLabel(
                text = if (state.localGsiImages.isEmpty()) {
                    "本地 GSI 镜像库（Download/AVBForge）"
                } else {
                    "本地 GSI 镜像库（${state.localGsiImages.size} 个 · ${formatByteSize(localTotalBytes)}）"
                },
            )
            if (state.localGsiImages.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Download/AVBForge 下暂无已保存的 GSI 镜像。完成签名后的镜像会自动出现在这里，方便一键复用或清理空间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.localGsiImages.forEachIndexed { idx, img ->
                        M3EGroupRow(
                            title = img.name,
                            subtitle = "${formatByteSize(img.sizeBytes)} · 点击一键安装或删除释放空间",
                            icon = Icons.Rounded.Description,
                            iconTint = MaterialTheme.colorScheme.primary,
                            iconBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            shape = m3eGroupShape(idx, state.localGsiImages.size),
                            showChevron = true,
                            onClick = { onSelectLocalImage(img) },
                        )
                    }
                }
            }

            // 5. Advanced DSU Key Configuration
            M3ESectionLabel("底层公钥配置", helpDoc = HelpDocs.DSU_PUBKEY)
            M3EGroupRow(
                title = "管理 init_boot DSU 校验公钥",
                subtitle = if (pubkeys.isNotEmpty()) {
                    "已注入：${pubkeys.joinToString(", ") { it.substringAfterLast('/') }} · 更换私钥时可重新注入"
                } else {
                    "尚未检测到 .avbpubkey，点击立即无损注入"
                },
                icon = Icons.Rounded.Key,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                showChevron = true,
                onClick = onOpenKeySetup,
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DsuMetricTile(
    label: String,
    value: String,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = fg.copy(alpha = 0.09f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.72f),
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
