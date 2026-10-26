package io.github.ahdcamera.diagnostics;

import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import io.github.ahdcamera.camera.NativeBridge;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/** Root-only diagnostic of the native pipeline, not application/permission acceptance. */
public final class NativePipelineProbe {
    private static final class Stream {
        String node;
        long handle;
        ImageReader reader;
        AtomicInteger frames = new AtomicInteger();
        Set<Long> hashes = new HashSet<>();
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("library.so /dev/videoN ...");
        System.load(args[0]);
        HandlerThread callbacks = new HandlerThread("ahd-probe-images");
        callbacks.start();
        Handler handler = new Handler(callbacks.getLooper());
        List<Stream> streams = new ArrayList<>();
        try {
            for (int index = 1; index < args.length; index++) {
                Stream stream = new Stream();
                stream.node = args[index];
                String caps = NativeBridge.probe(stream.node);
                System.out.println("CAPABILITIES " + stream.node + " " + caps);
                String largest = null;
                long area = 0;
                for (String option : caps.split(";")) {
                    if (!option.contains("@")) continue;
                    String[] dimensions = option.split("@")[0].split("x");
                    long candidate = Long.parseLong(dimensions[0]) * Long.parseLong(dimensions[1]);
                    if (candidate > area) { largest = option; area = candidate; }
                }
                if (largest == null) throw new IllegalStateException("No dimensions: " + caps);
                String[] fields = largest.split("@");
                String[] dimensions = fields[0].split("x");
                int width = Integer.parseInt(dimensions[0]);
                int height = Integer.parseInt(dimensions[1]);
                int fps = 0;
                if (fields.length > 1) for (String rate : fields[1].split(",")) {
                    fps = Math.max(fps, Integer.parseInt(rate.split("-")[1]));
                }
                if (fps <= 0) throw new IllegalStateException("No driver-reported rates");
                stream.reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN);
                stream.reader.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image == null) return;
                        ByteBuffer pixels = image.getPlanes()[0].getBuffer();
                        CRC32 crc = new CRC32();
                        for (int offset = pixels.position(); offset < pixels.limit(); offset += 4096) crc.update(pixels.get(offset));
                        synchronized (stream.hashes) { stream.hashes.add(crc.getValue()); }
                        stream.frames.incrementAndGet();
                    }
                }, handler);
                streams.add(stream);
                stream.handle = NativeBridge.start(stream.node, stream.reader.getSurface(), null,
                    width, height, fps, width, height);
            }
            for (int second = 0; second < 12; second++) {
                Thread.sleep(1000);
                for (Stream stream : streams) System.out.println("STATUS " + stream.node + " " + NativeBridge.status(stream.handle));
            }
            boolean passed = true;
            for (Stream stream : streams) {
                int unique;
                synchronized (stream.hashes) { unique = stream.hashes.size(); }
                System.out.println("RESULT " + stream.node + " frames=" + stream.frames.get() + " unique_sampled_hashes=" + unique);
                passed &= stream.frames.get() > 0;
            }
            if (!passed) throw new IllegalStateException("One or more streams produced no images");
        } finally {
            for (Stream stream : streams) if (stream.handle != 0) NativeBridge.stop(stream.handle);
            for (Stream stream : streams) if (stream.reader != null) stream.reader.close();
            callbacks.quitSafely();
            callbacks.join(2000);
        }
    }
}
