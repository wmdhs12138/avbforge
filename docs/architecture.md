# AVBForge Architecture & Safety Model

## 1. Execution Architecture

AVBForge splits responsibilities cleanly into two layers:

1. **Native Rust Core (`rust-core/`)**:
   - Compiled as an `aarch64-linux-android` PIE executable and packaged into `android-app/app/src/main/jniLibs/arm64-v8a/libavbforge_core.so` (`extractNativeLibs = true`).
   - Invoked by the Android app via short-lived processes (`CoreRunner`) over a deterministic JSON-Lines (`log`, `progress`, `result`, `error`) protocol.
   - Avoids long-lived `libsu` root shells that can deadlock when OEM cgroup freezers (such as OriginOS `4:freezer:/frozen`) freeze UID 0 child processes.

2. **Android UI & Security Layer (`android-app/`, `io.github.fu.avbforge`)**:
   - **Key Vault (`KeyVaultManager`)**: Standard PKCS#8 PEM private keys are encrypted at rest with Android Keystore `AES/GCM/NoPadding`. Plaintext keys are materialized only into `0600` ephemeral temp files for the duration of a signing command and immediately zero-wiped upon completion.
   - **Version & State Tiered Safety Gate (`SafetyGate`)**:
     - Classifies Android SDK compatibility (Android 14 SDK 34 verified; Android 15+ SDK >= 35 high-risk warning).
     - Enforces a first-time migration checklist on `green + locked` without hard-blocking direct `dd` writes (supporting stock A14 temp-root migration workflows).
     - Verifies public key SHA-1 match on `yellow + locked` and warns about `unlock2` on vivo/iQOO `orange` states.

## 2. Cryptographic & Partition Safety Guarantees

- **Mandatory Pre-Flash Verification**: `flash-partition` and `flash-bundle` refuse to write any image that fails offline AVB signature and hash/hashtree verification.
- **Readback Verification**: Every flash executes `fsync`, `sync`, `blockdev --flushbufs`, full readback SHA-256 comparison against source, and a second offline AVB verification on the readback copy.
- **Lossless Ramdisk Patching**: `bootimg` preserves every existing SVR4 `newc` CPIO entry's exact raw header chunk (preserving any KernelSU variant's `init`, `init.real`, `kernelsu.ko`, and permissions byte-for-byte) while upserting DSU `.avbpubkey` entries.
- **Cross-Tool Interoperability**: Every CI run verifies bidirectional compatibility between `avbforge-core` and Google's official AOSP `avbtool.py`.
