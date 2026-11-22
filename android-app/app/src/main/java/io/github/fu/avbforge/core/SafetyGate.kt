package io.github.fu.avbforge.core

import io.github.fu.avbforge.data.DeviceSnapshot

enum class RiskLevel {
    SAFE,
    CAUTION,
    HIGH_RISK,
}

data class SafetyAssessment(
    val riskLevel: RiskLevel,
    val sdkBannerTitle: String,
    val sdkBannerDetail: String,
    val stateNotice: String,
    val requiredChecklist: List<String>,
    val requireFullBundle: Boolean,
)

object SafetyGate {

    fun isSupportedNeo9(snapshot: DeviceSnapshot): Boolean {
        return snapshot.model.equals("V2338A", ignoreCase = true) ||
            snapshot.device.startsWith("PD2338", ignoreCase = true)
    }

    fun assessFlash(
        snapshot: DeviceSnapshot,
        selectedKeySha1: String?,
        includeKsuRoot: Boolean,
        hasStockBackup: Boolean,
    ): SafetyAssessment {
        val isNeo9 = isSupportedNeo9(snapshot)
        val isVivoFamily = snapshot.manufacturer.equals("vivo", ignoreCase = true) ||
            snapshot.model.startsWith("V2", ignoreCase = true) ||
            snapshot.device.startsWith("PD", ignoreCase = true)

        val (sdkRisk, sdkTitle, sdkDetail) = when {
            snapshot.sdkInt == 34 -> Triple(
                RiskLevel.SAFE,
                "Android 14 (SDK 34) — 已验证支持 avb_custom_key 链路",
                "当前系统版本适配完整自签名回锁链路，支持在临时/正式 Root 下直接写入已验签镜像。"
            )
            snapshot.sdkInt >= 35 -> Triple(
                RiskLevel.HIGH_RISK,
                "Android ${snapshot.androidRelease} (SDK ${snapshot.sdkInt}) — 高版本固件高风险警示",
                if (isVivoFamily) {
                    "实测部分 vivo/iQOO 的 A15/A16 固件已收紧或禁用 avb_custom_key 启动链路，直接写入自签名镜像可能导致无法启动。请确认本机 BL 确实支持后再继续！"
                } else {
                    "Android 15+ 固件的 Bootloader AVB 策略可能已变更，请先确认本机支持 avb_custom_key 回锁启动后再写入。"
                }
            )
            snapshot.sdkInt in 31..33 -> Triple(
                RiskLevel.CAUTION,
                "Android ${snapshot.androidRelease} (SDK ${snapshot.sdkInt}) — 建议先确认 BL 兼容",
                "请先确认设备 Bootloader 支持 avb_custom_key 并保留完整原厂备份。"
            )
            else -> Triple(
                RiskLevel.CAUTION,
                "未知 Android 版本 (SDK ${snapshot.sdkInt})",
                "未能识别系统版本，请谨慎确认设备兼容性。"
            )
        }

        val checklist = mutableListOf<String>()
        var overallRisk = sdkRisk
        var requireFullBundle = false

        val vbs = snapshot.verifiedBootState.lowercase()
        val devState = snapshot.deviceState.lowercase()

        val stateNotice = when {
            vbs == "yellow" && devState == "locked" -> {
                val keyMatches = !selectedKeySha1.isNullOrBlank() &&
                    selectedKeySha1.equals(snapshot.vbmetaPubkeySha1, ignoreCase = true)
                if (keyMatches) {
                    "设备已处于 Yellow 回锁自签名状态，且当前所选密钥与 vbmeta 公钥指纹一致，可安全进行单分区增量刷写（如仅更新 init_boot，不会格式化用户数据）。"
                } else {
                    overallRisk = maxRisk(overallRisk, RiskLevel.CAUTION)
                    requireFullBundle = true
                    checklist += "【数据清除警告】更换签名密钥会改变信任根，重启后系统将自动格式化数据分区（清空所有个人数据），请务必提前备份重要资料！"
                    checklist += "当前所选密钥与实机 vbmeta 公钥不一致，必须全套重签所有链式分区与 vbmeta 后一起刷入。"
                    "检测到更换签名密钥：请确保 Bootloader 已同步更新为新密钥的 avb_pkmd.bin，并全套重签刷入。"
                }
            }
            vbs == "green" && devState == "locked" -> {
                overallRisk = maxRisk(overallRisk, RiskLevel.CAUTION)
                requireFullBundle = true
                checklist += "【数据清除警告】首次从原厂系统刷入自签名镜像后，重启时系统会自动格式化数据分区（恢复出厂、清空所有照片与应用数据），我已提前备份重要资料。"
                if (!hasStockBackup) {
                    checklist += "尚未检测到原厂全分区黄金备份，强烈建议先执行「备份原厂引导分区」。"
                }
                checklist += "确认 Bootloader 已注册与当前私钥匹配的 avb_pkmd.bin（avb_custom_key）。"
                checklist += "本次必须刷入通过离线全链验签的完整重签包（含 vbmeta、vbmeta_system 及全部链式分区）。"
                if (!includeKsuRoot) {
                    checklist += "注意：你当前未勾选随本轮一并刷入 KernelSU，重启后将没有 Root，后续操作需重新获取临时 Root。"
                }
                "当前处于原厂 Green 回锁状态（首次迁移模式）：刷入自签名镜像后系统会格式化数据分区，请逐项核对检查清单后再写入。"
            }
            vbs == "orange" -> {
                if (isVivoFamily) {
                    overallRisk = maxRisk(overallRisk, RiskLevel.CAUTION)
                    checklist += "vivo/iQOO 机型在 Orange 状态下可能触发 unlock2 强校验拒启，建议优先走 avb_custom_key 回锁 Yellow 链路。"
                }
                "当前处于 Orange 解锁状态，允许直接写入已验签镜像。"
            }
            else -> {
                overallRisk = maxRisk(overallRisk, RiskLevel.CAUTION)
                "当前 AVB 状态为 $vbs / $devState，请核对签名链与目标槽位后再写入。"
            }
        }

        if (sdkRisk == RiskLevel.HIGH_RISK) {
            checklist.add(0, "我已知晓 Android ${snapshot.androidRelease} 固件可能不支持 avb_custom_key，并自行承担刷写风险。")
        }

        if (!isNeo9) {
            overallRisk = RiskLevel.HIGH_RISK
            checklist.add(
                0,
                "当前机型（${snapshot.manufacturer} ${snapshot.model} / ${snapshot.device}）非 iQOO Neo9 (V2338A/PD2338)，本项目未适配其他机型，我已知晓强行刷写可能导致变砖并自行承担风险。"
            )
        }

        return SafetyAssessment(
            riskLevel = overallRisk,
            sdkBannerTitle = sdkTitle,
            sdkBannerDetail = sdkDetail,
            stateNotice = stateNotice,
            requiredChecklist = checklist,
            requireFullBundle = requireFullBundle,
        )
    }

    private fun maxRisk(a: RiskLevel, b: RiskLevel): RiskLevel =
        if (a.ordinal >= b.ordinal) a else b
}
