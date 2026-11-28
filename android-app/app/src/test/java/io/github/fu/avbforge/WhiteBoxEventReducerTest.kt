package io.github.fu.avbforge

import io.github.fu.avbforge.data.CommandStatus
import io.github.fu.avbforge.data.GateStatus
import io.github.fu.avbforge.data.WhiteBoxConsoleState
import io.github.fu.avbforge.data.WhiteBoxEventReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBoxEventReducerTest {

    @Test
    fun fullThreeGateFlowTransitionsGatesAndProgress() {
        var state = WhiteBoxConsoleState()
        val events = listOf(
            """{"event":"plan","steps":[{"step_id":"verify_init_boot","title":"离线验签","cmd":"avbforge-core avb verify"},{"step_id":"flash_init_boot","title":"写入落盘","cmd":"avbforge-core flash-partition"},{"step_id":"readback_init_boot","title":"回读核对","cmd":"avbforge-core verify-readback"}]}""",
            """{"event":"step_start","step_id":"verify_init_boot","title":"离线验签","cmd":"avbforge-core avb verify","gate":"offline_verify"}""",
            """{"event":"step_log","step_id":"verify_init_boot","level":"kv","message":"pre-flash","kv":{"sha256":"abc123"}}""",
            """{"event":"step_end","step_id":"verify_init_boot","ok":true,"exit_code":0,"duration_ms":42,"gate":"offline_verify","summary":"PASS"}""",
            """{"event":"step_start","step_id":"flash_init_boot","title":"写入落盘","cmd":"avbforge-core flash-partition","gate":"block_flash"}""",
            """{"event":"step_end","step_id":"flash_init_boot","ok":true,"exit_code":0,"duration_ms":120,"gate":"block_flash","summary":"FLUSHED"}""",
            """{"event":"step_start","step_id":"readback_init_boot","title":"回读核对","cmd":"avbforge-core verify-readback","gate":"readback_verify"}""",
            """{"event":"step_end","step_id":"readback_init_boot","ok":true,"exit_code":0,"duration_ms":88,"gate":"readback_verify","summary":"VERIFIED"}""",
        )
        events.forEach { line ->
            state = WhiteBoxEventReducer.reduceRawLine(state, line)
        }

        assertEquals(3, state.blocks.size)
        assertEquals(GateStatus.PASSED, state.offlineVerifyGate)
        assertEquals(GateStatus.PASSED, state.blockFlashGate)
        assertEquals(GateStatus.PASSED, state.readbackVerifyGate)
        assertFalse(state.rebootBlocked)
        assertEquals(1f, state.overallProgress, 0.001f)
        assertEquals("abc123", state.blocks[0].lines.first().kv["sha256"])
    }

    @Test
    fun failedReadbackBlocksRebootAndMarksGateFailed() {
        var state = WhiteBoxConsoleState()
        state = WhiteBoxEventReducer.reduceRawLine(
            state,
            """{"event":"step_start","step_id":"readback_init_boot","title":"回读核对","cmd":"avbforge-core verify-readback","gate":"readback_verify"}""",
        )
        state = WhiteBoxEventReducer.reduceRawLine(
            state,
            """{"event":"step_end","step_id":"readback_init_boot","ok":false,"exit_code":1,"duration_ms":55,"gate":"readback_verify","summary":"sha256 mismatch"}""",
        )

        assertEquals(GateStatus.FAILED, state.readbackVerifyGate)
        assertEquals(CommandStatus.FAILED, state.blocks.first().status)
        assertTrue(state.rebootBlocked)
    }

    @Test
    fun legacyLogAndErrorFallbackStillAttachToActiveBlock() {
        var state = WhiteBoxConsoleState()
        state = WhiteBoxEventReducer.reduceRawLine(
            state,
            """{"event":"log","level":"info","message":"Preparing GSI..."}""",
        )
        state = WhiteBoxEventReducer.reduceRawLine(
            state,
            """{"event":"error","message":"disk full"}""",
        )

        assertEquals(1, state.blocks.size)
        assertEquals(CommandStatus.FAILED, state.blocks.first().status)
        assertTrue(state.rebootBlocked)
    }
}
