# AHD Camera · Kotlin Camera2

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

- [工程结构与验证](docs/engineering.md)
- [原型与 App 对应关系](docs/ui-prototype.md)
- [参考项目](docs/reference-projects.md)
