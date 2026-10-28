# 独立 AHD Camera Provider

可选的系统侧组件，通过 Stable AIDL `ICameraProvider/ahd/0` 向标准 Camera2 提供 AHD 摄像头。
App 仍然使用 Kotlin Camera2，没有 JNI、设备节点权限或平台签名依赖。Provider 在独立进程
`vendor.camera-provider-ahd` 运行，安装到 vendor；不替换或链接现有 Rockchip Camera HAL，
不修改 framework、已有 USB/MIPI Provider、现有相机配置或系统 AIDL 定义。

## 代码边界与升级

| 目录 | 职责 | 升级/移植范围 |
|---|---|---|
| `core` | 节点白名单、驱动/总线身份验证、V4L2 多平面适配、能力和解码器时序读取 | 使用 Linux V4L2，不包含 Rockchip/RGA/ISP API |
| `compat/android15` | 固定版本 AOSP CameraDevice/Session、元数据、buffer/fence 与图像输出 | Android 版本相关的适配层；不覆盖 SDK 源码 |
| `service` | AIDL Provider 注册、配置节点的状态通知、并发组合和像素吞吐预算 | 稳定 AIDL NDK backend，独立设备 ID 命名空间 |
| `integration` | init、VINTF、独立 SELinux 域、配置示例 | 板级修改仅包括注册、节点身份映射和可选图形适配 |

`compat/android15/UPSTREAM.json` 记录导入 AOSP 源码的 revision 与每个原文件的 SHA-256，
`changes.patch` 记录本仓库的适配差异。保留原 Apache-2.0 许可与版权。
升级 AOSP 时先运行：

```sh
python3 external/ahd_camera/provider/tools/check-upstream.py hardware/interfaces
```

该检查用于提醒审阅上游变化，不代表每次系统升级都必须替换已固定的适配层。
首先编译现有 Provider，检查已冻结的 AIDL 版本仍受支持；只有需要接口或 buffer mapper
适配时才新增/更新 `compat/androidXX` 和它的构建依赖。运行 Camera VTS、Camera2/CTS
相关测试及实际摄像头回归。Stable AIDL 减少接口变动，不保证依赖库、策略和硬件无需验证。

## 对其他摄像头的边界

Provider 不遍历 `/dev/video*`，只检查配置明确列出的节点；打开前匹配 path，打开后检查
字符设备、V4L2 driver/card/bus，解码器时序节点还需匹配 sysfs name。
配置了解码器时，还必须提供 mediaNode；通过只读 MEDIA_IOC_G_TOPOLOGY 检查已启用的
上游链路确实到达该解码器，并在 S_FMT/STREAMON 前复核。绝不调用 MEDIA_IOC_SETUP_LINK，
也不重标记可能与其他摄像头共享的 media 节点。身份不匹配、配置错误、
重复路径/ID、无法读取时序时停止公开对应设备，不猜测或回退到其他节点。
设备 ID 必须以 `ahd-` 开头，服务为独立的 `ahd/0`；不声明其他 Provider 的 ID 或跨 Provider
并发组合，不改变现有相机的 resourceCost 或 conflictingDevices。

不重标记现有 video/media/subdev 节点，不更改其他 HAL 的访问规则。独立进程使用标准相机
HAL 域的视频设备访问权限；新增策略仅针对新进程。白名单、驱动/总线身份及已启用上游拓扑
构成采集所有权边界，不能把独立 SELinux 域描述成对其他 video_device 的绝对打开拒绝。
代码没有修改 media links、subdev 格式、ISP、USB 控件或 MIPI 配置的路径。

各路仍共享 CPU、内存带宽及 Android CameraService 的资源仲裁；独立进程不等于资源完全
隔离。`maxConcurrent`、`maxPixelRate` 和 `resourceCost` 是经过板级验证后设置的预算，
不是硬件能力的自动证明。只有实际接入并测试 USB/MIPI 后才能声明并发行为已验收。

## 能力与处理

当前实现支持单平面或多平面 V4L2 API 下的连续单内存平面 NV12。不同 Y/UV 内存平面
与非零 data_offset 明确拒绝，避免误读或越界。保留驱动返回的 bytesperline、bytesused、
时间戳与 buffer offset，NV12 经 libyuv 转换并输出至标准 Camera2 Surfaces；没有私有 RGA 库。
采集保持最大原生窗口，输出分辨率使用裁切/缩放，避免减小 CIF 窗口后丢失完整视野。
并发预算按原生像素阵列计算，不因选择较小预览而低估输入带宽。转换层随后可以独立优化，不能将尚未测试的硬件加速当作性能结论。

输出尺寸来自驱动离散尺寸，或 stepwise 范围端点及当前 G_FMT。Camera2 并发协议要求的 720p/VGA 输出，仅在驱动范围和步长
明确允许时补充；App 没有分辨率预设菜单。
配置了解码器 timingNode 时只读 `VIDIOC_SUBDEV_G_FRAME_INTERVAL`，将真实信号间隔作为
能力；未配置时使用 V4L2 ENUM_FRAMEINTERVALS。固定时序解码器不伪装成可调帧率设备。
信号制式改变后需重新探测设备/重启此独立服务再协商新能力，不修改已有 Provider。

## 构建与集成

```make
# 产品配置
PRODUCT_PACKAGES += AhdCamera AhdCameraProvider
PRODUCT_COPY_FILES += <board>/ahd_provider.xml:$(TARGET_COPY_OUT_VENDOR)/etc/ahd_camera/provider.xml

# BoardConfig.mk
BOARD_VENDOR_SEPOLICY_DIRS += external/ahd_camera/provider/integration/sepolicy
```

将经过实机核验的设备映射写入板级 provider.xml；示例位于 `integration/examples`，无需额外节点标签。
缺少 provider.xml 时服务不枚举设备，不扫描其他节点。`limits.xml` 是独立的 buffer/方向配置，
不会读取或覆盖 USB Provider 使用的 external_camera_config.xml。
不同 SoC 若需要 gralloc 的额外设备权限，在板级策略或独立 SoC adapter 中补充，保持核心
策略和代码不引用厂商符号。App 的普通 APK 安装不要求安装此系统组件；要支持当前 AHD
驱动，系统镜像需集成 Provider、VINTF 和策略并重启。

```sh
m AhdCameraProvider ahd_capture_io_test
out/host/linux-x86/nativetest64/ahd_capture_io_test/ahd_capture_io_test
```

核心测试覆盖未列入白名单的路径拒绝、多平面布局/时间戳/offset 传递、独立内存平面拒绝及
重复设备配置失败，以及已启用上游拓扑验证和反向链路拒绝。实机验收另查 enforcing 模式、Camera2 枚举与并发、持续预览/录像、隐私
开关、服务重启和其他相机回归。构建通过不等于 CTS/VTS 认证。

接口依据：[AOSP Camera HAL](https://source.android.com/docs/core/camera/camera3)、
[Stable AIDL HAL](https://source.android.com/docs/core/architecture/aidl/aidl-hals)。
并发的最低输出组合与 resourceCost 约束以当前 SDK 的 ICameraProvider.aidl 为准；示例使用
保守的每路 resourceCost=100，不降低现有相机的仲裁成本。
