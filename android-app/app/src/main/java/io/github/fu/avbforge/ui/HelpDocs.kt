package io.github.fu.avbforge.ui

data class HelpDoc(
    val title: String,
    val summary: String,
    val bullets: List<String> = emptyList(),
    val caution: String? = null,
)

object HelpDocs {

    val ACTIVE_SLOT = HelpDoc(
        title = "什么是 A/B 活动槽位？",
        summary = "iQOO Neo9 采用 A/B 双槽位分区设计，同一套引导分区同时存在 _a 和 _b 两份副本。",
        bullets = listOf(
            "活动槽位（当前运行槽位）：手机此刻正在使用的系统与引导分区，例如 _b。",
            "非活动槽位：另一份备用槽位（例如 _a），通常存放上一次 OTA 升级前的旧镜像。",
            "备份与刷写前务必确认目标槽位，避免备错或写错槽位。",
        ),
    )

    val BACKUP_PARTITIONS = HelpDoc(
        title = "备份分区选择与必选清单",
        summary = "默认列出的 7 个分区对应本机 vbmeta 完整的 AVB 签名校验链（总计约 449 MB），无需备份 10GB+ 的 super 分区。",
        bullets = listOf(
            "首次从原厂 (Green) 迁移自签名：7 个分区全部必选（vbmeta、vbmeta_system、init_boot、boot、vendor_boot、dtbo、recovery），缺一会导致 Bootloader 拒启。",
            "已是 Yellow 自签名，仅更新 KSU 或注入 DSU 公钥：只需勾选「最小备份」（init_boot 8MB + vbmeta 64KB），几秒完成。",
            "recovery (192MB) 与 vendor_boot (128MB) 体积最大，日常只折腾 init_boot 时无需反复备份。",
        ),
    )

    val VBMETA_KEY = HelpDoc(
        title = "vbmeta 公钥指纹 (SHA-1)",
        summary = "代表当前活动槽位 vbmeta 分区使用的签名公钥唯一指纹。",
        bullets = listOf(
            "Yellow 回锁状态下，如果你在「密钥库」导入的私钥 SHA-1 与此一致，说明私钥匹配，可安全单分区增量更新 init_boot。",
            "如果 SHA-1 不一致，说明更换了密钥，必须同时更新 Bootloader 的 avb_custom_key 并全套重签全部链式分区。",
        ),
    )

    val DSU_PUBKEY = HelpDoc(
        title = "为什么回锁机需要注入 DSU 公钥？",
        summary = "Bootloader 的 avb_custom_key 与 DSU 的校验信任根是两套独立机制。",
        bullets = listOf(
            "DSU 启动时由 first_stage_mount 读取 init_boot/vendor_boot ramdisk 中的 /avb/*-gsi.avbpubkey（由 fstab avb_keys 指定）来验签 GSI。",
            "国行 OriginOS 默认未内置任何 .avbpubkey，导致回锁状态下无法直接引导 DSU。",
            "开启注入后，本应用会把你的 avb_pkmd.bin 无损写入 init_boot 的 ramdisk，同时 100% 保留 KernelSU 补丁。",
        ),
    )

    val FREEZER_ORIGINOS = HelpDoc(
        title = "OriginOS 后台冻结与卡死原因",
        summary = "OriginOS 的 cgroup freezer 墓碑机制在切后台时会连同 App 的 Root 子进程 (sh) 一并冻结。",
        bullets = listOf(
            "重新切回前台时，系统可能只解冻主进程而漏解冻 UID 0 的 sh 子进程，导致常驻 Root 管理器界面卡死。",
            "虽然 AVBForge 已改用短生命周期执行器规避，仍建议将本应用和 KernelSU 管理器设为「允许后台高耗电」，避免大镜像签名中途被系统冻结。",
        ),
    )

    val KEY_BITS = HelpDoc(
        title = "RSA-2048 与 RSA-4096 如何选择？",
        summary = "iQOO Neo9 原厂与常见 avb_custom_key 方案默认使用 SHA256_RSA2048（对应 520 字节 avb_pkmd.bin）。",
        bullets = listOf(
            "RSA-2048（推荐）：兼容性最好，手机端生成与验签极快。",
            "RSA-4096：更高安全强度（1032 字节 avb_pkmd.bin），但需先确认你的机型 Bootloader 支持 4096 位自定义公钥。",
        ),
    )

