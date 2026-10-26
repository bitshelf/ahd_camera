# AAOS 与 AHD 参考资料核查（2026-10-27）

当前决策：先完成 `rust-v4l2-native` 应用与采集核心；节点权限、系统服务及固件部署另行处理。
后续追加平台签名和系统应用构建要求；签名不改变设备访问的 SELinux 边界。
Camera2 应用保留在 `rust-camera2-native` 和 `kotlin-camera2`。此前允许新增独立 Provider，
后续已调整优先级，本阶段不新增、部署或修改任何 HAL/framework，也不更改节点权限。

## 官方 AAOS 与当前 Android 15 源码

Google 正推动 EVS 相机接口迁移到 Camera2。EVS 不是免 HAL 的相机接口；Android 15
`CarEvsMultiCameraPreviewApp` 通过 CarEvsManager/CarEvsService 使用 EVS HAL。当前板子
没有已注册的 EVS 服务，产品 `BOARD_CAMERA_SUPPORT_AUTOMOTIVE=false`。

- [AAOS 官方迁移方向](https://source.android.com/docs/automotive/camera/acs/overview)
- [Java/NDK Camera2、权限和迁移差异](https://source.android.com/docs/automotive/camera/acs/camera2-migration)
- [EVS 架构与 V4L2 示例](https://source.android.com/docs/automotive/camera/evs/camera-hal)

本地 `hardware/interfaces/automotive/evs/aidl/rust_impl` 是未实现采集的示例，不能直接用来
驱动 AHD。`packages/services/Car/cpp/evs/sampleDriver/aidl/src/VideoCapture.cpp` 示例使用
单平面 CAPTURE/YUYV，不能直接替代本板的多平面 API/连续 NV12 管线。

NDK Camera2 与 Kotlin Camera2 都经过系统相机服务和 Camera HAL。Rust/C++ V4L2 直采
都可以绕过 Camera HAL；更换编程语言不会改变权限、系统接口或硬件能力。

Android 15 自带 Virtual Camera 通过生产者 Surface 向 Camera2 输出图像。此版本
`VirtualCameraProvider` 返回空的并发组合、组合支持返回 false，`VirtualCameraDevice`
报告资源成本 100。因此它不能未经修改和验证就作为三路/16 路的方案。本阶段没有启动
它、修改 feature flags 或注册测试相机。

## 用户提供指南中的开源项目

下面只记录已核查的源码事实，不接受 README 的性能宣传作为本板验收结果。

| 项目/核查版本 | 已核实内容 | 本项目取舍 |
|---|---|---|
| [baihua666/android_camera_v4l2](https://github.com/baihua666/android_camera_v4l2/tree/53c7df73f6785de406a6647d49e44b9f3feabce4) | 有 `connectByPath` 与 MPLANE 分支；采集格式选择仍主要是 MJPEG/YUYV，渲染支持 NV12 不等于可直接采集本板 NV12 | 参考节点验证、能力查询与多平面处理；不直接复制为 AHD 后端 |
| [shengjunhu/android_camera_v4l2](https://github.com/shengjunhu/android_camera_v4l2/tree/1f266fbbe6772ea2dcbd40248adc4b7088daf839) | 有 APK、V4L2/OpenGL 代码；README 区分采集 MJPEG/YUYV 和渲染 YUYV/NV12/NV21 | 参考缓冲和渲染思路；不能依据“支持 NV12”宣称适配完成 |
| [ossuret-capture-viewer](https://github.com/damianmonelle/ossuret-capture-viewer/tree/4f543a20d7359a10ffeac26e9a3a4944e15128ed) | 递归 Git 树仅 16 项、未截断；有 MIT 与设计文档，但没有指南声称的 app/Kotlin/C++ 源码 | 目前不能当成可复用的 Kotlin 采集源码基线 |
| [MG4-360-Camera-App](https://github.com/jamakr4/MG4-360-Camera-App/tree/1a5c8e76df346a76806799526dabe7a6f6f609a4) | README 自述面向 MG4 AAOS 9；实际含 V4L2/JNI `cameraprobe.cpp`；GPL-3.0 | 可研究生命周期；它不是官方 AAOS Camera2 架构的证据，不复制代码进本项目 |

前三个仓库的“工程质量最好”“能开几路”等表述没有在本板复现。Gitee 项目以及其他
厂商提供的吞吐量数字本轮未验证。UVC 库解决 USB 传输，不会自动接入片上 CIF 节点。

## 指南需要纠正的部分

1. `allow untrusted_app video_device...` 不能作为 Android 15 的通用部署方案；所有 appdomain（包括平台签名系统应用）的
   通用 video_device 读写访问受到平台 neverallow 约束。chmod 只处理 DAC，不解决 SELinux。
   放进 priv-app 也不会自动获得正确的设备域、组或 linker namespace。
2. V4L2 mmap、ANativeWindow、SurfaceView 并不自动等于全链路零拷贝。必须核对 DMA-BUF、
   gralloc 导入、stride、fence、转换、编码器与显示的实际处理。不能直接断言比 Camera2 快。
3. RGA 调用必须使用协商后的真实 stride、plane 布局并检查返回状态；不能把图像宽度
   当成分配 stride。本文恢复的核心会拒绝无法确认的独立多 plane、data offset 和隔行格式。
4. ImageReader 不一定发生 CPU 像素复制；是否复制取决于 Image/AHardwareBuffer 消费方式。
5. 不采用“降低 external_camera_config.xml 缓冲数到 2–3”的通用建议。此前本板实验中，
   2 缓冲曾限制吞吐，4 缓冲才恢复约 25 fps；这也不代表其他固件应固定设为 4。
6. `/dev/videoN` 与 cameraId 都不是跨启动、跨板子的永久硬件标识，需要板级映射和节点
   身份验证。不能声称“按路径一定不会错”。AHD、GMSL 也不应混为同一种物理传输。
7. 300 行 C++、1–2 天等估时未包括错误处理、多路资源所有权、权限、热插拔、录像收尾与
   回归测试，不能作为可交付实现的依据。

## 发现的板级兼容开关（只读核查，尚未启用）

本 BSP 的 `kernel-6.1/drivers/media/platform/rockchip/cif/hw.c` 读取
`rockchip,android-usb-camerahal-enable`。`capture.c` 的 `rkcif_enum_framesizes` 和
`rkcif_enum_frameintervals` 据此把 STEPWISE/CONTINUOUS 改为 DISCRETE。
当前运行设备树 `/proc/device-tree/rkcif@27c10000` 没有此属性。

这个现成开关确实值得后续做 Camera2 适配试验，但它作用于共享 CIF 硬件，不能承诺不影响
其他 CIF/MIPI 输入。而且当前 External Provider 没有声明多路并发组合，资源成本为 100，
所以即使枚举问题解决，也不能直接认定三路预览成立。本阶段没有修改设备树或内核。
