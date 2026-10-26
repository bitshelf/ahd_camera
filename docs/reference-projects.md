> 历史实验记录：其中 HAL 修改和 vendor/camera 集成已撤回，不能作为当前部署步骤。
> 当前两个分支均禁止修改 HAL/framework，直采系统集成见 [集成说明](../integration/README.md)。

# 参考项目与取舍

以下是设计参考，不表示引入了全部依赖或复制其源码。

| 项目 | 参考点与采用范围 |
|---|---|
| [rust-mobile/ndk](https://github.com/rust-mobile/ndk) | NativeWindow、HardwareBuffer 所有权和有界 ImageReader；当前没有完整高层 Camera2 模块。本工程由 NDK 头生成 Camera2 绑定并管理 RAII 生命周期 |
| [camera-rs](https://github.com/zibo-chen/camera-rs) | 能力协商、错误边界及帧所有权；Android 默认 Camera2。采用逐路配置，不引入桌面后端和无用转换依赖 |
| [iroh-live](https://github.com/n0-computer/iroh-live) | Android Camera2 bridge、硬件编码和渲染分离；当前没有传输需求，不加入 P2P/MoQ 栈 |
| [cosmic-ext-camera](https://github.com/cosmic-utils/cosmic-ext-camera) | Rust 相机状态与设备切换设计，不复制桌面 UI 或平台后端 |
| [GNOME Snapshot](https://github.com/GNOME/snapshot) | 应用状态与媒体管线职责分离，不引入 GTK、PipeWire、WirePlumber |
| [gstreamer-rs](https://github.com/GStreamer/gstreamer-rs) | 管线状态、buffer 生命周期和背压语义；当前使用 Android Camera2/MediaCodec，不添加 GStreamer 插件运行时 |

Rust 与 Kotlin Camera2 使用同一系统服务，更换语言本身不等于减少像素拷贝或降低延时。
采用 Rust 是为了明确 native 所有权及故障边界，性能结论来自同负载实测。

官方依据：

- https://developer.android.com/ndk/reference/group/camera
- https://source.android.com/docs/core/camera/camera3
- https://source.android.com/docs/core/graphics/arch-bq-gralloc