    val PKMD_EXPORT = HelpDoc(
        title = "avb_pkmd.bin 的作用",
        summary = "avb_pkmd.bin 是按 AOSP libavb 二进制格式编码的公钥文件（含蒙哥马利常数），用于注册到 Bootloader 的 avb_custom_key。",
        bullets = listOf(
            "私钥本身加密存放在 Android Keystore 中，不会以明文导出。",
            "avb_pkmd.bin 仅包含公钥信息，可安全导出到 Download/AVBForge 供首次配置 avb_custom_key 使用。",
        ),
    )

    val FORGE_STOCK_DIR = HelpDoc(
        title = "原厂镜像 / 备份目录要求",
        summary = "镜像工坊会读取该目录中的 vbmeta.img、vbmeta_system.img 及各引导分区，按原 AVB 元数据（分区大小、rollback_index、属性）重新签名。",
        bullets = listOf(
            "最简单方式：先在「体检」页执行一次完整 7 分区备份，再点「使用最近一次黄金备份」一键填入。",
            "重签 vbmeta_system 时会自动无损保留原厂 7 个逻辑分区（system/vendor/product 等）的 hashtree 描述符，不会破坏 super 校验。",
        ),
    )

    val KSU_COLLAB = HelpDoc(
        title = "为什么推荐随自签名一并刷入 KernelSU？",
        summary = "原厂 Android 14 用户通常依赖临时 Root 完成首次迁移。",
        bullets = listOf(
            "如果首次刷入不含 KernelSU 的纯净自签名镜像，重启进入 Yellow 系统后将失去 Root，后续装 KSU 必须重新获取临时 Root。",
            "本应用不绑定单一 KSU 分支：你可以导出纯净 init_boot.img，用你偏爱的任何管理器（官版 / Next / SukiSU 等）修补后再选回，由本应用自动保留 KSU 补丁、注入 DSU 公钥并重签。",
        ),
        caution = "若关闭此开关，重启后将不具备持久 Root 能力。",
    )

    val GSI_SIGN = HelpDoc(
        title = "GSI 极速自签名原理",
        summary = "在回锁 Yellow 状态下，DSU 只能引导通过你自定义公钥验签的 system 镜像。",
        bullets = listOf(
            "支持直接选择 .img、.img.xz、.img.gz 以及 Android Sparse 稀疏镜像，全自动流式解压与转 Raw。",
            "内置 Rust 多线程引擎并行计算 4096 B 分块的 dm-verity SHA-256 Merkle Hashtree，并追加自签名 AVB Footer。",
            "自动写入符合回锁防回滚要求的 security_patch 属性，并执行离线全树验签，确保万无一失才交给 gsi_tool。",
        ),
    )

    val GSI_GENFS_FIX = HelpDoc(
        title = "自动修复 Android 15/16/17 SELinux 冲突",
        summary = "解决高版本 GSI 在 Android 13 Vendor (VNDK 33) 机型上因 secilc 策略编译冲突导致无法开机的问题。",
        bullets = listOf(
            "Android 15/16/17 GSI 新增的 plat_sepolicy_genfs_*.cil 规则（例如 /power/mem_sleep、/class/typec）经常与本机 /vendor/etc/selinux/vendor_sepolicy.cil 重复定义。",
            "开启后，签名引擎会先以只读方式快速扫描 GSI；若未发现冲突则零修改直接跳过；若发现冲突则自动剔除冲突行并无损压缩 ext4 镜像后再签名。",
            "此功能仅在扫描/修补 ext4 时短暂调用 Root 挂载循环设备，不修改原厂系统任何文件。",
        ),
    )

    val DSU_USERDATA = HelpDoc(
        title = "DSU 独立 Userdata 空间",
        summary = "DSU 会为临时体验的 GSI 在 /data/gsi/dsu 下单独划出一块虚拟存储作为它的 /data 分区。",
        bullets = listOf(
            "完全与原厂 OriginOS 数据隔离，退出或清理 DSU 不会影响原系统里的任何文件和应用。",
            "Android 15/16 带 GApps 的 GSI 建议至少分配 16 GiB，避免首次开机装应用空间不足。",
        ),
    )

