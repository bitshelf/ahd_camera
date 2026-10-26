//! Unsafe is confined to platform FFI. Frames remain hardware buffers throughout.
#![allow(non_upper_case_globals)]
use ahd_core::latest::LatestFrame;
use ahd_v4l2::Capture;
use jni::{
    objects::{JClass, JObject, JString},
    sys::{jlong, jstring},
    JNIEnv,
};
use std::{
    collections::HashMap,
    ffi::CString,
    ptr,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
    thread,
    time::{Duration, Instant},
};

#[allow(
    non_upper_case_globals,
    non_camel_case_types,
    non_snake_case,
    dead_code,
    clippy::all,
    clippy::undocumented_unsafe_blocks
)]
mod g {
    #[cfg(not(soong))]
    include!(concat!(env!("OUT_DIR"), "/graphics.rs"));
    #[cfg(soong)]
    include!("bindings_android.rs");
}
use g::*;
mod rga;
use rga::{HardwareFrame, Rga};
type Result<T> = std::result::Result<T, String>;
fn check(ok: bool, message: &str) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err(message.into())
    }
}

struct Window(*mut ANativeWindow);
// SAFETY: NDK reference-counted windows may be handed to a single rendering thread.
unsafe impl Send for Window {}
impl Drop for Window {
    fn drop(&mut self) {
        // SAFETY: Window owns exactly one acquired ANativeWindow reference.
        unsafe {
            ANativeWindow_release(self.0);
        }
    }
}

struct TraceSection;
impl TraceSection {
    fn begin(name: &std::ffi::CStr) -> Self {
        // SAFETY: ATrace copies a valid NUL-terminated name during this call.
        unsafe {
            ATrace_beginSection(name.as_ptr());
        }
        Self
    }
}
impl Drop for TraceSection {
    fn drop(&mut self) {
        // SAFETY: This scope is paired with begin on the same thread.
        unsafe {
            ATrace_endSection();
        }
    }
}

type GetClient = unsafe extern "C" fn(*const AHardwareBuffer) -> EGLClientBuffer;
type CreateImage = unsafe extern "C" fn(
    EGLDisplay,
    EGLContext,
    EGLenum,
    EGLClientBuffer,
    *const EGLint,
) -> EGLImageKHR;
type DestroyImage = unsafe extern "C" fn(EGLDisplay, EGLImageKHR) -> EGLBoolean;
type TargetImage = unsafe extern "C" fn(GLenum, GLeglImageOES);
type Presentation = unsafe extern "C" fn(EGLDisplay, EGLSurface, i64) -> EGLBoolean;

