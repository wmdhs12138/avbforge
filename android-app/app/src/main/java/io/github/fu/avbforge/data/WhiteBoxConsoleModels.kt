package io.github.fu.avbforge.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class GateKind {
    OFFLINE_VERIFY,
    BLOCK_FLASH,
    READBACK_VERIFY,
}

enum class GateStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
}

enum class CommandStatus {
    WAITING,
    RUNNING,
    SUCCESS,
    FAILED,
}

enum class CommandLogLevel {
    INFO,
    KV,
    OK,
    WARN,
    ERROR,
}

data class CommandLogLine(
    val level: CommandLogLevel,
    val message: String,
    val kv: Map<String, String> = emptyMap(),
)

data class CommandBlock(
    val stepId: String,
    val title: String,
    val cmd: String,
    val status: CommandStatus = CommandStatus.WAITING,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val gate: GateKind? = null,
    val summary: String? = null,
    val lines: List<CommandLogLine> = emptyList(),
)

data class WhiteBoxConsoleState(
    val blocks: List<CommandBlock> = emptyList(),
    val rawLines: List<String> = emptyList(),
    val offlineVerifyGate: GateStatus = GateStatus.PENDING,
    val blockFlashGate: GateStatus = GateStatus.PENDING,
    val readbackVerifyGate: GateStatus = GateStatus.PENDING,
    val showRawJson: Boolean = false,
    val rebootBlocked: Boolean = false,
    val overallProgress: Float = 0f,
)

object WhiteBoxEventReducer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun reduceRawLine(state: WhiteBoxConsoleState, rawLine: String): WhiteBoxConsoleState {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return state

        val nextRaw = (state.rawLines + trimmed).takeLast(400)
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return appendFallbackLog(state.copy(rawLines = nextRaw), trimmed)

        val event = obj["event"]?.jsonPrimitive?.contentOrNull
            ?: return appendFallbackLog(state.copy(rawLines = nextRaw), trimmed)

