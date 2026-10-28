// SPDX-License-Identifier: Apache-2.0
#include "AhdProvider.h"
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <log/log.h>
int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(6);
    auto provider=ndk::SharedRefBase::make<ahd::AhdProvider>();
    const std::string name=std::string(ahd::AhdProvider::descriptor)+"/ahd/0";
    LOG_ALWAYS_FATAL_IF(AServiceManager_addService(provider->asBinder().get(),name.c_str())!=STATUS_OK,
                        "Cannot register independent AHD provider");
    ABinderProcess_joinThreadPool();
    return 1;
}