struct Renderer {
    display: EGLDisplay,
    surface: EGLSurface,
    context: EGLContext,
    program: GLuint,
    images: HashMap<usize, (EGLImageKHR, GLuint)>,
    get_client: GetClient,
    create_image: CreateImage,
    destroy_image: DestroyImage,
    target_image: TargetImage,
    presentation: Option<Presentation>,
    _window: Window,
}
impl Renderer {
    fn new(window: Window, encoder: bool) -> Result<Self> {
        // SAFETY: This thread owns the window reference and all EGL objects created here;
        // handles are checked before use and partial construction releases owned objects.
        unsafe {
            unsafe fn extension<T: Copy>(name: &str) -> Result<T> {
                let name = CString::new(name).unwrap();
                // SAFETY: Callers choose the exact NDK extension signature and use only
                // non-null pointers; extension functions use the platform C ABI.
                unsafe {
                    let f = eglGetProcAddress(name.as_ptr()).ok_or_else(|| {
                        format!("missing EGL extension {}", name.to_string_lossy())
                    })?;
                    Ok(std::mem::transmute_copy(&f))
                }
            }
            let get_client = extension("eglGetNativeClientBufferANDROID")?;
            let create_image = extension("eglCreateImageKHR")?;
            let destroy_image = extension("eglDestroyImageKHR")?;
            let target_image = extension("glEGLImageTargetTexture2DOES")?;
            let presentation = extension("eglPresentationTimeANDROID").ok();
            check(
                !encoder || presentation.is_some(),
                "encoder requires EGL presentation timestamp extension",
            )?;
            let display = eglGetDisplay(ptr::null_mut());
            check(
                !display.is_null() && eglInitialize(display, ptr::null_mut(), ptr::null_mut()) != 0,
                "EGL initialization failed",
            )?;
            // Never call eglTerminate on this process-wide display: other camera renderers share it.
            let attrs = [
                EGL_SURFACE_TYPE as i32,
                EGL_WINDOW_BIT as i32,
                EGL_RENDERABLE_TYPE as i32,
                EGL_OPENGL_ES2_BIT as i32,
                EGL_RED_SIZE as i32,
                8,
                EGL_GREEN_SIZE as i32,
                8,
                EGL_BLUE_SIZE as i32,
                8,
                0x3142,
                if encoder { 1 } else { 0 },
                EGL_NONE as i32,
            ];
            let mut config = ptr::null_mut();
            let mut count = 0;
            check(
                eglChooseConfig(display, attrs.as_ptr(), &mut config, 1, &mut count) != 0
                    && count > 0,
                "no compatible EGL configuration",
            )?;
            let context = eglCreateContext(
                display,
                config,
                ptr::null_mut(),
                [EGL_CONTEXT_CLIENT_VERSION as i32, 2, EGL_NONE as i32].as_ptr(),
            );
            check(!context.is_null(), "EGL context creation failed")?;
            let surface = eglCreateWindowSurface(display, config, window.0.cast(), ptr::null());
            if surface.is_null() {
                eglDestroyContext(display, context);
                return Err("EGL Surface creation failed".into());
            }
            if eglMakeCurrent(display, surface, surface, context) == 0 {
                eglDestroySurface(display, surface);
                eglDestroyContext(display, context);
                return Err("EGL make-current failed".into());
            }
            eglSwapInterval(display, 0); // Request no extra application pacing; compositor may still pace.
                                         // Construct RAII owner before fallible shader/extension work.
            let mut renderer = Self {
                display,
                surface,
                context,
                program: 0,
                images: HashMap::new(),
                get_client,
                create_image,
                destroy_image,
                target_image,
                presentation,
                _window: window,
            };
            let vs = shader(GL_VERTEX_SHADER, "attribute vec2 position; varying vec2 uv; void main(){uv=vec2((position.x+1.0)*0.5,(1.0-position.y)*0.5);gl_Position=vec4(position,0.0,1.0);}")?;
            let fs = match shader(GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 uv; uniform sampler2D frame; void main(){gl_FragColor=texture2D(frame,uv);}") {
                Ok(s) => s, Err(e) => { glDeleteShader(vs); return Err(e); }
            };
            renderer.program = glCreateProgram();
            glAttachShader(renderer.program, vs);
            glAttachShader(renderer.program, fs);
            glBindAttribLocation(renderer.program, 0, c"position".as_ptr());
            glLinkProgram(renderer.program);
            glDeleteShader(vs);
            glDeleteShader(fs);
            let mut linked = 0;
            glGetProgramiv(renderer.program, GL_LINK_STATUS, &mut linked);
            check(linked != 0, "GLES program link failed")?;
            Ok(renderer)
        }
    }
    fn render(&mut self, frame: &HardwareFrame, timestamp: i64) -> Result<()> {
        let _trace = TraceSection::begin(c"ahd.egl.present");
        // SAFETY: The EGL context is current on its owning thread. frame retains the
        // AHB allocation; pool writers cannot reuse it until this GPU work completes.
        unsafe {
            let key = frame.buffer as usize;
            if !self.images.contains_key(&key) {
                check(self.images.len() < 3, "hardware frame pool limit exceeded")?;
                let client = (self.get_client)(frame.buffer);
                let image = (self.create_image)(
                    self.display,
                    ptr::null_mut(),
                    0x3140,
                    client,
                    [
                        EGL_IMAGE_PRESERVED_KHR as i32,
                        EGL_TRUE as i32,
                        EGL_NONE as i32,
                    ]
                    .as_ptr(),
                );
                check(!image.is_null(), "EGLImage import failed")?;
                let mut texture = 0;
                glGenTextures(1, &mut texture);
                glBindTexture(GL_TEXTURE_2D, texture);
                (self.target_image)(GL_TEXTURE_2D, image);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR as i32);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR as i32);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE as i32);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE as i32);
                self.images.insert(key, (image, texture));
            }
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, self.images[&key].1);
            let mut width = 0;
            let mut height = 0;
            eglQuerySurface(self.display, self.surface, EGL_WIDTH as i32, &mut width);
            eglQuerySurface(self.display, self.surface, EGL_HEIGHT as i32, &mut height);
            glViewport(0, 0, width, height);
            glClearColor(0.0, 0.0, 0.0, 1.0);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(self.program);
            let vertices: [f32; 8] = [-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, 1.0];
            glVertexAttribPointer(0, 2, GL_FLOAT, 0, 0, vertices.as_ptr().cast());
            glEnableVertexAttribArray(0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            // Complete reads before returning the buffer to the pool/RGA writer.
            glFinish();
            let gl_error = glGetError();
            check(gl_error == GL_NO_ERROR, &format!("GLES error {gl_error}"))?;
            if let Some(present) = self.presentation {
                present(self.display, self.surface, timestamp);
            }
            check(
                eglSwapBuffers(self.display, self.surface) != 0,
                "Surface submission failed",
            )
        }
    }
}
unsafe fn shader(kind: u32, text: &str) -> Result<u32> {
    // SAFETY: Called only with a current GLES context; source remains a valid
    // NUL-terminated string until compilation completes. Handles are checked.
    unsafe {
        let source = CString::new(text).unwrap();
        let id = glCreateShader(kind);
        glShaderSource(id, 1, &source.as_ptr(), ptr::null());
        glCompileShader(id);
        let mut compiled = 0;
        glGetShaderiv(id, GL_COMPILE_STATUS, &mut compiled);
        if compiled == 0 {
            glDeleteShader(id);
            return Err("GLES shader compilation failed".into());
        }
        Ok(id)
    }
}
impl Drop for Renderer {
    fn drop(&mut self) {
        // SAFETY: This thread still owns the current context; cached images and
        // textures are destroyed before unbinding and destroying their EGL objects.
        unsafe {
            for (image, texture) in self.images.values() {
                glDeleteTextures(1, texture);
                (self.destroy_image)(self.display, *image);
            }
            glDeleteProgram(self.program);
            eglMakeCurrent(
                self.display,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
            );
            eglDestroySurface(self.display, self.surface);
            eglDestroyContext(self.display, self.context);
        }
    }
}

