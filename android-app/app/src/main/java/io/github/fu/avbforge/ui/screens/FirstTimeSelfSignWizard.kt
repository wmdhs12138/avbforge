package io.github.fu.avbforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.core.UriPathResolver
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.HelpDocs
import io.github.fu.avbforge.ui.UiState
import io.github.fu.avbforge.ui.components.AddKeyBottomSheet
import io.github.fu.avbforge.ui.components.BackupPartitionsSheet
import io.github.fu.avbforge.ui.components.DryRunCommandDialog
import io.github.fu.avbforge.ui.components.M3EGroupRow
import io.github.fu.avbforge.ui.components.WhiteBoxConsoleCard
import io.github.fu.avbforge.ui.components.M3EPillChip
import io.github.fu.avbforge.ui.components.M3EPrimaryButton
import io.github.fu.avbforge.ui.components.WizardCalloutCard
import io.github.fu.avbforge.ui.components.WizardScaffold
import io.github.fu.avbforge.ui.components.WizardStepIntro
import io.github.fu.avbforge.ui.components.m3eGroupShape
import java.io.File

/**
 * Full-screen 5-Step Guided Wizard: First-Time Self-Signed System Migration (including KernelSU & DSU).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstTimeSelfSignWizard(
    state: UiState,
    vm: AvbForgeViewModel,
    onOpenLogs: () -> Unit,
    onExit: () -> Unit,
    onNotify: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val consoleState by vm.consoleState.collectAsState()
    val snap = state.snapshot
    val activeSlot = snap?.activeSlot?.ifBlank { "_b" } ?: "_b"
    val isNeo9 = snap?.let { SafetyGate.isSupportedNeo9(it) } ?: true
    val defaultKey = vm.vault.getDefaultKey()
    val fstabNames = snap?.fstabAvbKeys?.map { it.substringAfterLast('/') }.orEmpty()

    var step by remember { mutableIntStateOf(0) }
    var stockDir by remember { mutableStateOf(state.lastBackupDir ?: "") }
    LaunchedEffect(state.lastBackupDir) {
        if (stockDir.isBlank() && !state.lastBackupDir.isNullOrBlank()) {
            stockDir = state.lastBackupDir
        }
    }
    var includeKsuRoot by remember { mutableStateOf(true) }
    var patchedInitBootPath by remember { mutableStateOf("") }
    var injectDsuKey by remember { mutableStateOf(true) }
    var dsuKeyName by remember { mutableStateOf(fstabNames.firstOrNull() ?: "q-gsi.avbpubkey") }
    var slotChoiceIdx by remember { mutableIntStateOf(0) }
    val slotChoices = listOf("" to "当前槽位 ($activeSlot)", "_a" to "槽位 _a", "_b" to "槽位 _b")

    var showBackupSheet by remember { mutableStateOf(false) }
    var showAddKeySheet by remember { mutableStateOf(false) }
    var confirmFlashBundle by remember { mutableStateOf(false) }

    val stepTitles = remember {
        listOf("迁移须知", "备份原厂分区", "准备签名密钥", "KernelSU 与 DSU", "验签与安全写入")
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveTreeUri(uri)?.let {
                stockDir = it
                onNotify("已选择原厂镜像目录：${File(it).name}")
            } ?: onNotify("无法解析所选目录")
        }
    }

    val ksuPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            UriPathResolver.resolveFileUri(ctx, uri)?.let {
                patchedInitBootPath = it
                onNotify("已选择 KSU 修补产物：${File(it).name}")
            }
        }
    }

    val canAdvance = when (step) {
        0 -> true
        1 -> stockDir.isNotBlank()
        2 -> defaultKey != null
        3 -> !includeKsuRoot || patchedInitBootPath.isNotBlank()
        else -> false
    }

    WizardScaffold(
        wizardTitle = "首次迁移自签名系统",
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
            0 -> "我已了解风险，开始迁移"
            1 -> if (stockDir.isBlank()) "请先备份或选择目录" else "下一步：准备密钥"
            2 -> if (defaultKey == null) "请先生成或导入密钥" else "下一步：配置 KSU"
            3 -> if (includeKsuRoot && patchedInitBootPath.isBlank()) "请先选回 KSU 修补产物" else "下一步：构建与写入"
            else -> "下一步"
        },
        nextEnabled = canAdvance,
    ) { currentStep ->
        when (currentStep) {
            // STEP 1: Migration Notice & Device Check
            0 -> {
                WizardStepIntro(
                    stepNumber = 1,
                    title = "迁移须知与设备核验",
                    description = "在修改任何分区之前，请先确认以下 3 项关键前提，确保整个过程可控、可恢复。",
                )

                WizardCalloutCard(
                    title = "重要警告：首次切换自签名会格式化数据分区",
                    icon = Icons.Rounded.WarningAmber,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    bullets = listOf(
                        "从原厂 Green 状态首次刷入自签名（Yellow）或更换签名私钥时，Android 信任根发生变更，首次重启会自动格式化 /data 数据分区（清空所有照片、聊天记录和应用）。",
                        "请务必在开始前将重要个人资料备份到电脑或云端。",
                        "后续在 Yellow 状态下使用「同一把私钥」升级 KSU 或启用 DSU 时，不会再清空数据。",
                    ),
                )

                WizardCalloutCard(
                    title = "iQOO Neo9 (Android 14) 适配说明",
                    icon = Icons.Rounded.Info,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    bullets = listOf(
                        "本向导仅在 vivo iQOO Neo9 (V2338A / PD2338) Android 14 固件完整验证；Android 15+ 固件 BL 可能已收紧策略，风险极高。",
                        "本应用不提供解锁 Bootloader 或漏洞提权，需在 Android 14 下借助你已有的临时 Root 或现行 Root 权限运行。",
                        "请确保你已提前在 Bootloader 中注册与即将使用的私钥匹配的 avb_custom_key（avb_pkmd.bin）。",
                    ),
                )

                if (snap != null) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("当前设备核验结果", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "机型：${snap.manufacturer} ${snap.model}（${if (isNeo9) "已适配" else "未适配机型"}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isNeo9) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = "系统：Android ${snap.androidRelease} (SDK ${snap.sdkInt}) · 活动槽位 ${snap.activeSlot}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "AVB 状态：${snap.verifiedBootState.uppercase()} / ${snap.deviceState.uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // STEP 2: Backup 7 Stock Partitions
            1 -> {
                WizardStepIntro(
                    stepNumber = 2,
                    title = "备份原厂 7 个引导分区",
                    description = "我们需要一份干净的原厂引导镜像作为重签底包，同时它也是你随时回退原厂的黄金备份。",
                )

                WizardCalloutCard(
                    title = "为什么是这 7 个分区？（约 449 MB）",
                    icon = Icons.Rounded.Backup,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    bullets = listOf(
                        "Bootloader 会先校验 vbmeta，再沿着链式表逐一校验 boot、init_boot、vendor_boot、dtbo、recovery、vbmeta_system 这 6 个分区。",
                        "首次更换为自定义密钥时，以上 7 个分区必须全部用新私钥重签，缺一会导致拒启。",
                        "10GB+ 的 super 分区（system/vendor 等）完全不需要备份或修改，重签 vbmeta_system 时会自动保留其原厂 hashtree。",
                    ),
                )

                val rowCount = if (state.lastBackupDir != null) 3 else 2
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    M3EGroupRow(
                        title = "一键提取当前槽位 ($activeSlot) 黄金备份",
                        subtitle = "默认全选 7 个必选引导分区，保存到 Download/AVBForge",
                        icon = Icons.Rounded.Backup,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        iconBg = MaterialTheme.colorScheme.secondaryContainer,
                        shape = m3eGroupShape(0, rowCount),
                        showChevron = true,
                        onClick = { if (!state.busy) showBackupSheet = true },
                    )
                    M3EGroupRow(
                        title = if (stockDir.isBlank()) "或选择已有的原厂散包目录" else "已选目录：${File(stockDir).name}",
                        subtitle = stockDir.ifBlank { "如果你之前已经备份过，可直接选择包含 vbmeta.img 等文件的文件夹" },
                        icon = Icons.Rounded.FolderOpen,
                        shape = m3eGroupShape(1, rowCount),
                        showChevron = true,
                        monospaceSubtitle = stockDir.isNotBlank(),
                        helpDoc = HelpDocs.FORGE_STOCK_DIR,
                        onClick = { dirPicker.launch(null) },
                    )
                    state.lastBackupDir?.let { bkpDir ->
                        M3EGroupRow(
                            title = "使用刚才完成的备份目录",
                            subtitle = bkpDir,
                            icon = Icons.Rounded.CheckCircle,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            iconBg = MaterialTheme.colorScheme.secondaryContainer,
                            shape = m3eGroupShape(2, rowCount),
                            trailingText = if (stockDir == bkpDir) "已选中" else "使用",
                            onClick = {
                                stockDir = bkpDir
                                onNotify("已选中最新备份目录")
                            },
                        )
                    }
                }

                WhiteBoxConsoleCard(
                    state = consoleState,
                    onToggleRawMode = vm::setWhiteBoxRawJsonMode,
                    title = "分区备份 · 白盒控制台",
                    busy = state.busy,
                )
            }

            // STEP 3: Prepare Signing Key
            2 -> {
                WizardStepIntro(
                    stepNumber = 3,
                    title = "准备自签名密钥 (avb_pkmd.bin)",
                    description = "这把 RSA 私钥将成为你自签名系统的唯一信任根，请妥善保管并同步公钥到 Bootloader。",
                )

                WizardCalloutCard(
                    title = "密钥安全与使用提示",
                    icon = Icons.Rounded.Key,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    bullets = listOf(
                        "推荐使用 RSA-2048（生成 520 字节 avb_pkmd.bin），与 iQOO Neo9 Bootloader 100% 兼容。",
                        "如果你已经在电脑用 openssl/avbtool 生成过私钥，可直接导入标准 PKCS#8 PEM 文件。",
                        "生成或导入后，点击「导出 avb_pkmd.bin」即可获得注册 avb_custom_key 所需的公钥文件。",
                    ),
                )

                if (defaultKey != null) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "当前默认密钥：${defaultKey.alias}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                M3EPillChip(
                                    text = "RSA-${defaultKey.bits}",
                                    containerColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f),
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Text(
                                text = "SHA-1: ${defaultKey.sha1}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        M3EGroupRow(
                            title = "导出 avb_pkmd.bin 公钥文件",
                            subtitle = "保存到 Download/AVBForge/${defaultKey.alias}_avb_pkmd.bin",
                            icon = Icons.Rounded.Download,
                            shape = m3eGroupShape(0, 3),
                            showChevron = true,
                            helpDoc = HelpDocs.PKMD_EXPORT,
                            onClick = {
                                vm.exportPkmdToDownloads(defaultKey)
                                onNotify("已导出 avb_pkmd.bin")
                            },
                        )
                        M3EGroupRow(
                            title = "复制公钥 SHA-1 指纹",
                            subtitle = "便于核对与本机或电脑端密钥是否一致",
                            icon = Icons.Rounded.ContentCopy,
                            shape = m3eGroupShape(1, 3),
                            showChevron = true,
                            onClick = {
                                clipboard.setText(AnnotatedString(defaultKey.sha1))
                                onNotify("已复制公钥 SHA-1")
                            },
                        )
                        M3EGroupRow(
                            title = "更换 / 新增其他密钥",
                            subtitle = "生成新的 RSA 密钥对或导入其他 PEM 私钥",
                            icon = Icons.Rounded.Add,
                            shape = m3eGroupShape(2, 3),
                            showChevron = true,
                            onClick = { showAddKeySheet = true },
                        )
                    }
                } else {
                    M3EPrimaryButton(
                        text = "立即生成新密钥或导入 PEM",
                        icon = Icons.Rounded.Add,
                        onClick = { showAddKeySheet = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // STEP 4: KernelSU Collaboration & DSU Pubkey
            3 -> {
                WizardStepIntro(
                    stepNumber = 4,
                    title = "KernelSU Root 与 DSU 公钥",
                    description = "强烈建议随首次自签名一并刷入 KernelSU，并顺带注入 DSU 公钥，一次重启全部到位。",
                )

                WizardCalloutCard(
                    title = "为什么强烈建议现在就一并刷入 KernelSU？",
                    icon = Icons.Rounded.Memory,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    bullets = listOf(
                        "如果你本次不刷入 KernelSU，重启进入 Yellow 系统后会立刻失去临时 Root，后续想再装 KSU 或改分区必须重新寻找临时 Root。",
                        "你可以自由选择任意喜欢的 KSU 管理器（官方 KernelSU / KSU Next / SukiSU 等），只需把我们导出的纯净 init_boot 丢进去修补，再把产物选回即可。",
                        if (snap?.initBootHasKsu == true) {
                            "检测到当前活动槽位 init_boot 已经含有旧版 KSU：导出底包时会自动先剥离旧 KSU，避免二次叠打冲突！"
                        } else {
                            "后续进入 Yellow 系统后，你随时可以通过主页的「无损更新 / 更换 KernelSU」向导零数据损失升级 KSU。"
                        },
                    ),
                )

                val ksuRowCount = if (includeKsuRoot) 3 else 1
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    M3EGroupRow(
                        title = "随本轮一并刷入 KernelSU（推荐）",
                        subtitle = if (includeKsuRoot) "已开启：请在下方导出底包并选回修补产物" else "已关闭：重启后将不具备 Root 权限",
                        icon = Icons.Rounded.Memory,
                        shape = m3eGroupShape(0, ksuRowCount),
                        trailing = {
                            Switch(checked = includeKsuRoot, onCheckedChange = { includeKsuRoot = it })
                        },
                    )
                    if (includeKsuRoot) {
                        M3EGroupRow(
                            title = "① 导出纯净 init_boot.img 底包",
                            subtitle = state.exportedInitBootPath ?: "自动备份当前 init_boot（若已含旧 KSU 会自动剥离）",
                            icon = Icons.Rounded.FileDownload,
                            shape = m3eGroupShape(1, ksuRowCount),
                            showChevron = true,
                            onClick = { if (!state.busy) vm.exportCleanInitBootForKsu(stripKsu = true) },
                        )
                        M3EGroupRow(
                            title = if (patchedInitBootPath.isBlank()) "② 选回 KSU 管理器修补后的镜像" else "已选：${File(patchedInitBootPath).name}",
                            subtitle = patchedInitBootPath.ifBlank { "切到你的 KSU 管理器点击『修补文件』，完成后点此选回 kernelsu_patched_*.img" },
                            icon = Icons.Rounded.UploadFile,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            iconBg = MaterialTheme.colorScheme.secondaryContainer,
                            shape = m3eGroupShape(2, ksuRowCount),
                            showChevron = true,
                            monospaceSubtitle = patchedInitBootPath.isNotBlank(),
                            onClick = { ksuPicker.launch(arrayOf("*/*")) },
                        )
                    }
                }

                // Optional DSU pubkey injection
                M3EGroupRow(
                    title = "顺带注入 DSU 自签名公钥（推荐）",
                    subtitle = if (injectDsuKey) {
                        "将 avb_pkmd.bin 写入 ramdisk ($dsuKeyName)，开机后立刻可玩 GSI"
                    } else {
                        "暂不注入，后续可随时从主页进入「DSU 向导」无损补注"
                    },
                    icon = Icons.Rounded.Key,
                    iconTint = MaterialTheme.colorScheme.primary,
                    iconBg = MaterialTheme.colorScheme.primaryContainer,
                    helpDoc = HelpDocs.DSU_PUBKEY,
                    trailing = {
                        Switch(checked = injectDsuKey, onCheckedChange = { injectDsuKey = it })
                    },
                )

                AnimatedVisibility(visible = injectDsuKey && fstabNames.isNotEmpty()) {
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

            // STEP 5: Verify & Safety Gate Flash
            4 -> {
                WizardStepIntro(
                    stepNumber = 5,
                    title = "全链离线验签与安全刷写",
                    description = "先在离线环境生成并逐一验证整条 AVB 信任链，全部通过后才会解锁刷写按钮。",
                )

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("本次构建配置总览", style = MaterialTheme.typography.titleSmall)
                        Text("原厂底包：${File(stockDir).name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("签名私钥：${defaultKey?.alias ?: "未配置"} (RSA-${defaultKey?.bits ?: 0})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("KernelSU：${if (includeKsuRoot) File(patchedInitBootPath).name else "不刷入"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("DSU 公钥：${if (injectDsuKey) "注入 $dsuKeyName" else "不注入"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                M3EPrimaryButton(
                    text = if (state.lastForgeReport == null) "① 构建全套自签名包并全链验签" else "重新构建并全链验签",
                    icon = Icons.Rounded.AutoFixHigh,
                    onClick = {
                        vm.forgeSelfSignedBundle(
                            stockDirPath = stockDir.trim(),
                            patchedInitBootPath = if (includeKsuRoot) patchedInitBootPath.trim().ifBlank { null } else null,
                            injectDsuKey = injectDsuKey,
                            dsuKeyName = dsuKeyName.trim(),
                        )
                    },
                    enabled = !state.busy && stockDir.isNotBlank() && defaultKey != null,
                    loading = state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Safety Gate & Flash section once built and verified
                state.lastForgeReport?.let { rep ->
                    WizardCalloutCard(
                        title = "离线全链验签通过（${rep.chainVerifiedCount} 个镜像）",
                        icon = Icons.Rounded.CheckCircle,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        body = "已签名分区：${rep.signedPartitions.joinToString("、")}\nKSU：${if (rep.initBootHasKsu) "已保留" else "无"} · DSU 公钥：${if (rep.dsuKeyInjected) "已注入" else "无"}",
                    )

                    val assessment = vm.computeSafety(includeKsuRoot)
                    val checks = remember(assessment) {
                        mutableStateListOf<Boolean>().apply {
                            repeat(assessment?.requiredChecklist?.size ?: 0) { add(false) }
                        }
                    }
                    val allChecked = checks.all { it }

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("② 写入目标槽位与安全门禁", style = MaterialTheme.typography.titleMedium)
                            }

                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                slotChoices.forEachIndexed { idx, (_, label) ->
                                    SegmentedButton(
                                        selected = slotChoiceIdx == idx,
                                        onClick = { slotChoiceIdx = idx },
                                        shape = SegmentedButtonDefaults.itemShape(idx, slotChoices.size),
                                    ) { Text(label, maxLines = 1) }
                                }
                            }

                            if (assessment != null) {
                                Text(
                                    text = assessment.stateNotice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                assessment.requiredChecklist.forEachIndexed { i, item ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = checks[i], onCheckedChange = { checks[i] = it })
                                        Text(
                                            text = item,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (item.contains("数据清除")) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }

                            M3EPrimaryButton(
                                text = "预览命令并安全写入（含清缓存回读核验）",
                                icon = Icons.Rounded.FlashOn,
                                onClick = { confirmFlashBundle = true },
                                enabled = !state.busy && allChecked,
                                loading = state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                WhiteBoxConsoleCard(
                    state = consoleState,
                    onToggleRawMode = vm::setWhiteBoxRawJsonMode,
                    title = "全链构建与刷写 · 白盒控制台",
                    busy = state.busy,
                    onReboot = { vm.rebootDevice() },
                    rebootLabel = "安全重启进入自签名系统",
                )
            }
        }
    }

    if (confirmFlashBundle && state.lastForgeReport != null) {
        val rep = state.lastForgeReport
        val chosenSlot = slotChoices[slotChoiceIdx].first.ifBlank { null }
        val displaySlot = chosenSlot ?: activeSlot
        val assessment = vm.computeSafety(includeKsuRoot)
        val bundleName = File(rep.outputDir).name
        val plannedCommands = listOf(
            "avbforge-core avb verify --image $bundleName/vbmeta.img --follow-chain",
            "su -c "avbforge-core flash-bundle --bundle-dir $bundleName --slot $displaySlot --fsync"",
            "su -c "avbforge-core verify-readback --slot $displaySlot (${rep.signedPartitions.joinToString(", ")})"",
        )
        DryRunCommandDialog(
            title = "刷前命令预演确认（全套自签名包）",
            safetySummary = assessment?.stateNotice
                ?: "目标槽位 $displaySlot · 共 ${rep.signedPartitions.size} 个已验签分区（vbmeta 最后写入）",
            plannedCommands = plannedCommands,
            onConfirm = {
                confirmFlashBundle = false
                vm.flashForgedBundle(rep.outputDir, chosenSlot)
            },
            onDismiss = { confirmFlashBundle = false },
        )
    }

    if (showBackupSheet) {
        BackupPartitionsSheet(
            activeSlot = activeSlot,
            defaultSelectAll = true,
            liveSizes = snap?.partitionSizes.orEmpty(),
            busy = state.busy,
            onStartBackup = { chosenSlot, selectedParts ->
                showBackupSheet = false
                vm.backupPartitions(slot = chosenSlot, partitions = selectedParts)
            },
            onDismiss = { showBackupSheet = false },
        )
    }

    if (showAddKeySheet) {
        AddKeyBottomSheet(
            busy = state.busy,
            onGenerate = { alias, bits ->
                vm.generateKey(alias, bits)
                showAddKeySheet = false
            },
            onImport = { alias, path, pass ->
                vm.importStandardPem(alias, path, pass)
                showAddKeySheet = false
            },
            onPickFile = { uri -> UriPathResolver.resolveFileUri(ctx, uri) },
            onDismiss = { showAddKeySheet = false },
        )
    }
}
