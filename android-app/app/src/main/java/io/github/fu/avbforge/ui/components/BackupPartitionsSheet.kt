package io.github.fu.avbforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.data.PartitionSizeInfo
import io.github.fu.avbforge.ui.HelpDocs
import java.util.Locale

private data class BackupPartitionMeta(
    val name: String,
    val desc: String,
    val fallbackBytes: Long,
    val minimalDefault: Boolean,
)

private val BACKUP_PARTITIONS_META = listOf(
    BackupPartitionMeta("init_boot", "KernelSU LKM 与 DSU 公钥所在分区", 8L * 1024 * 1024, true),
    BackupPartitionMeta("vbmeta", "AVB 根校验表（链式校验入口）", 64L * 1024, true),
    BackupPartitionMeta("vbmeta_system", "super 内 7 个逻辑分区总校验表", 64L * 1024, false),
    BackupPartitionMeta("boot", "GKI 内核分区（首次全链迁移必选）", 96L * 1024 * 1024, false),
    BackupPartitionMeta("dtbo", "设备树覆盖（首次全链迁移必选）", 24L * 1024 * 1024, false),
    BackupPartitionMeta("vendor_boot", "厂商 Ramdisk / fstab（体积较大）", 128L * 1024 * 1024, false),
    BackupPartitionMeta("recovery", "恢复模式镜像（体积最大）", 192L * 1024 * 1024, false),
)

fun formatByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupPartitionsSheet(
    activeSlot: String,
    defaultSelectAll: Boolean,
    liveSizes: List<PartitionSizeInfo>,
    busy: Boolean,
    onStartBackup: (slot: String, partitions: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inactiveSlot = if (activeSlot == "_b") "_a" else "_b"
    val slotOptions = listOf(
        activeSlot to "活动槽位 ${activeSlot.trimStart('_').uppercase()} ($activeSlot)",
        inactiveSlot to "备用槽位 ${inactiveSlot.trimStart('_').uppercase()} ($inactiveSlot)",
    )
    var slotIdx by remember { mutableIntStateOf(0) }
    val sizeMap = remember(liveSizes) { liveSizes.associateBy { it.name } }

    val selected = remember {
        mutableStateMapOf<String, Boolean>().apply {
            BACKUP_PARTITIONS_META.forEach { meta ->
                put(meta.name, if (defaultSelectAll) true else meta.minimalDefault)
            }
        }
    }

    val selectedList = BACKUP_PARTITIONS_META.filter { selected[it.name] == true }
    val totalBytes = selectedList.sumOf { meta ->
        sizeMap[meta.name]?.sizeBytes ?: meta.fallbackBytes
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("选择要备份的分区", style = MaterialTheme.typography.titleLarge)
                M3EHelpButton(doc = HelpDocs.BACKUP_PARTITIONS)
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                slotOptions.forEachIndexed { idx, (_, label) ->
                    SegmentedButton(
                        selected = slotIdx == idx,
                        onClick = { slotIdx = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, slotOptions.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                M3EPillChip(
                    text = "首次迁移完整 AVB 链 (7个)",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { BACKUP_PARTITIONS_META.forEach { selected[it.name] = true } },
                )
                M3EPillChip(
                    text = "仅 KSU/DSU 最小 (~8 MB)",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { BACKUP_PARTITIONS_META.forEach { selected[it.name] = it.minimalDefault } },
                )
                M3EPillChip(
                    text = "清空勾选",
                    onClick = { BACKUP_PARTITIONS_META.forEach { selected[it.name] = false } },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BACKUP_PARTITIONS_META.forEachIndexed { idx, meta ->
                    val checked = selected[meta.name] == true
                    val actualBytes = sizeMap[meta.name]?.sizeBytes ?: meta.fallbackBytes
                    val inChain = sizeMap[meta.name]?.inVbmetaChain ?: true

                    Surface(
                        shape = m3eGroupShape(idx, BACKUP_PARTITIONS_META.size),
                        color = if (checked) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(m3eGroupShape(idx, BACKUP_PARTITIONS_META.size))
                            .clickable { selected[meta.name] = !checked },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { selected[meta.name] = it })
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(meta.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = formatByteSize(actualBytes),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                val tagPrefix = when {
                                    meta.minimalDefault -> "核心必选 · "
                                    inChain -> "首次必选 · "
                                    else -> ""
                                }
                                Text(
                                    text = "$tagPrefix${meta.desc}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            M3EPrimaryButton(
                text = if (selectedList.isEmpty()) "请至少选择 1 个分区" else "备份 ${selectedList.size} 个分区（约 ${formatByteSize(totalBytes)}）",
                icon = Icons.Rounded.Backup,
                onClick = { onStartBackup(slotOptions[slotIdx].first, selectedList.map { it.name }) },
                enabled = !busy && selectedList.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
