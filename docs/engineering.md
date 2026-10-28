# Kotlin Camera2 管线

`Backend` 连接 Camera2 能力枚举与 `KotlinCamera2Engine`。`CameraCatalog` 逐路读取系统公开的
Surface 输出尺寸、AE 帧率范围、最小帧间隔和并发组合。单个设备查询失败不会隐藏其他设备。
`StreamPolicy` 是可独立测试的能力与并发规则；没有硬编码的节点、分辨率或帧率列表。

`WallViewModel` 持有用户设置、选中摄像头和有效的预览 Surface，控制前后台启停。
Camera2 AvailabilityCallback 驱动能力列表刷新，合并开关相机时的密集通知；Provider 晚启动、
热插拔和服务重启后可以重新发现设备，无需给 App 增加私有服务接口。
`KotlinCamera2Engine` 在同一串行执行器内管理设备、会话、配置及回调，使用 generation
隔离旧异步回调；打开并发组全部设备后才创建会话。配置变化进行短暂合并，后台立即停采。

录像通过第二个 Camera2 输出 Surface 直接进入硬件 H.264 MediaCodec，MediaMuxer 写 MP4。
录像器运行在独立排空线程，停止生产者后发送 EOS，再完成文件或删除无有效帧的文件。
MediaStore pending 状态避免将未完成文件展示为已保存录像。编码能力和相机会话输出组合
由系统校验，不能仅凭预览成功推断录像必然支持。

界面通过 `WallEngine`/`PreviewTarget`/`TileStatus` 与采集解耦，不持有设备句柄。
每秒统计实际 capture completed 回调速率；该值不会用于伪造相机能力或改写用户选择。
诊断只写有界元数据，不保存画面或自动上传。导出由用户选择路径。

验证分为构建、自动化测试与实机验收：单元测试覆盖并发组和尺寸/帧率过滤；lint、APK、
测试 APK 与 release bundle 检查构建链。设备验收需另查实际摄像头列表、首帧、切换尺寸、
帧率、前后台恢复、录制 MP4 及隐私开关。未连接指定目标板时不能声明实机通过。
