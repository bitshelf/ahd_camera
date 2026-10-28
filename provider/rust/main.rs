// SPDX-License-Identifier: Apache-2.0
//! Independent Rust AIDL Provider. Device/session/graphics adapter remains C++.
mod adapter;
mod device;
mod policy;

use adapter::Adapter;
use android_hardware_camera_common::aidl::android::hardware::camera::common::{
    CameraDeviceStatus::CameraDeviceStatus, Status::Status, VendorTagSection::VendorTagSection,
};
use android_hardware_camera_device::aidl::android::hardware::camera::device::ICameraDevice::ICameraDevice;
use android_hardware_camera_provider::aidl::android::hardware::camera::provider::{
    CameraIdAndStreamCombination::CameraIdAndStreamCombination,
    ConcurrentCameraIdCombination::ConcurrentCameraIdCombination,
    ICameraProvider::{BnCameraProvider, ICameraProvider},
    ICameraProviderCallback::ICameraProviderCallback,
};
use binder::{BinderFeatures, Interface, Strong};
use std::collections::BTreeMap;
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

type Device = Strong<dyn ICameraDevice>;
type Callback = Strong<dyn ICameraProviderCallback>;
#[derive(Default)]
struct State {
    stop: bool,
    callback: Option<Callback>,
    callback_epoch: u64,
    devices: BTreeMap<String, Device>,
}
struct Inner {
    adapter: Adapter,
    state: Mutex<State>,
    wake: Condvar,
}
struct Provider {
    inner: Arc<Inner>,
    worker: Mutex<Option<JoinHandle<()>>>,
}
fn name(id: &str) -> String {
    format!("device@1.1/ahd/{id}")
}
fn illegal() -> binder::Status {
    binder::Status::new_service_specific_error(Status::ILLEGAL_ARGUMENT.0, None)
}

impl Provider {
    fn new() -> Self {
        let inner = Arc::new(Inner {
            adapter: Adapter::new(),
            state: Mutex::default(),
            wake: Condvar::new(),
        });
        log::info!(
            "Rust AHD Provider: {} allowlisted nodes; C++ device/session adapter",
            inner.adapter.ids.len()
        );
        let owned = Arc::clone(&inner);
        let worker = thread::Builder::new()
            .name("ahd-discovery".into())
            .spawn(move || discover(owned))
            .expect("Start AHD discovery");
        Self {
            inner,
            worker: Mutex::new(Some(worker)),
        }
    }
}
impl Drop for Provider {
    fn drop(&mut self) {
        self.inner.state.lock().unwrap().stop = true;
        self.inner.wake.notify_all();
        if let Some(worker) = self.worker.lock().unwrap().take() {
            let _ = worker.join();
        }
    }
}
impl Interface for Provider {}

// A single worker emits both the initial snapshot and subsequent transitions.
// Binder callbacks are NEVER invoked with the state lock held: CameraService
// can synchronously re-enter the Provider from an availability callback.
fn discover(inner: Arc<Inner>) {
    let mut delivered_epoch = 0;
    let mut announced = BTreeMap::<String, ()>::new();
    loop {
        for (index, id) in inner.adapter.ids.iter().enumerate() {
            if inner.state.lock().unwrap().stop {
                return;
            }
            let key = name(id);
            let present = inner.adapter.probe(index);
            let exists = inner.state.lock().unwrap().devices.contains_key(&key);
            if present && !exists {
                if let Some(device) = inner.adapter.device(index) {
                    inner
                        .state
                        .lock()
                        .unwrap()
                        .devices
                        .insert(key, device::wrap(device));
                }
            } else if !present && exists {
                // Drop device references outside the registry lock.
                let removed = inner.state.lock().unwrap().devices.remove(&key);
                drop(removed);
            }
        }
        let (callback, epoch, names) = {
            let state = inner.state.lock().unwrap();
            (
                state.callback.clone(),
                state.callback_epoch,
                state.devices.keys().cloned().collect::<Vec<_>>(),
            )
        };
        if epoch != delivered_epoch {
            announced.clear();
            delivered_epoch = epoch;
        }
        if let Some(callback) = callback {
            let current: BTreeMap<_, ()> = names.into_iter().map(|n| (n, ())).collect();
            let events = announced
                .keys()
                .filter(|n| !current.contains_key(*n))
                .map(|n| (n.clone(), CameraDeviceStatus::NOT_PRESENT))
                .chain(
                    current
                        .keys()
                        .filter(|n| !announced.contains_key(*n))
                        .map(|n| (n.clone(), CameraDeviceStatus::PRESENT)),
                )
                .collect::<Vec<_>>();
            for (name, status) in events {
                if let Err(error) = callback.cameraDeviceStatusChange(&name, status) {
                    log::warn!("Availability callback: {error:?}");
                    continue;
                }
                if status == CameraDeviceStatus::PRESENT {
                    announced.insert(name, ());
                } else {
                    announced.remove(&name);
                }
            }
        }
        let state = inner.state.lock().unwrap();
        if state.stop {
            return;
        }
        // If setCallback arrived during probing/callback delivery, don't lose
        // its wake-up: immediately publish the new callback's full snapshot.
        if state.callback_epoch != delivered_epoch {
            continue;
        }
        drop(
            inner
                .wake
                .wait_timeout(state, Duration::from_secs(2))
                .unwrap(),
        );
    }
}