#[derive(Default)]
struct Metrics {
    input_fps: f32,
    rate_control: bool,
    message: String,
    frames: u64,
    state: u32,
    dropped: u64,
    fps: f32,
    process_ms: f32,
    age_ms: Option<f32>,
}
struct Worker {
    stop: Arc<AtomicBool>,
    join: thread::JoinHandle<()>,
    metrics: Arc<Mutex<Metrics>>,
}
static WORKERS: OnceLock<Mutex<HashMap<u64, Worker>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn workers() -> &'static Mutex<HashMap<u64, Worker>> {
    WORKERS.get_or_init(Mutex::default)
}
#[no_mangle]
pub extern "system" fn Java_io_github_ahdcamera_camera_NativeBridge_probe(
    mut env: JNIEnv,
    _: JClass,
    node: JString,
) -> jstring {
    let result = (|| -> Result<String> {
        let path: String = env.get_string(&node).map_err(|e| e.to_string())?.into();
        ahd_v4l2::probe_capabilities(&path).map_err(|e| e.to_string())
    })();
    let message = match result {
        Ok(s) => s,
        Err(e) => format!("error:{e}"),
    };
    env.new_string(message)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}
fn monotonic() -> i64 {
    let mut t = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // SAFETY: t is valid writable timespec storage; CLOCK_MONOTONIC is supported.
    unsafe {
        libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut t);
    }
    t.tv_sec * 1_000_000_000 + t.tv_nsec
}

struct CaptureRequest {
    node: String,
    width: u32,
    height: u32,
    fps: u32,
    out_w: u32,
    out_h: u32,
}

