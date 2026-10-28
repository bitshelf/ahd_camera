# AVS 与鱼眼校正资料核对

核对日期：2026-10-28。当前实验分支为 `kotlin-camera2-dewarp`，基于 `kotlin-camera2`。
本记录是资料与 SDK 能力审阅。实验实现与验收结果另见 README，不能将通用 GPU 近似矫正称为厂商 AVS 库已适配。

## 已读资料

通过 IMA skill 从「Embedded Linux」知识库读取原始 PDF；不将原文、下载凭证或知识库内部 ID 提交到仓库。

| 文件 | 版本/日期 | 适用范围 |
|---|---|---|
| Rockchip_Developer_Guide_RK3588_AVS_CN_V0.2.5.pdf | V0.2.5，2022-09-28 | RK3588 全景拼接 |
| Rockchip_Guide_AVS_Calib_Model_CN.pdf | V1.1.3，2023-11-29 | RV1106/RV1126 双目模型标定 |
| Rockchip_Guide_AVS_Calib_Product_CN.pdf | V1.1.4，2023-12-08 | RV1106/RV1126 双目产线标定 |
| Rockchip_Development_Guide_FEC_CN.pdf | V0.6.0，2025-06-07 | RK3588/Linux 5.10、RV1126B/Linux 6.1；支持表列 RK3588 Android 可用 |

## 文档结论

AVS 指南第 1.3 节（PDF 第 5 页）把几何映射、融合和亮度/色彩处理区分开；
RGA/GPU 缩略图缩放在 AVS 输出之后。第 4.3.3、4.5 节和第 5.5 节说明，
校准结果用于生成 GPU 使用的 mesh/LUT，融合另外使用 alpha 权重。
因此 AVS 的这条几何校正路线依赖 GPU 映射，而不是 RGA 非线性重映射接口。

要将鱼眼弯曲的直线拉直，应使用直线投影（第 2.2.1 节，PDF 第 7 页）。
文档建议控制输出视场角以减少边缘透视拉伸；直线投影不能在保留完整 180 度视野的同时保持有限输出尺寸。
不能用裁切或缩放冒充畸变校正，也不能保证所有边缘物体比例与中心一致。

第 5.5 节区分中间 LUT 和最终 LUT：标定、mask、微调等变化会影响中间 LUT；
输出分辨率、投影方式、视场角、中心和姿态角等变化会影响最终 LUT。
缓存映射表时必须把这些参数和输入图像的有效窗口纳入版本/缓存键。

AVS 指南第 5.3.1 节存在旧版本限制：枚举虽然列出 fisheye，正文仍说明该模型当时不支持，
单目/双目标定 API 也未开放。不能仅凭枚举推断当前库可调用，不能将 CMei、pinhole 与 OpenCV fisheye 的参数互换。
AVS 多目拼接还涉及重叠视场、外参、同步和融合；本 App 的当前需求是每路独立校正，
不应因此引入不需要的多目融合或声明已有三路摄像头满足拼接条件。

2025 年的 FEC 指南另描述专用 FEC 硬件及 libispfec，并明确列出 RK3588 Android 支持。
该表未列 RK3576；既不能据此否定所有平台的专用 FEC，也不能据此认定 RK3576 可直接使用。

## 当前 Android SDK 的证据

- `hardware/rockchip/librga` 的 im2d API 包含缩放、旋转、翻转、格式转换等，没有公开的非线性 mesh remap 接口。
- `external/camera_engine_rkaiq/include/common/panorama_stitchingApp.h` 包含直线投影及 GPU/FEC 输出类型和部分 mesh API 声明；头文件存在不代表具备对应 Android 运行库。
- `vendor/rockchip/common/fec` 有旧版 `libdistortion_gl`，其产品配置仅为 rk356x 安装，名称和调用代码表明这是 GPU 路线；不能按名称当作 RK3576 专用 FEC 支持。
- 当前内核配置未启用 `VIDEO_ROCKCHIP_FEC` 和 `VIDEO_ROCKCHIP_ISPP_FEC`；独立 FEC 驱动匹配表只列 RV1126B。单独打开配置项不能证明 RK3576 存在对应硬件。
- 已检查的 Android vendor/hardware 目录中没有找到可直接调用文档所述 `RKALGO_AVS_*` 接口的完整库/头文件组合；尚未验证厂商 AVS 二进制对 RK3576/Bionic/当前 buffer mapper 的兼容性。

## 实现边界

优先验证每路独立的“标定参数 → 输出到输入映射 → GPU 直线投影”路线，
预览和录像共用校正后的画面，避免屏幕已校正但 MP4 仍弯曲。
保持 Kotlin App 与已有 HAL/framework 解耦。通用几何参数、映射格式与渲染器分离；
未来厂商 AVS/FEC 后端需独立适配，不让平台库依赖扩散到 UI。
只有实际测量证明 RGA 转换能减少开销时才增加该环节，避免额外拷贝和同步。

接入前仍需取得每路镜头的模型、标定分辨率、有效窗口、内参、畸变参数或有格式说明的映射表。
缺失标定时的近似模式必须明确标注，不能当作准确标定结果；厂商 mesh 二进制格式未知时不能猜测解析。

验证需覆盖关闭校正时原有三路预览/录像、直线测试图、输入窗口/分辨率变化、预览录像一致性、
生命周期、GPU 帧时间/丢帧和内存占用。实际通过的项目以 README 和本地验证产物为准，未覆盖项不能据此推断通过。
