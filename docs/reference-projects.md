# 参考项目

用户提供的 camera-rs、iroh-live、cosmic-ext-camera、GNOME Snapshot 和 gstreamer-rs
用于对照能力发现、状态管理、渲染与录制职责分离。本分支没有引入这些项目的库或桌面后端。
此前评估的 rust-mobile/ndk 与 Rust 采集路线保留在独立分支，不属于 Kotlin App 的依赖。

本分支实现以 Android 源码树提供的 Camera2、MediaCodec、MediaMuxer、MediaStore 和
Compose API 为边界。应用侧语言选择不会增加系统公开的摄像头或并发组合。