        val base = state.copy(rawLines = nextRaw)
        return when (event) {
            "plan" -> handlePlan(base, obj)
            "step_start" -> handleStepStart(base, obj)
            "step_log" -> handleStepLog(base, obj)
            "step_end" -> handleStepEnd(base, obj)
            "log" -> handleLegacyLog(base, obj)
            "progress" -> handleLegacyProgress(base, obj)
            "error" -> handleLegacyError(base, obj)
            else -> base
        }
    }

    private fun handlePlan(state: WhiteBoxConsoleState, obj: JsonObject): WhiteBoxConsoleState {
        val steps = obj["steps"]?.jsonArray?.mapNotNull { elem ->
            val item = elem.jsonObject
            val id = item["step_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.contentOrNull ?: id
            val cmd = item["cmd"]?.jsonPrimitive?.contentOrNull ?: title
            CommandBlock(stepId = id, title = title, cmd = cmd)
        }.orEmpty()
        return if (steps.isEmpty()) state else state.copy(blocks = steps, overallProgress = 0f)
    }

    private fun handleStepStart(state: WhiteBoxConsoleState, obj: JsonObject): WhiteBoxConsoleState {
        val id = obj["step_id"]?.jsonPrimitive?.contentOrNull ?: return state
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: id
        val cmd = obj["cmd"]?.jsonPrimitive?.contentOrNull ?: title
        val gate = parseGate(obj["gate"]?.jsonPrimitive?.contentOrNull)

        val existing = state.blocks.indexOfFirst { it.stepId == id }
        val updatedBlocks = if (existing >= 0) {
            state.blocks.toMutableList().apply {
                this[existing] = this[existing].copy(
                    title = title,
                    cmd = cmd,
                    status = CommandStatus.RUNNING,
                    gate = gate ?: this[existing].gate,
                )
            }
        } else {
            state.blocks + CommandBlock(
                stepId = id,
                title = title,
                cmd = cmd,
                status = CommandStatus.RUNNING,
                gate = gate,
            )
        }

        return applyGate(state.copy(blocks = updatedBlocks), gate, GateStatus.RUNNING)
            .recomputeProgress()
    }

    private fun handleStepLog(state: WhiteBoxConsoleState, obj: JsonObject): WhiteBoxConsoleState {
        val id = obj["step_id"]?.jsonPrimitive?.contentOrNull ?: return state
        val level = parseLogLevel(obj["level"]?.jsonPrimitive?.contentOrNull)
        val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return state
        val kv = obj["kv"]?.jsonObject?.entries?.associate { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: v.toString())
        }.orEmpty()

        val line = CommandLogLine(level = level, message = message, kv = kv)
        return appendLineToBlock(state, id, line)
    }

    private fun handleStepEnd(state: WhiteBoxConsoleState, obj: JsonObject): WhiteBoxConsoleState {
        val id = obj["step_id"]?.jsonPrimitive?.contentOrNull ?: return state
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        val exitCode = obj["exit_code"]?.jsonPrimitive?.intOrNull ?: if (ok) 0 else 1
        val durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
        val gate = parseGate(obj["gate"]?.jsonPrimitive?.contentOrNull)

        val updatedBlocks = state.blocks.map { block ->
            if (block.stepId == id) {
                block.copy(
                    status = if (ok) CommandStatus.SUCCESS else CommandStatus.FAILED,
                    exitCode = exitCode,
                    durationMs = durationMs,
                    summary = summary,
                    gate = gate ?: block.gate,
                )
            } else {
                block
            }
        }

        val effectiveGate = gate ?: updatedBlocks.firstOrNull { it.stepId == id }?.gate
        val gateStatus = if (ok) GateStatus.PASSED else GateStatus.FAILED
        val blocked = state.rebootBlocked || !ok
        return applyGate(
            state.copy(blocks = updatedBlocks, rebootBlocked = blocked),
            effectiveGate,
            gateStatus,
        ).recomputeProgress()
    }

    private fun handleLegacyLog(state: WhiteBoxConsoleState, obj: JsonObject): WhiteBoxConsoleState {
        val level = parseLogLevel(obj["level"]?.jsonPrimitive?.contentOrNull)
        val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return state
        val targetId = state.blocks.lastOrNull { it.status == CommandStatus.RUNNING }?.stepId
            ?: state.blocks.lastOrNull()?.stepId
            ?: "default"
        return appendLineToBlock(state, targetId, CommandLogLine(level, message))
    }

    private fun handleLegacyProgress(state: WhiteBoxConsoleState, obj: JsonObject): WhiteBoxConsoleState {
        val stage = obj["stage"]?.jsonPrimitive?.contentOrNull ?: "progress"
        val current = obj["current"]?.jsonPrimitive?.longOrNull ?: 0L
        val total = obj["total"]?.jsonPrimitive?.longOrNull ?: 0L
        val pct = if (total > 0L) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else state.overallProgress
        val msg = if (total > 0L) "$stage: $current / $total" else stage
        val targetId = state.blocks.lastOrNull { it.status == CommandStatus.RUNNING }?.stepId ?: stage
        return appendLineToBlock(state.copy(overallProgress = pct), targetId, CommandLogLine(CommandLogLevel.INFO, msg))
    }

    private fun handleLegacyError(state: WhiteBoxConsoleState, obj: JsonObject): WhiteBoxConsoleState {
        val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
        val targetId = state.blocks.lastOrNull { it.status == CommandStatus.RUNNING }?.stepId
            ?: state.blocks.lastOrNull()?.stepId
            ?: "error"
        val updated = appendLineToBlock(state, targetId, CommandLogLine(CommandLogLevel.ERROR, message))
        val markedBlocks = updated.blocks.map { block ->
            if (block.stepId == targetId) block.copy(status = CommandStatus.FAILED, exitCode = block.exitCode ?: 1) else block
        }
        return updated.copy(blocks = markedBlocks, rebootBlocked = true)
    }

    private fun appendFallbackLog(state: WhiteBoxConsoleState, line: String): WhiteBoxConsoleState {
        val targetId = state.blocks.lastOrNull { it.status == CommandStatus.RUNNING }?.stepId
            ?: state.blocks.lastOrNull()?.stepId
            ?: "stdout"
        return appendLineToBlock(state, targetId, CommandLogLine(CommandLogLevel.INFO, line))
    }

    private fun appendLineToBlock(
        state: WhiteBoxConsoleState,
        stepId: String,
        line: CommandLogLine,
    ): WhiteBoxConsoleState {
        val idx = state.blocks.indexOfFirst { it.stepId == stepId }
        val updatedBlocks = if (idx >= 0) {
            state.blocks.toMutableList().apply {
                val cur = this[idx]
                this[idx] = cur.copy(lines = (cur.lines + line).takeLast(120))
            }
        } else {
            state.blocks + CommandBlock(
                stepId = stepId,
                title = stepId,
                cmd = "avbforge-core $stepId",
                status = CommandStatus.RUNNING,
                lines = listOf(line),
            )
        }
        return state.copy(blocks = updatedBlocks)
    }

    private fun applyGate(
        state: WhiteBoxConsoleState,
        gate: GateKind?,
        status: GateStatus,
    ): WhiteBoxConsoleState = when (gate) {
        GateKind.OFFLINE_VERIFY -> state.copy(offlineVerifyGate = status)
        GateKind.BLOCK_FLASH -> state.copy(blockFlashGate = status)
        GateKind.READBACK_VERIFY -> state.copy(readbackVerifyGate = status)
        null -> state
    }

    private fun WhiteBoxConsoleState.recomputeProgress(): WhiteBoxConsoleState {
        if (blocks.isEmpty()) return this
        val completed = blocks.count { it.status == CommandStatus.SUCCESS }
        val running = blocks.count { it.status == CommandStatus.RUNNING }
        val progress = ((completed + running * 0.4f) / blocks.size.toFloat()).coerceIn(0f, 1f)
        return copy(overallProgress = progress)
    }

    private fun parseGate(raw: String?): GateKind? = when (raw) {
        "offline_verify" -> GateKind.OFFLINE_VERIFY
        "block_flash" -> GateKind.BLOCK_FLASH
        "readback_verify" -> GateKind.READBACK_VERIFY
        else -> null
    }

    private fun parseLogLevel(raw: String?): CommandLogLevel = when (raw?.lowercase()) {
        "kv" -> CommandLogLevel.KV
        "ok" -> CommandLogLevel.OK
        "warn", "warning" -> CommandLogLevel.WARN
        "error", "err" -> CommandLogLevel.ERROR
        else -> CommandLogLevel.INFO
    }
}