    val DSU_SINGLE_BOOT = HelpDoc(
        title = "单次启动模式（-s 防变砖）",
        summary = "强烈建议始终保持开启的防变砖保险机制。",
        bullets = listOf(
            "开启后，重启只会尝试进入一次 GSI；无论 GSI 正常退出还是卡开机/黑屏，长按电源键强制重启一次即可自动回到原厂 OriginOS。",
            "关闭后每次重启都会默认继续进入 GSI，直到手动禁用或清理。",
        ),
    )

    val DSU_WIPE_DATA = HelpDoc(
        title = "重置 GSI 数据 vs 彻底卸载",
        summary = "两种清理方式均完全隔离，绝不会影响原厂 OriginOS 的任何数据。",
        bullets = listOf(
            "仅重置 GSI 数据（wipe-data）：只清空 GSI 的独立 /data 分区，下次进入 GSI 会重新走开机向导，但无需重新安装 3 GB+ 的系统镜像。",
            "彻底卸载（wipe）：删除 /data/gsi/dsu 下全部系统与数据镜像，立刻释放十余 GB 机身存储空间。",
        ),
    )

    val AVB_CHAIN_FSTAB = HelpDoc(
        title = "AVB 链式分区与 fstab 详情",
        summary = "只读展示本机底层的两项关键校验配置，不会修改任何分区。",
        bullets = listOf(
            "vbmeta 链式分区：Bootloader 验证完 vbmeta 后继续链式验签的 6 个分区，首次换密钥自签名时这 6 个分区 + vbmeta 必须全部重签。",
            "fstab avb_keys：系统 /vendor/etc/fstab.qcom 写死的 DSU 公钥文件名白名单，注入到 init_boot 的自定义公钥必须命中其中一个文件名。",
        ),
    )

    val DSU_FIRST_SETUP = HelpDoc(
        title = "首次启用自签名 DSU 支持",
        summary = "仅修改当前槽位的 init_boot（8 MB），无需重签其他分区，也不会清除用户数据。",
        bullets = listOf(
            "自动先完整备份当前 init_boot 到 Download/AVBForge 作为退路。",
            "在保留你现有 KernelSU 补丁的前提下，把你的 avb_pkmd.bin 注入 ramdisk 并用同一把密钥重签。",
            "写回 init_boot 并重启生效一次后，以后就能无限次切换各种自签名 GSI 体验。",
        ),
        caution = "前提：设备必须已经处于 Yellow 自签名状态，且密钥库里的默认私钥与本机 vbmeta 公钥一致。",
    )

    val KSU_UPDATE_NO_DIRECT = HelpDoc(
        title = "为什么回锁机严禁使用 KSU 管理器「直接安装」？",
        summary = "KernelSU 管理器的「直接安装」会直接向 init_boot 分区写入未签名镜像，导致 AVB 校验失败。",
        bullets = listOf(
            "在 Yellow 回锁状态下，Bootloader 每次开机都会校验 init_boot 的 AVB 签名是否匹配你的私钥。",
            "如果使用 KSU 管理器直接安装，写入的镜像没有你的 AVB Footer，重启后会立刻无法进入系统。",
            "正确做法：用 AVBForge 导出纯净底包 → 在 KSU 管理器选择『修补文件』 → 交回 AVBForge 重新注入 DSU 公钥并自签名后再单刷 init_boot（不清空数据）。",
        ),
        caution = "只要保持同一把自签名私钥，单刷 init_boot 绝对不会格式化用户数据。",
    )

    val KSU_STRIP_EXPLAIN = HelpDoc(
        title = "为什么要自动剥离旧版 KernelSU？",
        summary = "如果你当前的 init_boot 已经打过 KernelSU LKM 补丁，直接把它再交给新的 KSU 管理器二次修补容易引发冲突或启动失败。",
        bullets = listOf(
            "KernelSU LKM 修补 init_boot 时，会把原厂 init 改名为 init.real，再放入自己的 init 桩程序与 kernelsu.ko。",
            "AVBForge 导出底包前会自动先备份当前可用的 init_boot，再将 init.real 还原为 init 并移除 kernelsu.ko / ksu_config，得到 100% 干净的原厂底包。",
            "无论你想升级官版 KSU，还是换用 KSU Next / SukiSU，都可以放心拿这个纯净底包去修补。",
        ),
    )
}
