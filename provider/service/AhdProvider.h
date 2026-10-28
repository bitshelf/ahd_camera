// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <aidl/android/hardware/camera/provider/BnCameraProvider.h>
#include <aidl/android/hardware/camera/provider/ICameraProviderCallback.h>
#include "CaptureIo.h"
#include "ExternalCameraDevice.h"
#include <condition_variable>
#include <map>
#include <mutex>
#include <thread>

namespace ahd {
using Device = android::hardware::camera::device::ahd_implementation::ExternalCameraDevice;
using Callback = aidl::android::hardware::camera::provider::ICameraProviderCallback;
class AhdProvider final : public aidl::android::hardware::camera::provider::BnCameraProvider {
 public:
    AhdProvider();
    ~AhdProvider() override;
    ndk::ScopedAStatus setCallback(const std::shared_ptr<Callback>& callback) override;
    ndk::ScopedAStatus getVendorTags(std::vector<aidl::android::hardware::camera::common::VendorTagSection>* out) override;
    ndk::ScopedAStatus getCameraIdList(std::vector<std::string>* out) override;
    ndk::ScopedAStatus getCameraDeviceInterface(const std::string& name, std::shared_ptr<aidl::android::hardware::camera::device::ICameraDevice>* out) override;
    ndk::ScopedAStatus notifyDeviceStateChange(int64_t state) override;
    ndk::ScopedAStatus getConcurrentCameraIds(std::vector<aidl::android::hardware::camera::provider::ConcurrentCameraIdCombination>* out) override;
    ndk::ScopedAStatus isConcurrentStreamCombinationSupported(const std::vector<aidl::android::hardware::camera::provider::CameraIdAndStreamCombination>& configs, bool* out) override;
 private:
    void refresh();
    capture::Config mProfile;
    android::hardware::camera::ahd_external::common::ExternalCameraConfig mLimits;
    std::mutex mLock;
    std::condition_variable mWake;
    bool mStop = false;
    std::thread mWorker;
    std::shared_ptr<Callback> mCallback;
    std::map<std::string,std::shared_ptr<Device>> mDevices;
};
}
