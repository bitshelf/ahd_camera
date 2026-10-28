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
    private fun render(settings: DewarpSettings, outputWidth: Int = width, outputHeight: Int = height,
        draw: (android.graphics.Canvas) -> Unit): IntArray {
        val reader = ImageReader.newInstance(outputWidth,outputHeight,PixelFormat.RGBA_8888,2,
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
                    IntArray(outputWidth*outputHeight) { i ->
                        val pos = (i/outputWidth)*plane.rowStride + (i%outputWidth)*plane.pixelStride
                        Color.rgb(buffer.get(pos).toInt() and 255,buffer.get(pos+1).toInt() and 255,buffer.get(pos+2).toInt() and 255)
                    }
                }
                SystemClock.sleep(20)
            }
            error("No GPU output frame")
        } finally { renderer.close(); reader.close() }
    }

    @Test fun tileSizedOutputsCoverBoundsWithoutStretching() {
        // Output dimensions deliberately differ from the camera buffer. Edge
        // stripes must crop away, and a square must stay square in both shapes.
        for ((w,h) in listOf(256 to 256,480 to 120)) {
            val result = render(DewarpSettings(enabled=false),w,h) { c ->
                c.drawColor(Color.GREEN)
                val p = Paint().apply { color=Color.RED }
                if(w==h) {
                    c.drawRect(0f,0f,40f,240f,p);c.drawRect(280f,0f,320f,240f,p)
                } else {
                    c.drawRect(0f,0f,320f,80f,p);c.drawRect(0f,160f,320f,240f,p)
                }
                p.color=Color.BLUE;c.drawRect(144f,104f,176f,136f,p)
            }
            for(x in listOf(2,w-3)) for(y in listOf(2,h-3))
                assertEquals("Output must fill its tile at ($x,$y)",Color.GREEN,result[y*w+x])
            val inside = if(w==h) 14 else 20
            val outside = if(w==h) 22 else 30
            for((dx,dy) in listOf(inside to 0,0 to inside,-inside to 0,0 to -inside))
                assertEquals(Color.BLUE,result[(h/2+dy)*w+w/2+dx])
            for((dx,dy) in listOf(outside to 0,0 to outside,-outside to 0,0 to -outside))
                assertEquals(Color.GREEN,result[(h/2+dy)*w+w/2+dx])
        }
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
