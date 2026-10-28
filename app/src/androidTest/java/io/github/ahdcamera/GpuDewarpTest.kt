package io.github.ahdcamera

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ahdcamera.processing.DewarpSettings
import io.github.ahdcamera.processing.GpuDewarp
import io.github.ahdcamera.camera.PreviewLifetime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

@RunWith(AndroidJUnit4::class)
class GpuDewarpTest {
    private val width = 320
    private val height = 240
    private fun render(settings: DewarpSettings, draw: (android.graphics.Canvas) -> Unit): IntArray {
        val reader = ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val error = AtomicReference<Throwable?>()
        val renderer = GpuDewarp(width,height,reader.surface,null,settings) { error.set(it) }
        try {
            val canvas = renderer.surface.lockCanvas(null)
            try { draw(canvas) } finally { renderer.surface.unlockCanvasAndPost(canvas) }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                error.get()?.let { throw AssertionError("GPU failed", it) }
                val image = reader.acquireLatestImage()
                if (image != null) return image.use {
                    val plane = it.planes[0]; val buffer = plane.buffer
                    IntArray(width*height) { i ->
                        val pos = (i/width)*plane.rowStride + (i%width)*plane.pixelStride
                        Color.rgb(buffer.get(pos).toInt() and 255,buffer.get(pos+1).toInt() and 255,buffer.get(pos+2).toInt() and 255)
                    }
                }
                SystemClock.sleep(20)
            }
            error("No GPU output frame")
        } finally { renderer.close(); reader.close() }
    }

    @Test fun originalModePreservesOrientationAndColors() {
        val result = render(DewarpSettings(enabled=false)) { c ->
            val p = Paint(); p.color=Color.RED;c.drawRect(0f,0f,160f,120f,p)
            p.color=Color.GREEN;c.drawRect(160f,0f,320f,120f,p)
            p.color=Color.BLUE;c.drawRect(0f,120f,160f,240f,p)
            p.color=Color.WHITE;c.drawRect(160f,120f,320f,240f,p)
        }
        assertEquals(Color.RED,result[30*width+30])
        assertEquals(Color.GREEN,result[30*width+290])
        assertEquals(Color.BLUE,result[210*width+30])
        assertEquals(Color.WHITE,result[210*width+290])
    }

    @Test fun curvedEquidistantGridBecomesStraight() {
        // Independently synthesize a curved source line from a known pinhole scene.
        val settings = DewarpSettings(strength=1f,lensFov=180f,outputFov=100f)
        val result = render(settings) { c ->
            c.drawColor(Color.BLACK)
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;strokeWidth=5f;style=Paint.Style.STROKE }
            for (outX in listOf(80.0,240.0)) {
                val path=Path()
                for (y in 0..height) {
                    val a=(outX/width-0.5)*2*tan(Math.toRadians(50.0))
                    val b=(y.toDouble()/height-0.5)*2*tan(Math.toRadians(50.0))*height/width
                    val r=hypot(a,b);val scale=if(r==0.0) 1.0/Math.PI else atan(r)/r/Math.PI
                    val xSrc=((0.5+a*scale)*width).toFloat()
                    val ySrc=((0.5+b*scale*width/height)*height).toFloat()
                    if(y==0)path.moveTo(xSrc,ySrc) else path.lineTo(xSrc,ySrc)
                }
                c.drawPath(path,p)
            }
        }
        for(x in listOf(80,240)) for(y in 30..210 step 30) {
            assertTrue("Line must be straight at ($x,$y)",(x-4..x+4).any { Color.red(result[y*width+it])>180 })
        }
    }
    @Test fun destroyedPreviewDoesNotReceiveFurtherGpuFrames() {
        val reader = ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        val lifetime = PreviewLifetime()
        val error = AtomicReference<Throwable?>()
        val renderer = GpuDewarp(width,height,reader.surface,null,DewarpSettings(),lifetime) { error.set(it) }
        try {
            fun postFrame() {
                val canvas = renderer.surface.lockCanvas(null)
                canvas.drawColor(Color.RED)
                renderer.surface.unlockCanvasAndPost(canvas)
            }
            postFrame()
            val deadline = SystemClock.elapsedRealtime()+5000
            while(renderer.renderedFrames == 0L && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(10)
            assertTrue(renderer.renderedFrames>0)
            lifetime.invalidate()
            val frames = renderer.renderedFrames
            reader.close() // SurfaceHolder may destroy its BufferQueue as soon as invalidate returns.
            postFrame()
            SystemClock.sleep(100)
            assertNull(error.get())
            assertEquals(frames,renderer.renderedFrames)
        } finally { renderer.close(); reader.close() }
    }

}
