package io.github.fu.avbforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.data.CommandBlock
import io.github.fu.avbforge.data.CommandLogLevel
import io.github.fu.avbforge.data.CommandLogLine
import io.github.fu.avbforge.data.CommandStatus
import io.github.fu.avbforge.data.GateStatus
import io.github.fu.avbforge.data.WhiteBoxConsoleState

@Composable
fun WhiteBoxCommandConsole(
    state: WhiteBoxConsoleState,
    onToggleRawMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    embeddedInScroll: Boolean = true,
) {
    val manualExpanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SafetyGateStrip(
            offlineVerify = state.offlineVerifyGate,
            blockFlash = state.blockFlashGate,
            readbackVerify = state.readbackVerifyGate,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.showRawJson,
                    onClick = { onToggleRawMode(false) },
                    label = { Text("结构化命令块") },
                )
                FilterChip(
                    selected = state.showRawJson,
                    onClick = { onToggleRawMode(true) },
                    label = { Text("原始 JSON-Lines") },
                )
            }
            Text(
                text = "${(state.overallProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LinearProgressIndicator(
            progress = { state.overallProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = if (state.rebootBlocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )

        if (state.rebootBlocked) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "检测到异常或回读校验不一致，已阻止重启引导。请检查标红命令块并重新刷写或恢复备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (state.showRawJson) {
            RawJsonPane(
                lines = state.rawLines,
                modifier = if (embeddedInScroll) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp)
                } else {
                    Modifier.weight(1f, fill = false)
                },
            )
        } else if (embeddedInScroll) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.blocks.forEach { block ->
                    val expanded = manualExpanded[block.stepId]
                        ?: (block.status == CommandStatus.RUNNING || block.status == CommandStatus.FAILED)
                    CommandBlockCard(
                        block = block,
                        expanded = expanded,
                        onToggle = { manualExpanded[block.stepId] = !expanded },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.blocks, key = { it.stepId }) { block ->
                    val expanded = manualExpanded[block.stepId]
                        ?: (block.status == CommandStatus.RUNNING || block.status == CommandStatus.FAILED)
                    CommandBlockCard(
                        block = block,
                        expanded = expanded,
                        onToggle = { manualExpanded[block.stepId] = !expanded },
                    )
                }
            }
        }
    }
}

/**
 * Drop-in Material 3 Expressive container for embedding [WhiteBoxCommandConsole] inside
 * scrollable wizard screens, with optional safety-gated reboot action.
 */
@Composable
fun WhiteBoxConsoleCard(
    state: WhiteBoxConsoleState,
    onToggleRawMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "白盒命令执行控制台",
    busy: Boolean = false,
    onReboot: (() -> Unit)? = null,
    rebootLabel: String = "安全重启设备",
) {
    if (state.blocks.isEmpty() && state.rawLines.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            WhiteBoxCommandConsole(
                state = state,
                onToggleRawMode = onToggleRawMode,
                embeddedInScroll = true,
            )

            if (onReboot != null && state.readbackVerifyGate == GateStatus.PASSED) {
                Button(
                    onClick = onReboot,
                    enabled = !busy && !state.rebootBlocked,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(rebootLabel)
                }
            }
        }
    }
}

@Composable
private fun SafetyGateStrip(
    offlineVerify: GateStatus,
    blockFlash: GateStatus,
    readbackVerify: GateStatus,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GateTile(title = "1. 离线验签", status = offlineVerify, modifier = Modifier.weight(1f))
        GateTile(title = "2. 写入落盘", status = blockFlash, modifier = Modifier.weight(1f))
        GateTile(title = "3. 回读核对", status = readbackVerify, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GateTile(
    title: String,
    status: GateStatus,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (status) {
        GateStatus.PASSED -> MaterialTheme.colorScheme.primaryContainer
        GateStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        GateStatus.RUNNING -> MaterialTheme.colorScheme.secondaryContainer
        GateStatus.PENDING -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val statusText = when (status) {
        GateStatus.PASSED -> "PASS"
        GateStatus.FAILED -> "FAIL"
        GateStatus.RUNNING -> "RUN"
        GateStatus.PENDING -> "WAIT"
    }
    val textColor = when (status) {
        GateStatus.PASSED -> MaterialTheme.colorScheme.onPrimaryContainer
        GateStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        GateStatus.RUNNING -> MaterialTheme.colorScheme.onSecondaryContainer
        GateStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = textColor,
            )
        }
    }
}

@Composable
private fun CommandBlockCard(
    block: CommandBlock,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val containerColor = when (block.status) {
        CommandStatus.RUNNING -> MaterialTheme.colorScheme.secondaryContainer
        CommandStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        CommandStatus.SUCCESS -> MaterialTheme.colorScheme.surfaceContainerLow
        CommandStatus.WAITING -> MaterialTheme.colorScheme.surfaceContainerLowest
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(status = block.status)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = block.title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$ ${block.cmd}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (block.summary != null || block.durationMs != null || block.exitCode != null) {
                        val meta = buildList {
                            block.durationMs?.let { add("${it}ms") }
                            block.exitCode?.let { add("exit=$it") }
                            block.summary?.let { add(it) }
                        }.joinToString(" · ")
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }

            AnimatedVisibility(visible = expanded && block.lines.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HorizontalDivider()
                    Spacer(Modifier.height(2.dp))
                    block.lines.forEach { line ->
                        CommandOutputLine(line = line)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: CommandStatus) {
    val label = when (status) {
        CommandStatus.WAITING -> "WAIT"
        CommandStatus.RUNNING -> "RUN"
        CommandStatus.SUCCESS -> "OK"
        CommandStatus.FAILED -> "ERR"
    }
    val bg = when (status) {
        CommandStatus.WAITING -> MaterialTheme.colorScheme.surfaceContainerHigh
        CommandStatus.RUNNING -> MaterialTheme.colorScheme.primary
        CommandStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        CommandStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    val fg = when (status) {
        CommandStatus.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
        CommandStatus.RUNNING -> MaterialTheme.colorScheme.onPrimary
        CommandStatus.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        CommandStatus.FAILED -> MaterialTheme.colorScheme.onError
    }

    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CommandOutputLine(line: CommandLogLine) {
    val bg = when (line.level) {
        CommandLogLevel.OK -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        CommandLogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when (line.level) {
        CommandLogLevel.OK -> MaterialTheme.colorScheme.onPrimaryContainer
        CommandLogLevel.ERROR -> MaterialTheme.colorScheme.error
        CommandLogLevel.KV -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val rendered = if (line.kv.isEmpty()) {
        line.message
    } else {
        val pairs = line.kv.entries.joinToString("  ") { "${it.key}=${it.value}" }
        "${line.message} | $pairs"
    }

    Text(
        text = rendered,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun RawJsonPane(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun DryRunCommandDialog(
    title: String,
    safetySummary: String,
    plannedCommands: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = safetySummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = "确认后将依次执行以下命令：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    plannedCommands.forEachIndexed { idx, cmd ->
                        Text(
                            text = "${idx + 1}. $ $cmd",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认并执行")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
