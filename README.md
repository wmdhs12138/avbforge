# AVBForge (`io.github.fu.avbforge`)

> [!CAUTION]
> **适用范围声明（防误用警示）**：
> 本项目**仅针对 vivo iQOO Neo9（`V2338A` / `PD2338`）的 Android 14（OriginOS 4）固件**开发与实机验证。
> - 仅验证了 iQOO Neo9 **Android 14** 的 `avb_custom_key`（回锁 Yellow）自签名与 DSU 链路；
> - **不适用于** iQOO Neo9 的 Android 15 / Android 16 固件；
> - **未对任何其他品牌或机型做适配与测试**，切勿在其他设备上直接刷写分区，否则极可能导致无法开机或变砖，一切风险自行承担。

一站式 Android AVB 自签名、KernelSU 协作修补与 DSU/GSI 管理应用（**MIT / Apache-2.0 双协议**）。

## 核心特性

- **纯净自研 Rust 引擎 (`rust-core/`)**：
  - `avb2`：零 GPL 依赖的 AVB 2.0 协议实现（RSA-2048/4096 密钥与标准 `avb_pkmd.bin` 编码、`add_hash_footer`、多线程 SHA-256 Merkle `add_hashtree_footer`、`make_vbmeta_image`、全链递归 `verify_image`）。
  - `bootimg`：Android Boot v3/v4、Vendor Boot v3/v4、`fstab` `avb_keys=` 自动扫描、纯 Rust LZ4 Legacy / Gzip / XZ 流式解压、Android Sparse 转 Raw、无损 SVR4 `newc` CPIO 补丁器。
  - `avbforge-core`：统一 JSON-Lines 协议原生可执行文件，打包为 `jniLibs/arm64-v8a/libavbforge_core.so`。
- **Android 原生客户端 (`android-app/`)**：
  - Kotlin + Jetpack Compose (Material 3) 四大模块：**体检与备份 / 密钥保险箱 / 镜像工坊 / DSU 工作室**。
  - **版本与状态分级门禁**：针对 Android 14 绿灯、Android 15+ 高版本强警示、原厂 `green + locked` 首次迁移检查清单（不锁死临时 Root 直刷）。
  - **自选 KernelSU 管理器协作流程**：一键导出纯净 `init_boot.img` 交给用户偏爱的任意 KSU 管理器修补，再导回自动注入 DSU 公钥并重签，避免重启后丢失 Root。
  - **短生命周期 Root 调用 + 冻结检测**：避免 OriginOS 等系统 cgroup `freezer` 冻死常驻 `libsu` 导致的 ANR。

## 本地测试（Rust 核心）

```bash
cd rust-core
cargo test --workspace
cargo build --release --bin avbforge-core
```

## GitHub Actions 自动化构建

推送至 GitHub 后自动触发：
- **CI (`.github/workflows/ci.yml`)**：Rust 单测 + 官方 AOSP `avbtool.py` 双向交叉验签 + NDK 交叉编译 `aarch64` 核心 + Gradle 单测/Lint/Debug APK。
- **Release (`.github/workflows/release.yml`)**：打 `v*` 标签自动发布 Release APK（支持 Secrets 正式签名，未配置时自动测试签名兜底）。