impl ICameraProvider for Provider {
    fn setCallback(&self, callback: &Callback) -> binder::Result<()> {
        let mut state = self.inner.state.lock().unwrap();
        state.callback = Some(callback.clone());
        state.callback_epoch = state.callback_epoch.wrapping_add(1);
        self.inner.wake.notify_all();
        Ok(())
    }
    fn getVendorTags(&self) -> binder::Result<Vec<VendorTagSection>> {
        Ok(Vec::new())
    }
    fn getCameraIdList(&self) -> binder::Result<Vec<String>> {
        Ok(Vec::new())
    }
    fn getCameraDeviceInterface(&self, camera_device_name: &str) -> binder::Result<Device> {
        self.inner
            .state
            .lock()
            .unwrap()
            .devices
            .get(camera_device_name)
            .cloned()
            .ok_or_else(illegal)
    }
    fn notifyDeviceStateChange(&self, _: i64) -> binder::Result<()> {
        Ok(())
    }
    fn getConcurrentCameraIds(&self) -> binder::Result<Vec<ConcurrentCameraIdCombination>> {
        let state = self.inner.state.lock().unwrap();
        let combination = self
            .inner
            .adapter
            .ids
            .iter()
            .filter(|id| state.devices.contains_key(&name(id)))
            .take(self.inner.adapter.max_concurrent)
            .cloned()
            .collect::<Vec<_>>();
        Ok(if combination.len() > 1 {
            vec![ConcurrentCameraIdCombination { combination }]
        } else {
            vec![]
        })
    }
    fn isConcurrentStreamCombinationSupported(
        &self,
        configs: &[CameraIdAndStreamCombination],
    ) -> binder::Result<bool> {
        if configs.is_empty() || configs.len() > self.inner.adapter.max_concurrent {
            return Ok(false);
        }
        policy::unique_ids(configs.iter().map(|c| c.cameraId.as_str())).map_err(|_| illegal())?;
        // Clone handles; potentially blocking device Binder calls happen outside the registry lock.
        let devices = self.inner.state.lock().unwrap().devices.clone();
        let mut total = 0;
        for config in configs {
            let Some(device) = devices.get(&name(&config.cameraId)) else {
                return Ok(false);
            };
            if !device
                .isStreamCombinationSupported(&config.streamConfiguration)
                .unwrap_or(false)
            {
                return Ok(false);
            }
            let Ok(metadata) = device.getCameraCharacteristics() else {
                return Ok(false);
            };
            let Some((pixels, fps)) = adapter::native_budget(&metadata.metadata) else {
                return Ok(false);
            };
            let mut area = 0;
            for stream in &config.streamConfiguration.streams {
                area = area
                    .max(policy::stream_area(stream.width, stream.height).map_err(|_| illegal())?);
            }
            let Some(load) = policy::add_load(total, pixels, area, fps) else {
                return Ok(false);
            };
            total = load;
        }
        Ok(total <= self.inner.adapter.max_pixel_rate)
    }
}

fn main() {
    logger::init(logger::Config::default().with_tag_on_device("AhdProviderRust"));
    binder::ProcessState::set_thread_pool_max_thread_count(6);
    binder::ProcessState::start_thread_pool();
    let provider = BnCameraProvider::new_binder(Provider::new(), BinderFeatures::default());
    binder::add_service(
        "android.hardware.camera.provider.ICameraProvider/ahd/0",
        provider.as_binder(),
    )
    .expect("Register independent Rust AHD Provider");
    binder::ProcessState::join_thread_pool();
}
