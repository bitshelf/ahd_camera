package io.github.ahdcamera.processing

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.github.ahdcamera.diagnostics.Diagnostics
import io.github.ahdcamera.camera.PreviewLifetime
import kotlin.math.PI
import kotlin.math.tan

/** One GL owner per camera. Camera -> OES texture -> preview and encoder; no pixel readback. */
class GpuDewarp(
    private val width: Int,
    private val height: Int,
    private val preview: Surface,
    private val encoder: Surface?,
    initial: DewarpSettings,
    private val lifetime: PreviewLifetime = PreviewLifetime(),
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("ahd-dewarp").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    @Volatile private var settings = initial.validated()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var previewWindow: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWindow: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var input: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    val surface: Surface get() = checkNotNull(cameraSurface)
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var lastRenderNs = 0L
        private set
    private var lastTimestamp = 0L
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }
    private var position = 0
    private var uv = 0
    private var transform = 0
    private var params = 0

    init {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try { initialize() } catch (e: Throwable) { failure = e; release() }
            finally { done.countDown() }
        }
        if (!done.await(8, TimeUnit.SECONDS)) {
            close()
            error("GPU initialization timed out")
        }
        failure?.let { close(); throw IllegalStateException("GPU initialization failed", it) }
    }

    fun update(value: DewarpSettings) { settings = value.validated() }

    private fun initialize() {
        display = acquireDisplay()
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE,
        ),0,configs,0,1,count,0) && count[0] > 0)
        context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT)
        previewWindow = EGL14.eglCreateWindowSurface(display,configs[0],preview,intArrayOf(EGL14.EGL_NONE),0)
        check(previewWindow != EGL14.EGL_NO_SURFACE)
        if (encoder != null) {
            encoderWindow = EGL14.eglCreateWindowSurface(display,configs[0],encoder,intArrayOf(EGL14.EGL_NONE),0)
            check(encoderWindow != EGL14.EGL_NO_SURFACE)
        }
        makeCurrent(previewWindow)
        val ids = IntArray(1)
        GLES20.glGenTextures(1,ids,0); texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        program = GLES20.glCreateProgram()
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        GLES20.glAttachShader(program,vs); GLES20.glAttachShader(program,fs); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        val status = IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        position = GLES20.glGetAttribLocation(program,"aPosition")
        uv = GLES20.glGetAttribLocation(program,"aUv")
        transform = GLES20.glGetUniformLocation(program,"uTransform")
        params = GLES20.glGetUniformLocation(program,"uParams")
        input = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width,height)
            setOnFrameAvailableListener({ drawFrame() },handler)
        }
        cameraSurface = Surface(input)
        Diagnostics.event("dewarp_gpu", "renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} input=${width}x$height approximate=true")
    }

    private fun makeCurrent(window: EGLSurface) {
        check(EGL14.eglMakeCurrent(display,window,window,context)) { "EGL makeCurrent failed" }
    }

    private fun drawFrame() = lifetime.whileAlive { drawLiveFrame() }

    private fun drawLiveFrame() {
        if (closed.get()) return
        try {
            makeCurrent(previewWindow)
            val image = checkNotNull(input)
            image.updateTexImage()
            val timestamp = image.timestamp
            if (timestamp <= lastTimestamp) return // Coalesced callbacks must not duplicate encoder PTS.
            lastTimestamp = timestamp
            image.getTransformMatrix(matrix)
            // One parameter snapshot for both outputs of this frame.
            val value = settings
            render(previewWindow,value,timestamp,false)
            if (encoderWindow != EGL14.EGL_NO_SURFACE) render(encoderWindow,value,timestamp,true)
            lastRenderNs = android.os.SystemClock.elapsedRealtimeNanos()
            renderedFrames++
        } catch (e: Throwable) {
            if (closed.compareAndSet(false,true)) {
                release(); thread.quitSafely(); onError(e)
            }
        }
    }

    private fun render(window: EGLSurface, value: DewarpSettings, timestamp: Long, recording: Boolean) {
        makeCurrent(window)
        GLES20.glViewport(0,0,width,height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0)
        GLES20.glUniformMatrix4fv(transform,1,false,matrix,0)
        GLES20.glUniform4f(params,if(value.enabled) value.strength else 0f,
            (2.0 * tan(value.outputFov * PI / 360.0)).toFloat(),
            (value.lensFov * PI / 180.0).toFloat(),width.toFloat()/height)
        vertices.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GPU remap failed" }
        if(recording) check(EGLExt.eglPresentationTimeANDROID(display,window,timestamp))
        check(EGL14.eglSwapBuffers(display,window)) { "EGL swap failed" }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
        val status = IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
        if(status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); error(message)
        }
        return shader
    }

    private fun release() {
        input?.setOnFrameAvailableListener(null)
        cameraSurface?.release(); cameraSurface = null
        input?.release(); input = null
        if(display != EGL14.EGL_NO_DISPLAY) {
            if(lifetime.alive && context != EGL14.EGL_NO_CONTEXT && previewWindow != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(display,previewWindow,previewWindow,context)) {
                if(program != 0) GLES20.glDeleteProgram(program)
                if(texture != 0) GLES20.glDeleteTextures(1,intArrayOf(texture),0)
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if(encoderWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,encoderWindow)
            if(previewWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,previewWindow)
            if(context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            EGL14.eglReleaseThread(); releaseDisplay()
        }
        display = EGL14.EGL_NO_DISPLAY
    }

    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        val done = CountDownLatch(1)
        handler.post { try { release() } finally { done.countDown(); thread.quitSafely() } }
        check(done.await(5,TimeUnit.SECONDS)) { "GPU shutdown timed out" }
    }

    companion object {
        private val displayLock = Any()
        private var sharedDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var displayUsers = 0
        private fun acquireDisplay(): EGLDisplay = synchronized(displayLock) {
            if (displayUsers == 0) {
                val candidate = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(candidate != EGL14.EGL_NO_DISPLAY)
                check(EGL14.eglInitialize(candidate, IntArray(2), 0, IntArray(2), 1))
                sharedDisplay = candidate
            }
            displayUsers++
            sharedDisplay
        }
        private fun releaseDisplay() = synchronized(displayLock) {
            if (--displayUsers == 0) {
                EGL14.eglTerminate(sharedDisplay)
                sharedDisplay = EGL14.EGL_NO_DISPLAY
            }
        }
        private const val VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { gl_Position=vec4(aPosition,0.0,1.0); vUv=aUv; }
        """
        // Rectilinear output ray -> approximate equidistant source. No calibrated coefficients invented.
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTransform;
            uniform vec4 uParams;
            varying vec2 vUv;
            void main() {
                vec2 ray=(vUv-0.5)*vec2(uParams.y,uParams.y/uParams.w);
                float r=length(ray);
                float scale=r>0.00001 ? atan(r)/r/uParams.z : 1.0/uParams.z;
                vec2 mapped=0.5+ray*scale*vec2(1.0,uParams.w);
                vec2 sampleUv=mix(vUv,mapped,uParams.x);
                if(any(lessThan(sampleUv,vec2(0.0))) || any(greaterThan(sampleUv,vec2(1.0)))) {
                    gl_FragColor=vec4(0.0,0.0,0.0,1.0);
                } else {
                    vec2 uv=(uTransform*vec4(sampleUv,0.0,1.0)).xy;
                    gl_FragColor=texture2D(uTexture,uv);
                }
            }
        """
    }
}
