# AHD Camera · Kotlin Camera2

## AOSP 系统集成

```make
PRODUCT_PACKAGES += AhdCamera
```

然后在已选择产品的 AOSP 环境编译：

```sh
m AhdCamera
# 独立包名，适合与 release 并存调试
m AhdCameraDebug
```

### AHD Provider 集成

**1. 编辑产品 `.mk` 文件**

找到产品 `AndroidProducts.mk` 中 `PRODUCT_MAKEFILES` 指向的文件，
例如 `device/example/board/board.mk`，添加：

```make
PRODUCT_PACKAGES += AhdCameraProvider
```

移植硬件时，在仓库内核对并调整该 XML 的采集节点、driver/card/bus、media 节点、
解码器名称和 pad、并发数量及像素吞吐预算；当前映射不代表所有板子都使用相同节点。

**2. 编辑产品的 `BoardConfig.mk`**

例如 `device/example/board/BoardConfig.mk`，添加：

```make
BOARD_VENDOR_SEPOLICY_DIRS += external/ahd_camera/provider/integration/sepolicy
```

## 更多文档

- [Rust HAL 迁移边界与测试](docs/rust-hal.md)

- [AVS 与鱼眼校正资料核对](docs/dewarp-avs-review.md)

- [工程结构与验证](docs/engineering.md)
- [原型与 App 对应关系](docs/ui-prototype.md)
- [参考项目](docs/reference-projects.md)