fn run(
    request: CaptureRequest,
    preview: Window,
    encoder: Option<Window>,
    stop: Arc<AtomicBool>,
    metrics: Arc<Mutex<Metrics>>,
) -> Result<()> {
    let CaptureRequest {
        node,
        width,
        height,
        fps,
        out_w,
        out_h,
    } = request;
    let mut capture =
        Capture::open(&node, width, height, fps).map_err(|e| format!("{node}: {e}"))?;
    let layout = capture.layout;
    metrics.lock().unwrap().rate_control = capture.can_set_fps;
    let rga = Arc::new(Rga::load()?);
    let recording = encoder.is_some();
    let (w, h) = if recording {
        (layout.width, layout.height)
    } else {
        let bound_w = out_w.min(layout.width).max(16);
        let bound_h = out_h.min(layout.height).max(2);
        let scale =
            (bound_w as f64 / layout.width as f64).min(bound_h as f64 / layout.height as f64);
        let w = ((layout.width as f64 * scale) as u32 & !15).max(16);
        let h = ((w as u64 * layout.height as u64 / layout.width as u64) as u32 & !1).max(2);
        (w, h)
    };
    let pool = (0..3)
        .map(|_| HardwareFrame::allocate(rga.clone(), (w + 15) & !15, (h + 1) & !1).map(Arc::new))
        .collect::<Result<Vec<_>>>()?;
    let mut inputs = std::collections::HashMap::new();
    let mailbox = Arc::new(LatestFrame::<(Arc<HardwareFrame>, i64)>::default());
    let encoder_error = Arc::new(Mutex::new(None));
    let encoder_join = encoder.map(|window| {
        let input = mailbox.clone();
        let errors = encoder_error.clone();
        thread::spawn(move || {
            let result = (|| -> Result<()> {
                let mut render = Renderer::new(window, true)?;
                loop {
                    if let Some((frame, pts)) = input.take(Duration::from_millis(100)) {
                        render.render(&frame, pts)?;
                    } else if input.is_closed() {
                        break;
                    }
                }
                Ok(())
            })();
            if let Err(e) = result {
                *errors.lock().unwrap() = Some(e);
            }
        })
    });
    let result = (|| -> Result<()> {
        let mut render = Renderer::new(preview, false)?;
        let mut period = Instant::now();
        let mut count = 0;
        let mut first_present = true;
        let mut timeout_count = 0;
        let mut input_rate = ahd_core::frame_rate::InputRate::default();
        let mut pacing = ahd_core::frame_rate::FramePacer::new(fps);
        while !stop.load(Ordering::Acquire) {
            let dropped = capture.dropped;
            let latest = {
                let _trace = TraceSection::begin(c"ahd.v4l2.wait");
                capture.latest(100).map_err(|e| e.to_string())?
            };
            let Some(frame) = latest else {
                timeout_count += 1;
                if timeout_count >= 30 {
                    return Err("capture timed out for 3 seconds; check input signal".into());
                }
                continue;
            };
            timeout_count = 0;
            let timestamp_ns = if frame.timestamp.nanos > 0 {
                frame.timestamp.nanos
            } else {
                monotonic() as u64
            };
            if let Some(rate) = input_rate.observe(timestamp_ns, frame.sequence) {
                metrics.lock().unwrap().input_fps = rate;
            }
            if !pacing.accept(timestamp_ns) {
                continue;
            }
            let Some(output) = pool.iter().find(|b| Arc::strong_count(b) == 1) else {
                continue;
            };
            let start = Instant::now();
            let input = match inputs.entry(frame.dma_fd()) {
                std::collections::hash_map::Entry::Occupied(entry) => entry.into_mut(),
                std::collections::hash_map::Entry::Vacant(entry) => {
                    entry.insert(rga.import_input(frame.dma_fd(), layout.allocation_bytes)?)
                }
            };
            rga.convert(input, layout, output)?;
            let age = frame
                .timestamp
                .age_at_monotonic(monotonic() as u64)
                .map(|v| v as f32 / 1_000_000.0);
            drop(frame); // RGA has completed before V4L2 gets its buffer back.
            let pts = monotonic();
            render.render(output, pts)?;
            if recording && encoder_error.lock().unwrap().is_none() {
                let _ = mailbox.publish((output.clone(), pts));
            }
            if first_present {
                period = Instant::now();
                first_present = false;
            } else {
                count += 1;
            }
            if period.elapsed() >= Duration::from_secs(1) {
                let mut m = metrics.lock().unwrap();
                m.message = format!(
                    "{}×{} · RGA / GLES{}",
                    layout.width,
                    layout.height,
                    encoder_error
                        .lock()
                        .unwrap()
                        .as_ref()
                        .map(|e| format!(" · recording failed {e}"))
                        .unwrap_or_default()
                );
                m.state = 1;
                m.frames += count;
                m.fps = count as f32 / period.elapsed().as_secs_f32();
                m.dropped = dropped + mailbox.replaced();
                m.process_ms = start.elapsed().as_secs_f32() * 1000.0;
                m.age_ms = age;
                count = 0;
                period = Instant::now();
            }
        }
        Ok(())
    })();
    mailbox.close();
    if let Some(join) = encoder_join {
        let _ = join.join();
    }
    result
}

