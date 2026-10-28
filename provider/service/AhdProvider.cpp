// SPDX-License-Identifier: Apache-2.0
#define LOG_TAG "AhdProvider"
#include "AhdProvider.h"
#include "convert.h"
#include <aidl/android/hardware/camera/common/CameraDeviceStatus.h>
#include <aidl/android/hardware/camera/common/Status.h>
#include <system/camera_metadata.h>
#include <log/log.h>
#include <algorithm>
#include <chrono>
#include <set>
#include <unistd.h>

namespace ahd {
using aidl::android::hardware::camera::common::CameraDeviceStatus;
using aidl::android::hardware::camera::common::Status;
using android::hardware::camera::device::ahd_implementation::fromStatus;
AhdProvider::AhdProvider()
    : mProfile(capture::loadConfig("/vendor/etc/ahd_camera/provider.xml")),
      mLimits(android::hardware::camera::ahd_external::common::ExternalCameraConfig::loadFromCfg()) {
    capture::setNodes(mProfile.nodes);
    mLimits.resourceCost = mProfile.resourceCost;
    ALOGI("Configured AHD nodes: %zu; no unlisted devices will be opened",mProfile.nodes.size());
    mWorker = std::thread([this] {
        std::unique_lock guard(mLock);
        while (!mStop) {
            guard.unlock(); refresh(); guard.lock();
            mWake.wait_for(guard,std::chrono::seconds(2),[this] { return mStop; });
        }
    });
}
AhdProvider::~AhdProvider() {
    { std::lock_guard guard(mLock); mStop=true; }
    mWake.notify_all();
    mWorker.join();
}
void AhdProvider::refresh() {
    for (const auto& node : mProfile.nodes) {
        const auto name = "device@" + Device::kDeviceVersion + "/ahd/" + node.id;
        // Probe only board-allowlisted nodes; no S_FMT, link setup, or STREAMON here.
        const int fd = capture::openNode(node.path);
        const bool present = fd >= 0;
        if (fd >= 0) close(fd);
        std::shared_ptr<Callback> callback;
        bool added=false,removed=false;
        {
            std::lock_guard guard(mLock);
            auto it = mDevices.find(name);
            if (present && it==mDevices.end()) {
                auto device = ndk::SharedRefBase::make<Device>(node.path,mLimits,node.id);
                if (device->isInitFailed()) continue;
                mDevices.emplace(name,device); added=true;
            } else if (!present && it!=mDevices.end()) { mDevices.erase(it); removed=true; }
            callback=mCallback;
        }
        if (callback && (added || removed)) {
            auto status = callback->cameraDeviceStatusChange(name,added ? CameraDeviceStatus::PRESENT : CameraDeviceStatus::NOT_PRESENT);
            if (!status.isOk()) ALOGW("Availability callback failed: %s",status.getDescription().c_str());
        }
    }
}
ndk::ScopedAStatus AhdProvider::setCallback(const std::shared_ptr<Callback>& callback) {
    if (!callback) return fromStatus(Status::ILLEGAL_ARGUMENT);
    std::vector<std::string> names;
    { std::lock_guard guard(mLock); mCallback=callback; for (const auto& [name,_] : mDevices) names.push_back(name); }
    for (const auto& name : names) callback->cameraDeviceStatusChange(name,CameraDeviceStatus::PRESENT);
    return fromStatus(Status::OK);
}
ndk::ScopedAStatus AhdProvider::getVendorTags(std::vector<aidl::android::hardware::camera::common::VendorTagSection>* out) {
    if (!out) return fromStatus(Status::ILLEGAL_ARGUMENT);
    out->clear(); return fromStatus(Status::OK);
}
ndk::ScopedAStatus AhdProvider::getCameraIdList(std::vector<std::string>* out) {
    if (!out) return fromStatus(Status::ILLEGAL_ARGUMENT);
    out->clear(); return fromStatus(Status::OK); // EXTERNAL-facing cameras use availability callbacks.
}
ndk::ScopedAStatus AhdProvider::getCameraDeviceInterface(const std::string& name, std::shared_ptr<aidl::android::hardware::camera::device::ICameraDevice>* out) {
    if (!out) return fromStatus(Status::ILLEGAL_ARGUMENT);
    std::lock_guard guard(mLock);
    auto it=mDevices.find(name);
    if (it==mDevices.end()) { *out=nullptr; return fromStatus(Status::ILLEGAL_ARGUMENT); }
    *out=it->second; return fromStatus(Status::OK);
}
ndk::ScopedAStatus AhdProvider::notifyDeviceStateChange(int64_t) { return fromStatus(Status::OK); }
ndk::ScopedAStatus AhdProvider::getConcurrentCameraIds(std::vector<aidl::android::hardware::camera::provider::ConcurrentCameraIdCombination>* out) {
    if (!out) return fromStatus(Status::ILLEGAL_ARGUMENT);
    out->clear();
    std::lock_guard guard(mLock);
    aidl::android::hardware::camera::provider::ConcurrentCameraIdCombination combination;
    // One board-qualified group. Never include another provider's IDs.
    for (const auto& node : mProfile.nodes) {
        const auto name="device@"+Device::kDeviceVersion+"/ahd/"+node.id;
        if (mDevices.count(name) && combination.combination.size()<mProfile.maxConcurrent)
            combination.combination.push_back(node.id);
    }
    if (combination.combination.size()>1) out->push_back(combination);
    return fromStatus(Status::OK);
}
ndk::ScopedAStatus AhdProvider::isConcurrentStreamCombinationSupported(
        const std::vector<aidl::android::hardware::camera::provider::CameraIdAndStreamCombination>& configs,bool* out) {
    if (!out) return fromStatus(Status::ILLEGAL_ARGUMENT);
    *out=false;
    if (configs.empty() || configs.size()>mProfile.maxConcurrent) return fromStatus(Status::OK);
    uint64_t total=0;
    std::set<std::string> seen;
    std::lock_guard guard(mLock);
    for (const auto& config : configs) {
        if (!seen.insert(config.cameraId).second) return fromStatus(Status::ILLEGAL_ARGUMENT);
        const auto name="device@"+Device::kDeviceVersion+"/ahd/"+config.cameraId;
        auto it=mDevices.find(name); if (it==mDevices.end()) return fromStatus(Status::OK);
        bool supported=false;
        if (!it->second->isStreamCombinationSupported(config.streamConfiguration,&supported).isOk() || !supported)
            return fromStatus(Status::OK);
        aidl::android::hardware::camera::device::CameraMetadata chars;
        if (!it->second->getCameraCharacteristics(&chars).isOk()) return fromStatus(Status::OK);
        const auto* metadata=reinterpret_cast<const camera_metadata_t*>(chars.metadata.data());
        camera_metadata_ro_entry_t rates{};
        if (find_camera_metadata_ro_entry(metadata,ANDROID_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,&rates) || !rates.count)
            return fromStatus(Status::OK);
        const int32_t fps=*std::max_element(rates.data.i32,rates.data.i32+rates.count);
        uint64_t area=0;
        for (const auto& stream : config.streamConfiguration.streams) {
            if (stream.width<=0 || stream.height<=0 || stream.width>32768 || stream.height>32768)
                return fromStatus(Status::ILLEGAL_ARGUMENT);
            area=std::max(area,static_cast<uint64_t>(stream.width)*stream.height);
        }
        if (fps<=0 || fps>1000) return fromStatus(Status::OK);
        camera_metadata_ro_entry_t pixels{};
        if (find_camera_metadata_ro_entry(metadata,ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE,&pixels) ||
            pixels.count!=2 || pixels.data.i32[0]<=0 || pixels.data.i32[1]<=0 ||
            pixels.data.i32[0]>32768 || pixels.data.i32[1]>32768) return fromStatus(Status::OK);
        area=std::max(area,static_cast<uint64_t>(pixels.data.i32[0])*pixels.data.i32[1]);
        total+=area*fps;
    }
    *out=total<=mProfile.maxPixelRate;
    return fromStatus(Status::OK);
}
}
