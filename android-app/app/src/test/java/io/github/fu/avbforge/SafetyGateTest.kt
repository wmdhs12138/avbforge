package io.github.fu.avbforge

import io.github.fu.avbforge.core.RiskLevel
import io.github.fu.avbforge.core.SafetyGate
import io.github.fu.avbforge.data.DeviceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {

    @Test
    fun a14YellowMatchingKeyIsSafe() {
        val snap = DeviceSnapshot(
            manufacturer = "vivo",
            model = "V2338A",
            androidRelease = "14",
            sdkInt = 34,
            verifiedBootState = "yellow",
            deviceState = "locked",
            vbmetaPubkeySha1 = "1c69d097e111ed8ae814fc7bf8957ec0721c654d",
        )
        val res = SafetyGate.assessFlash(
            snapshot = snap,
            selectedKeySha1 = "1c69d097e111ed8ae814fc7bf8957ec0721c654d",
            includeKsuRoot = true,
            hasStockBackup = true,
        )
        assertEquals(RiskLevel.SAFE, res.riskLevel)
        assertFalse(res.requireFullBundle)
        assertTrue(res.requiredChecklist.isEmpty())
    }

    @Test
    fun a14GreenLockedRequiresChecklistAndWarnsWithoutKsu() {
        val snap = DeviceSnapshot(
            manufacturer = "vivo",
            model = "V2338A",
            androidRelease = "14",
            sdkInt = 34,
            verifiedBootState = "green",
            deviceState = "locked",
        )
        val res = SafetyGate.assessFlash(
            snapshot = snap,
            selectedKeySha1 = "abc123",
            includeKsuRoot = false,
            hasStockBackup = false,
        )
        assertEquals(RiskLevel.CAUTION, res.riskLevel)
        assertTrue(res.requireFullBundle)
        assertTrue(res.requiredChecklist.any { it.contains("格式化数据分区") })
        assertTrue(res.requiredChecklist.any { it.contains("KernelSU") })
        assertTrue(res.requiredChecklist.any { it.contains("黄金备份") })
    }

    @Test
    fun a15TriggersHighRiskBanner() {
        val snap = DeviceSnapshot(
            manufacturer = "vivo",
            model = "V2338A",
            androidRelease = "15",
            sdkInt = 35,
            verifiedBootState = "green",
            deviceState = "locked",
        )
        val res = SafetyGate.assessFlash(
            snapshot = snap,
            selectedKeySha1 = "abc123",
            includeKsuRoot = true,
            hasStockBackup = true,
        )
        assertEquals(RiskLevel.HIGH_RISK, res.riskLevel)
        assertTrue(res.sdkBannerTitle.contains("高风险"))
    }

    @Test
    fun nonNeo9DeviceTriggersHighRiskWarning() {
        val snap = DeviceSnapshot(
            manufacturer = "Xiaomi",
            model = "23116PN5BC",
            device = "houji",
            androidRelease = "14",
            sdkInt = 34,
            verifiedBootState = "yellow",
            deviceState = "locked",
            vbmetaPubkeySha1 = "abc123",
        )
        val res = SafetyGate.assessFlash(
            snapshot = snap,
            selectedKeySha1 = "abc123",
            includeKsuRoot = true,
            hasStockBackup = true,
        )
        assertEquals(RiskLevel.HIGH_RISK, res.riskLevel)
        assertTrue(res.requiredChecklist.any { it.contains("非 iQOO Neo9") })
    }
}
