# Rust Camera HAL 分支

`rust-camera-hal` 从 `kotlin-camera2-dewarp` 创建。App 保持 Kotlin Camera2 和 GPU 矫正，
新增系统服务沿用 `AhdCameraProvider` 模块、`ahd/0` 实例和原有板级集成方式。

## 当前迁移边界

这是 Rust Provider + C++ CameraDevice/Session 的混合实现，**尚不是全 Rust Camera HAL**。
Provider 的 Binder 接口实现、设备注册表、单线程状态通知、并发组合、重复 ID 检查、
溢出检查及吞吐预算改为 Rust；Device AIDL 入口及结果/通知交付也由 Rust 包装。
原 `provider/service` 的 C++ 服务已移除。

| 路径 | 实现 |
|---|---|
| `provider/rust/main.rs` | 冻结的 Camera Provider V1 Rust AIDL backend、服务注册和设备发现 |
| `provider/rust/policy.rs` | 不依赖 Android 的资源校验，可运行主机测试 |
| `provider/rust/adapter.rs` | 唯一 Rust unsafe 边界，封装 C ABI 和 Binder 强引用转移 |
| `provider/rust/device.rs` | Rust Device AIDL 包装及每会话有界、保序的通知/结果交付线程 |
| `provider/bridge/adapter.{h,cpp}` | 配置装载、只读探测、创建既有设备和读取元数据 ABI |
| `provider/core` | 保留经过验证的 C++ V4L2/媒体拓扑适配 |
| `provider/compat/android15` | 保留固定版本的 C++ CameraDevice、Session、buffer/fence、JPEG 和图像转换 |

Rust 和 C++ 都使用冻结的 Camera Device/Common V1 AIDL。跨语言只传递不透明配置句柄、
标量、借用字符串/字节和 `AIBinder` 强引用，不跨边界传递 C++ 容器、异常或 Rust trait。
桥接返回设备 Binder 时增加一次引用，Rust 接管并在失败或退出时释放；不注册第二个 Provider。
`binder::unstable_api::new_spibinder` 只在 `adapter.rs` 内使用，升级时需核对该内部桥接 API。
Soong 安装的 Rust `.dylib.so` 必须与该服务使用同一 SDK/工具链重建、一起交付；
Rust 动态库 ABI 不因外层使用 Stable AIDL 而获得跨编译器版本兼容性。

发现线程只操作板级白名单。Binder 回调与设备 Binder 调用均在释放注册表锁后执行，
避免 CameraService 回调重入导致锁死；更换回调后由同一线程重新发布完整快照。
并发预算仍按原生采集像素阵列计算，较小输出分辨率不会降低输入带宽记账。

没有新增现有 HAL/framework 修改、节点标签或权限放宽。

## 异常退出适配

强制退出客户端时曾复现 CameraService 等待 HAL flush，同时 HAL 输出线程等待同步
错误通知回调的循环等待，最终触发 CameraService watchdog。Rust 的通知/结果队列将
远端同步调用与采集、flush/close 的工作线程分开，单个线程保持通知和结果顺序。
队列上限 64，拥塞显式返回错误；请求图像 buffer 的调用仍使用同步返回值。
生成的 AIDL parcelable 没有 Clone，通过 Parcel 往返持有消息和 fence FD 的独立副本，
不复制图像像素。队列线程不持有 Session，也不在 Session 清理线程中 join；最后一个
发送端释放后排空退出。

另修复本仓库 C++ 兼容层中工作线程临时持有 Session 最后一个强引用的问题，避免
在输出线程上析构 Session 并 join 自己。工作线程借用父对象，父析构函数先停止并
join 全部工作线程，再释放状态；同步更新了 `changes.patch`，未编辑 SDK 原文件。
后续将 Session 本体迁为 Rust 时仍需保留这些停止、buffer/fence 和线程所有权约束。

在原目标板 Enforcing 下，Rust 资源策略 3 项、V4L2 主机测试 32/64 位各 8 项通过；
Camera2 三路采集/尺寸切换/三轮会话重开、GPU 矫正及本地化共 6 项板上测试通过。
修复后连续 3 次强制退出并重开，三路均恢复 25 fps，CameraService、独立 AHD 服务及
已有两项 Provider PID 均保持不变。这些是有限实机回归，尚未执行完整 Camera CTS/VTS，
尤其不能据此宣称全部 flush/close 回调时序、离线 JPEG 和异常 buffer 情况已经符合认证要求。
三路预览同时进行的 1080p H.264 录像完成 718 帧，全部解码通过，时间戳严格递增。

## 编译与测试

在选定产品的 AOSP 环境运行：

```sh
m AhdCameraProvider ahd_provider_policy_test ahd_capture_io_test
out/host/linux-x86/nativetest64/ahd_provider_policy_test/ahd_provider_policy_test
out/host/linux-x86/nativetest64/ahd_capture_io_test/ahd_capture_io_test
```

Android 硬件测试 `AhdProviderTest` 要求板上已集成该服务，且没有其他 App 占用 AHD。
它通过普通 Camera2 接口打开全部 `ahd-*` 摄像头，先打开完整集合再配置，
按设备能力选择最大/最小尺寸，执行三轮并发 YUV 采集与关闭重开，检查真实帧和单调时间戳。
不在没有 AHD Provider 的设备上将测试跳过并报告成功。

```sh
adb -s <serial> shell am instrument -w \
  -e class io.github.ahdcamera.AhdProviderTest \
  io.github.ahdcamera.debug.test/androidx.test.runner.AndroidJUnitRunner
```

实机验证结果保存在忽略的 `dist/validation/rust-hal/`。模块构建或临时更新 vendor 文件
不代表 `super.img`/`update.img` 已刷新。USB/MIPI 的源码不变与实机回归是两种不同证据，
目前没有相应硬件，不声明它们已经通过运行测试。
