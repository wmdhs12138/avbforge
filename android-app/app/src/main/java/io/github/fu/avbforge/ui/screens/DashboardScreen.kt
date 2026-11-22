package io.github.fu.avbforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.core.FreezerDetector
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.UiState

@Composable
fun DashboardScreen(state: UiState, vm: AvbForgeViewModel) {
    val ctx = LocalContext.current
    val snap = state.snapshot

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本机 AVB 与环境体检", style = MaterialTheme.typography.titleMedium)
                if (snap == null) {
                    Text("尚未获取本机信息，请点击下方刷新并授予 Root（或临时 Root）权限。")
                } else {
                    Text("机型：${snap.manufacturer} ${snap.model} (${snap.device})")
                    Text("系统：Android ${snap.androidRelease} (SDK ${snap.sdkInt}) / VNDK ${snap.vndkVersion}")
                    Text("内核：${snap.kernelRelease}")
                    Text("活动槽位：${snap.activeSlot} | AVB：${snap.verifiedBootState} / ${snap.deviceState}")
                    Text("KernelSU：${snap.ksuVersion ?: "未检测到 ksud"} | init_boot 含 KSU：${snap.initBootHasKsu}")
                    Text("vbmeta 公钥 SHA-1：${snap.vbmetaPubkeySha1 ?: "无"}")
                    Text("fstab avb_keys：${snap.fstabAvbKeys.joinToString(", ").ifEmpty { "未检测到" }}")
                    Text("init_boot 已注入 DSU 公钥：${snap.initBootAvbpubkeys.joinToString(", ").ifEmpty { "未注入" }}")
                    Text("DSU 状态：${snap.dsuStatus}")
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.refreshDevice() }, enabled = !state.busy) {
                        Text("重新体检")
                    }
                    OutlinedButton(onClick = { vm.backupActiveSlot() }, enabled = !state.busy) {
                        Text("备份全套引导分区")
                    }
                }
            }
        }

        if (snap != null && !SafetyGate.isSupportedNeo9(snap)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "非目标机型警示（仅针对 iQOO Neo9 开发）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "检测到当前机型为 ${snap.manufacturer} ${snap.model} (${snap.device})。本应用仅针对 iQOO Neo9 (V2338A/PD2338) Android 14 开发验证，切勿在其他设备上盲目刷写分区！",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (snap != null && snap.sdkInt >= 35) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Android ${snap.androidRelease} 高版本固件警示",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "高版本固件（如 vivo/iQOO A15/A16）的 Bootloader 可能已收紧 avb_custom_key 链路，刷写自签名镜像前请务必确认本机兼容性与回退方案。",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (snap?.freezerEnabled == true && !FreezerDetector.isIgnoringBatteryOptimizations(ctx)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("检测到系统 cgroup 冻结机制（如 OriginOS 墓碑）", style = MaterialTheme.typography.titleSmall)
                    Text("建议将本应用和 KernelSU 管理器加入「允许后台高耗电」，避免长时间签名或 Root 子进程被冻住导致卡死。")
                    OutlinedButton(onClick = { FreezerDetector.openBatteryOptimizationSettings(ctx) }) {
                        Text("前往电池优化设置")
                    }
                }
            }
        }

        state.lastBackup?.let { bkp ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("最近原厂备份（槽位 ${bkp.slot}）", style = MaterialTheme.typography.titleSmall)
                    Text("已备份 ${bkp.partitions.size} 个分区：${bkp.partitions.joinToString { it.partition }}")
                }
            }
        }
    }
}