#[no_mangle]
pub extern "system" fn Java_io_github_ahdcamera_camera_NativeBridge_start(
    mut env: JNIEnv,
    _: JClass,
    node: JString,
    surface: JObject,
    encoder: JObject,
    width: i32,
    height: i32,
    fps: i32,
    out_w: i32,
    out_h: i32,
) -> jlong {
    let result = (|| -> Result<u64> {
        check(
            width > 0 && height > 0 && fps > 0 && out_w > 0 && out_h > 0,
            "invalid resolution or frame rate",
        )?;
        let path: String = env.get_string(&node).map_err(|e| e.to_string())?.into();
        let acquire = |obj: &JObject| -> Result<Window> {
            // SAFETY: env and the Surface local reference belong to this JNI call.
            // The returned window reference is independently owned by Window.
            let p = unsafe { ANativeWindow_fromSurface(env.get_raw().cast(), obj.as_raw().cast()) };
            check(!p.is_null(), "invalid Surface")?;
            Ok(Window(p))
        };
        let preview = acquire(&surface)?;
        let encoder = if encoder.is_null() {
            None
        } else {
            Some(acquire(&encoder)?)
        };
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        let stop = Arc::new(AtomicBool::new(false));
        let stop_worker = stop.clone();
        let metrics = Arc::new(Mutex::new(Metrics {
            message: "initializing V4L2 / RGA".into(),
            ..Default::default()
        }));
        let report = metrics.clone();
        let join = thread::Builder::new()
            .name(format!("ahd-{id}"))
            .spawn(move || {
                if let Err(e) = run(
                    CaptureRequest {
                        node: path,
                        width: width as _,
                        height: height as _,
                        fps: fps as _,
                        out_w: out_w as _,
                        out_h: out_h as _,
                    },
                    preview,
                    encoder,
                    stop_worker,
                    report.clone(),
                ) {
                    let mut m = report.lock().unwrap();
                    m.state = 3;
                    m.message = e;
                    m.fps = 0.0;
                }
            })
            .map_err(|e| e.to_string())?;
        workers().lock().unwrap().insert(
            id,
            Worker {
                stop,
                join,
                metrics,
            },
        );
        Ok(id)
    })();
    match result {
        Ok(id) => id as _,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e);
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_ahdcamera_camera_NativeBridge_stop(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    let worker = workers().lock().unwrap().remove(&(handle as u64));
    if let Some(worker) = worker {
        worker.stop.store(true, Ordering::Release);
        let _ = worker.join.join();
    }
}
#[no_mangle]
pub extern "system" fn Java_io_github_ahdcamera_camera_NativeBridge_status(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jstring {
    let report = workers()
        .lock()
        .unwrap()
        .get(&(handle as u64))
        .map(|w| w.metrics.clone());
    let text = report
        .map(|m| {
            let m = m.lock().unwrap();
            format!(
                "{}|{}|{}|{}|{}|{}|{}|{}",
                m.message.replace('|', "/"),
                m.fps,
                m.dropped,
                m.process_ms,
                m.age_ms.unwrap_or(-1.0),
                m.state,
                m.input_fps,
                u8::from(m.rate_control)
            )
        })
        .unwrap_or_else(|| "stopped|0|0|0|-1|2".into());
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}
