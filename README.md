# AHD Camera · Kotlin Camera2

## 实验性鱼眼矫正

`kotlin-camera2-dewarp` 使用 Kotlin＋OpenGL ES 做近似鱼眼到直线投影，默认开启。
点击摄像头画面，在画面设置中调节矫正强度、镜头水平视角和输出水平视野；
关闭开关可对照原图。每路独立保存参数，录制期间锁定；小窗口可滚动设置，或先展开单路再调节。

预览和 MP4 使用同一组映射，帧率角标统计实际 GPU 输出帧。
默认镜头视角 180°、输出视野 100°、强度 100% 只是可调初值，尚未做镜头标定。
矫正会裁去部分边缘视野，仍可能残留畸变。没有新增 JNI、RGA/AVS 私有库或 HAL/framework 修改。

Gradle Debug APK 可普通安装；AOSP 系统版沿用原平台签名，可用 `adb install -r` 更新现有系统 App。
仅构建 APK 不会刷新整包固件；需要整包部署时另构建对应系统镜像并打包。

已在目标板 Mali GPU 上验证三路 1080p 近似矫正约 25 fps、原图对照、实时强度调节、
分辨率/全屏/前后台切换，以及 1080p H.264 矫正录像。方向、弯曲网格拉直、
Surface 销毁保护和中英文资源共 5 项板上测试通过；Gradle 单元测试/lint 与 AOSP 系统 App 编译通过。
截图、测试日志及录像检查结果保存在本地 `dist/validation/dewarp/`，APK 保存在 `dist/`。
开发期间旧版 App 的窗口销毁和强制热更新曾触发独立 AHD Provider 的 session 退出异常，
并导致 CameraService 恢复。App 的 Surface 销毁竞态已修复，最终常规切换测试中服务 PID 不变；
强杀客户端的 Provider 清理路径仍需单独加固，尚不能据此声明所有异常退出场景稳定。

## AOSP 系统集成

预装到 Android 源码树，把仓库放到 `external/ahd_camera`，产品配置仅添加：

```make
PRODUCT_PACKAGES += AhdCamera
```

然后在已选择产品的 AOSP 环境编译：

```sh
m AhdCamera
# 独立包名，适合与 release 并存调试
m AhdCameraDebug
```

### 可选 Provider 集成
产品配置添加服务及板级节点映射：

```make
PRODUCT_PACKAGES += AhdCameraProvider
PRODUCT_COPY_FILES += \
    <板级目录>/ahd_provider.xml:$(TARGET_COPY_OUT_VENDOR)/etc/ahd_camera/provider.xml
```

在 `BoardConfig.mk` 中登记仓库内的策略：

```make
BOARD_VENDOR_SEPOLICY_DIRS += external/ahd_camera/provider/integration/sepolicy
```

## 更多文档

- [AVS 与鱼眼校正资料核对](docs/dewarp-avs-review.md)

- [工程结构与验证](docs/engineering.md)
- [原型与 App 对应关系](docs/ui-prototype.md)
- [参考项目](docs/reference-projects.md)
