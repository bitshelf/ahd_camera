# Android V4L2 管线与验收

Kotlin/Compose 管理展示与用户选项。`WallEngine` 定义控制契约，`RustV4l2Engine` 在串行
executor 上管理 Surface、native handle 和录像器。JNI 只传控制数据与 Surface，不传像素。

`ahd-core` 负责帧布局校验、时间戳时钟域和有界最新帧通道；`ahd-v4l2` 拥有设备 fd、
导出 DMA-BUF 与 frame lease。丢弃 lease 后才重新 QBUF；退出停止 STREAM，再关闭 fd。
`ahd-android` 负责 Android 窗口、线程、AHardwareBuffer 和 EGL；RGA ABI 在独立模块，
后续替换 SoC 处理实现不应改 Kotlin 或 V4L2 fd 的所有权。

RGA 同步完成后才能归还采集 buffer。渲染消费者通过 Arc 保留硬件帧，GPU 完成读取后
释放；写入端仅复用没有消费者的池元素。录像呈现和编码 drain 不阻塞 Kotlin 主线程。
后台或 Surface 销毁停止生产者，再收尾录像，错误时用黑色遮层隐藏最后一帧。

读能力查询不执行 S_FMT/STREAMON。驱动返回尺寸范围时，本阶段展示范围端点和当前尺寸，
不生成常用尺寸预设。每种尺寸单独查询整数帧率，缺失或非整数能力不伪造选项。实际帧率
由完成采集/渲染后的计数测量，不能把驱动报告或请求帧率当成稳定实测值。

不调用 Camera2，不携带 Debian/OpenHarmony 实现，不修改既有 HAL/framework。设备域、
策略与签名权限组 XML 由本仓库维护，板级保留节点标签，见 integration/README.md。App 保留 app_domain 与
每应用 MLS 类别；只将专用 AHD 硬件对象标记为 mlstrustedobject，不给 App 全局 MLS 绕过权限。
直接 V4L2 不自动享有 CameraService 的设备仲裁、隐私开关与指示管理；这些属于后续验收项。

验收必须分别记录：单路和三路真实画面、native DMA-BUF/RGA/GLES 结果、App 实际进程域、
权限失败、生命周期、录像文件与热插拔。三路及 16+ 路都不能只用回调、布局或单测代替。
端到端延时需物理测量，帧时间戳到回调年龄不等于镜头到屏幕延时。

RGA 源 DMA-BUF 与目标 AHB 都导入为 handle，不混用 fd/handle 模式。输入 handle 按
采集 buffer 缓存，RGA 同步完成后、采集 fd 关闭前释放。目标 AHB 申请标准 CPU_READ_RARELY 用途，
使本 BSP gralloc 分配线性 RGBA，避免把 raster 写入 AFBC 布局；不实际映射 CPU 或拷贝像素。


显示组件 `CameraTile`、`WallPopover` 和 `WallTheme` 不负责节点权限与采集生命周期，
HTML 原型仅定义展示与交互。显示状态持久化、语言、外观与流配置分开管理。
同一全局浮层切换显示/摄像头/应用设置/关于，普通设置不弹中央对话框。

连续帧间隔范围按有界的整数集合展开，离散能力仍由设备查询。`G_PARM/S_PARM`
确认设备能否改帧率；不能设置时，不把 ioctl 失败当作设置成功。`InputRate` 在节流前用
V4L2 时间戳与 sequence 差值估计源节奏，跳过的 dequeue 帧不被误计为低输入帧率。
固定输入的可选上限经稳定整数估计修正，UI 校准前显示“读取中”。`FramePacer` 在 RGA 前
释放不需要的帧，以最新帧实现较低输出节奏；它不能提高源帧率，也不改变传感器时序。
预览角标只保留一个实测 FPS，参数面板只保留一个帧率选择，不增加目标/实际双行。

通用 SELinux 域和签名权限组 XML 维护在 external/ahd_camera/integration。
产品通过 PRODUCT_PACKAGES 添加 AhdCamera，BoardConfig 通过 BOARD_VENDOR_SEPOLICY_DIRS
引入仓库内 sepolicy；权限 XML 由 App 的 required 依赖自动安装，无需配置复制和 make include。
板级 file_contexts 保留实际 AHD 节点映射，不推测 RK3588 的节点序号。
