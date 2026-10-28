// SPDX-License-Identifier: Apache-2.0
//! Rust device boundary and ordered callback delivery independent of session workers.
use crate::adapter::copy_parcel;
use android_hardware_camera_common::aidl::android::hardware::camera::common::CameraResourceCost::CameraResourceCost;
use android_hardware_camera_device::aidl::android::hardware::camera::device::{
    BufferRequest::BufferRequest,
    BufferRequestStatus::BufferRequestStatus,
    CameraMetadata::CameraMetadata,
    CaptureResult::CaptureResult,
    ICameraDevice::{BnCameraDevice, ICameraDevice},
    ICameraDeviceCallback::{BnCameraDeviceCallback, ICameraDeviceCallback},
    ICameraDeviceSession::ICameraDeviceSession,
    ICameraInjectionSession::ICameraInjectionSession,
    NotifyMsg::NotifyMsg,
    StreamBuffer::StreamBuffer,
    StreamBufferRet::StreamBufferRet,
    StreamConfiguration::StreamConfiguration,
};
use binder::{BinderFeatures, Interface, Strong};
use std::sync::mpsc::{sync_channel, SyncSender};
use std::thread;

type Callback = Strong<dyn ICameraDeviceCallback>;
enum Event {
    Notify(Vec<NotifyMsg>),
    Result(Vec<CaptureResult>),
    Return(Vec<StreamBuffer>),
}

struct CallbackRouter {
    remote: Callback,
    queue: SyncSender<Event>,
}
impl Interface for CallbackRouter {}
impl CallbackRouter {
    fn new(remote: &Callback) -> binder::Result<Strong<dyn ICameraDeviceCallback>> {
        let (queue, events) = sync_channel(64);
        let callback = remote.clone();
        // This worker owns messages/fences and the framework callback, NEVER a
        // device/session or the router. Dropping the last sender drains and exits.
        // close/flush must not join this worker: the framework may be waiting in
        // that very transaction with all its Binder threads occupied by disconnect.
        thread::Builder::new()
            .name("ahd-callback".into())
            .spawn(move || {
                while let Ok(event) = events.recv() {
                    let status = match event {
                        Event::Notify(msgs) => callback.notify(&msgs),
                        Event::Result(results) => callback.processCaptureResult(&results),
                        Event::Return(buffers) => callback.returnStreamBuffers(&buffers),
                    };
                    if let Err(error) = status {
                        log::warn!("Camera callback delivery failed: {error:?}");
                    }
                }
            })
            .map_err(|_| binder::Status::from(binder::StatusCode::NO_MEMORY))?;
        Ok(BnCameraDeviceCallback::new_binder(
            Self {
                remote: remote.clone(),
                queue,
            },
            BinderFeatures::default(),
        ))
    }
    fn send(&self, event: Event) -> binder::Result<()> {
        self.queue.try_send(event).map_err(|error| {
            log::error!("Camera callback queue unavailable: {error}");
            binder::StatusCode::WOULD_BLOCK.into()
        })
    }
}
impl ICameraDeviceCallback for CallbackRouter {
    fn notify(&self, msgs: &[NotifyMsg]) -> binder::Result<()> {
        self.send(Event::Notify(copy_parcel(msgs)?))
    }
    fn processCaptureResult(&self, results: &[CaptureResult]) -> binder::Result<()> {
        self.send(Event::Result(copy_parcel(results)?))
    }
    fn requestStreamBuffers(
        &self,
        requests: &[BufferRequest],
        buffers: &mut Vec<StreamBufferRet>,
    ) -> binder::Result<BufferRequestStatus> {
        self.remote.requestStreamBuffers(requests, buffers)
    }
    fn returnStreamBuffers(&self, buffers: &[StreamBuffer]) -> binder::Result<()> {
        self.send(Event::Return(copy_parcel(buffers)?))
    }
}

struct Device {
    backend: Strong<dyn ICameraDevice>,
}
impl Interface for Device {}
pub fn wrap(backend: Strong<dyn ICameraDevice>) -> Strong<dyn ICameraDevice> {
    BnCameraDevice::new_binder(Device { backend }, BinderFeatures::default())
}
impl ICameraDevice for Device {
    fn getCameraCharacteristics(&self) -> binder::Result<CameraMetadata> {
        self.backend.getCameraCharacteristics()
    }
    fn getPhysicalCameraCharacteristics(&self, id: &str) -> binder::Result<CameraMetadata> {
        self.backend.getPhysicalCameraCharacteristics(id)
    }
    fn getResourceCost(&self) -> binder::Result<CameraResourceCost> {
        self.backend.getResourceCost()
    }
    fn isStreamCombinationSupported(&self, streams: &StreamConfiguration) -> binder::Result<bool> {
        self.backend.isStreamCombinationSupported(streams)
    }
    fn open(&self, callback: &Callback) -> binder::Result<Strong<dyn ICameraDeviceSession>> {
        self.backend.open(&CallbackRouter::new(callback)?)
    }
    fn openInjectionSession(
        &self,
        callback: &Callback,
    ) -> binder::Result<Strong<dyn ICameraInjectionSession>> {
        self.backend.openInjectionSession(callback)
    }
    fn setTorchMode(&self, on: bool) -> binder::Result<()> {
        self.backend.setTorchMode(on)
    }
    fn turnOnTorchWithStrengthLevel(&self, strength: i32) -> binder::Result<()> {
        self.backend.turnOnTorchWithStrengthLevel(strength)
    }
    fn getTorchStrengthLevel(&self) -> binder::Result<i32> {
        self.backend.getTorchStrengthLevel()
    }
}
