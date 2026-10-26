## 构建

JDK 17+、SDK 36、Build Tools 36、NDK 28.2.13676358、Rust Android target：

```bash
rustup target add aarch64-linux-android
cargo test --workspace --locked
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Gradle 为 Android Rust 编译设置 NDK 和 linker。直接调用 Cargo 交叉编译时还需设置
`ANDROID_NDK_HOME`、`LIBCLANG_PATH` 和 `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`。
Soong 模块仍为 `AhdCamera`、`AhdCameraDebug`，诊断模块为 `ahd-v4l2-probe`。

## 资料

- [当前架构](docs/engineering.md)
- [用户指南、AAOS 与引用项目核查](docs/aaos-and-v4l2-reference-review.md)
- [诊断程序](tools/diagnostics/README.md)
- [平台签名与系统集成](integration/README.md)

官方 Android skills 位于本地 `skills/`，通过 `.agents/skills/` 链接，使用规则见 AGENTS.md。
工具目录不提交 Git，新 checkout 需按 AGENTS.md 安装。

## 平台签名与系统应用

Soong 的 AhdCamera/AhdCameraDebug 使用 `certificate: "platform"`、`privileged: true`、
`system_ext_specific: true`。源码位于 SDK 的 `external/ahd_camera`，集成只需：

```makefile
# 产品 mk
PRODUCT_PACKAGES += AhdCamera

# BoardConfig.mk
BOARD_VENDOR_SEPOLICY_DIRS += external/ahd_camera/integration/sepolicy
```
