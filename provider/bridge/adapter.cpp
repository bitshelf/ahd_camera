// SPDX-License-Identifier: Apache-2.0
#include "adapter.h"
#include "CaptureIo.h"
#include "ExternalCameraDevice.h"
#include <android/binder_ibinder.h>
#include <system/camera_metadata.h>
#include <algorithm>
#include <unistd.h>

namespace {
using Device = android::hardware::camera::device::ahd_implementation::ExternalCameraDevice;
struct Adapter {
    ahd::capture::Config profile = ahd::capture::loadConfig("/vendor/etc/ahd_camera/provider.xml");
    android::hardware::camera::ahd_external::common::ExternalCameraConfig limits =
        android::hardware::camera::ahd_external::common::ExternalCameraConfig::loadFromCfg();
    Adapter() {
        ahd::capture::setNodes(profile.nodes);
        limits.resourceCost = profile.resourceCost;
    }
};
const Adapter& get(const void* adapter) { return *static_cast<const Adapter*>(adapter); }
}
extern "C" {
void* ahd_adapter_create() { return new Adapter; }
void ahd_adapter_destroy(void* adapter) { delete static_cast<Adapter*>(adapter); }
size_t ahd_adapter_count(const void* adapter) { return get(adapter).profile.nodes.size(); }
const char* ahd_adapter_id(const void* adapter, size_t index) {
    const auto& nodes = get(adapter).profile.nodes;
    return index < nodes.size() ? nodes[index].id.c_str() : nullptr;
}
uint32_t ahd_adapter_max_concurrent(const void* adapter) { return get(adapter).profile.maxConcurrent; }
uint64_t ahd_adapter_max_pixel_rate(const void* adapter) { return get(adapter).profile.maxPixelRate; }
bool ahd_adapter_probe(const void* adapter, size_t index) {
    const auto& nodes = get(adapter).profile.nodes;
    if (index >= nodes.size()) return false;
    const int fd = ahd::capture::openNode(nodes[index].path);
    if (fd < 0) return false;
    close(fd);
    return true;
}
void* ahd_adapter_device(const void* adapter, size_t index) {
    const auto& a = get(adapter);
    if (index >= a.profile.nodes.size()) return nullptr;
    const auto& node = a.profile.nodes[index];
    auto device = ndk::SharedRefBase::make<Device>(node.path, a.limits, node.id);
    if (device->isInitFailed()) return nullptr;
    auto binder = device->asBinder();
    AIBinder_incStrong(binder.get());
    return binder.get();
}
bool ahd_metadata_budget(const uint8_t* bytes, size_t size, uint64_t* pixels, uint32_t* fps) {
    if (!bytes || !pixels || !fps || size < calculate_camera_metadata_size(0, 0)) return false;
    const auto* metadata = reinterpret_cast<const camera_metadata_t*>(bytes);
    if (validate_camera_metadata_structure(metadata, &size) != 0) return false;
    camera_metadata_ro_entry_t rates{}, array{};
    if (find_camera_metadata_ro_entry(metadata, ANDROID_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, &rates) ||
        rates.type != TYPE_INT32 || !rates.count || rates.count % 2 ||
        find_camera_metadata_ro_entry(metadata, ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE, &array) ||
        array.type != TYPE_INT32 || array.count != 2) return false;
    const auto maxFps = *std::max_element(rates.data.i32, rates.data.i32 + rates.count);
    if (maxFps <= 0 || maxFps > 1000 || array.data.i32[0] <= 0 || array.data.i32[1] <= 0 ||
        array.data.i32[0] > 32768 || array.data.i32[1] > 32768) return false;
    *pixels = static_cast<uint64_t>(array.data.i32[0]) * array.data.i32[1];
    *fps = maxFps;
    return true;
}
}
