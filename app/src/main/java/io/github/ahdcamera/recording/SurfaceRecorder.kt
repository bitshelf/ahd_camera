package io.github.ahdcamera.recording

import io.github.ahdcamera.R

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import io.github.ahdcamera.camera.StreamSize
import io.github.ahdcamera.diagnostics.Diagnostics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owns one hardware encoder and a pending MediaStore item. Call create/close off main. */
class SurfaceRecorder
private constructor(private val context: Context, private val onResult: (String) -> Unit) :
    AutoCloseable {
    private var uri: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var input: Surface? = null
    val surface: Surface
        get() = checkNotNull(input)

    private val finished = CountDownLatch(1)
    @Volatile private var stopping = false
    @Volatile private var deadlineNanos = Long.MAX_VALUE
    private var drainThread: Thread? = null

    companion object {
        fun create(
            context: Context,
            cameraId: String,
            size: StreamSize,
            fps: Int,
            onResult: (String) -> Unit,
        ): SurfaceRecorder {
            val result = SurfaceRecorder(context, onResult)
            try {
                val mime = MediaFormat.MIMETYPE_VIDEO_AVC
                val info =
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                        info.isEncoder &&
                            info.isHardwareAccelerated &&
                            info.supportedTypes.any { it.equals(mime, true) } &&
                            runCatching {
                                    info
                                        .getCapabilitiesForType(mime)
                                        .videoCapabilities
                                        ?.areSizeAndRateSupported(
                                            size.width,
                                            size.height,
                                            fps.toDouble(),
                                        ) == true
                                }
                                .getOrDefault(false)
                    } ?: error(context.getString(R.string.no_hardware_encoder, size.width, size.height, fps))
                val capabilities = info.getCapabilitiesForType(mime)
                Diagnostics.event(
                    "recording_prepare",
                    "camera=$cameraId size=$size fps=$fps codec=${info.name} advertised_instances=${capabilities.maxSupportedInstances}",
                )
                require(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                        capabilities.colorFormats
                ) {
                    context.getString(R.string.encoder_surface_unsupported)
                }
                val format =
                    MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                        val bitrate =
                            (size.width.toLong() * size.height * fps / 8)
                                .coerceIn(1_000_000L, 40_000_000L)
                                .toInt()
                        setInteger(
                            MediaFormat.KEY_BIT_RATE,
                            requireNotNull(capabilities.videoCapabilities).bitrateRange.clamp(bitrate),
                        )
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }
                val safeId = cameraId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                result.uri =
                    context.contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(
                                MediaStore.Video.Media.DISPLAY_NAME,
                                "AHD_${safeId}_${System.currentTimeMillis()}.mp4",
                            )
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AhdCamera")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        },
                    ) ?: error(context.getString(R.string.recording_create_failed))
                result.descriptor =
                    context.contentResolver.openFileDescriptor(result.uri!!, "rw")
                        ?: error(context.getString(R.string.recording_open_failed))
                result.muxer =
                    MediaMuxer(
                        result.descriptor!!.fileDescriptor,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                    )
                result.codec =
                    MediaCodec.createByCodecName(info.name).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    }
                result.input = result.codec!!.createInputSurface()
                result.codec!!.start()
                result.drainThread = Thread({ result.drain() }, "ahd-mp4-$safeId").apply { start() }
                return result
            } catch (e: Exception) {
                Diagnostics.error("recording_prepare_failed", e)
                result.cleanup(false)
                throw e
            }
        }
    }

    private fun drain() {
        var track = -1
        var started = false
        var samples = 0L
        var succeeded = false
        var failure: String? = null
        var previousPts = -1L
        val encoder = checkNotNull(codec)
        val output = checkNotNull(muxer)
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (System.nanoTime() < deadlineNanos) {
                val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!started) { context.getString(R.string.encoder_format_changed) }
                        track = output.addTrack(encoder.outputFormat)
                        output.start()
                        started = true
                    }
                    index >= 0 -> {
                        try {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                                bufferInfo.size = 0
                            if (bufferInfo.size > 0) {
                                check(started) { context.getString(R.string.encoder_no_format) }
                                val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                check(bufferInfo.presentationTimeUs > previousPts) { context.getString(R.string.recording_invalid_timestamp) }
                                output.writeSampleData(track, buffer, bufferInfo)
                                previousPts = bufferInfo.presentationTimeUs
                                samples++
                            }
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                succeeded = samples > 0
                                break
                            }
                        } finally {
                            encoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            if (!succeeded) failure = context.getString(R.string.recording_incomplete)
        } catch (e: Exception) {
            Diagnostics.error("recording_drain_failed", e)
            failure = context.getString(R.string.recording_failed, e.javaClass.simpleName)
        } finally {
            if (started)
                try {
                    output.stop()
                } catch (e: Exception) {
                    succeeded = false
                    failure = context.getString(R.string.mp4_finalize_failed, e.javaClass.simpleName)
                }
            val saved = cleanup(succeeded)
            Diagnostics.event(
                "recording_finished",
                "saved=$saved samples=$samples failure=$failure",
            )
            onResult(if (saved) context.getString(R.string.recording_saved) else failure ?: context.getString(R.string.recording_save_failed))
            finished.countDown()
        }
    }

    /** Producer must stop submitting Surface buffers before close. */
    override fun close() {
        if (!stopping) {
            stopping = true
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            runCatching { codec?.signalEndOfInputStream() }
        }
        // Never release codec/muxer while the drain thread owns output buffers.
        if (drainThread != null && !finished.await(5, TimeUnit.SECONDS)) onResult(context.getString(R.string.recording_finishing))
    }

    private fun cleanup(keep: Boolean): Boolean {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { input?.release() }
        input = null
        runCatching { muxer?.release() }
        muxer = null
        runCatching { descriptor?.close() }
        descriptor = null
        val target = uri ?: return false
        uri = null
        return try {
            if (keep) {
                check(
                    context.contentResolver.update(
                        target,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    ) > 0
                )
                true
            } else {
                context.contentResolver.delete(target, null, null)
                false
            }
        } catch (_: Exception) {
            runCatching { context.contentResolver.delete(target, null, null) }
            false
        }
    }
}
