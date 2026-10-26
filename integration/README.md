# Android 系统应用集成

源码放在 Android SDK 的 `external/ahd_camera`，直接使用仓库内的构建配置和策略。

## 两个构建入口

产品 makefile 添加模块名（不是 Android 包名）：

```makefile
PRODUCT_PACKAGES += AhdCamera
```

板级 `BoardConfig.mk` 添加策略搜索目录：

```makefile
BOARD_VENDOR_SEPOLICY_DIRS += external/ahd_camera/integration/sepolicy
```

根目录 `Android.bp` 中，AhdCamera 使用平台签名，安装到 `system_ext/priv-app`。
`required: ["ahd-capture-permissions.xml"]` 自动带入权限组 XML，产品不必再次添加它。
XML 安装到 `system_ext/etc/permissions/ahd-capture-permissions.xml`。
SELinux 策略通过 BoardConfig 参与 vendor policy 编译；源码放在 external 不改变策略分区。
`required` 本身不能自动注册 SELinux 策略目录，两个入口分别负责安装模块与编译策略。

## 板级节点映射

实际 AHD 节点仍由板级 `sepolicy/file_contexts` 维护，板级策略目录照常启用。
按目标板的实际拓扑，仅为已确认的 AHD 采集节点设置专用标签。例如：

```text
/dev/video<N>    u:object_r:vendor_ahd_video_device:s0
```

`<N>` 是说明用占位符，需替换为目标板实际节点编号。
不要将所有视频节点统一标记为 AHD，以免把 USB、MIPI 或其他处理节点纳入直采权限。
