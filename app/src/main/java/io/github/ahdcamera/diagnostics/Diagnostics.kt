package io.github.ahdcamera.diagnostics

import io.github.ahdcamera.R

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Bounded, metadata-only diagnostics. Producers never wait for disk or the log consumer. */
object Diagnostics {
    private data class Event(
        val time: Long,
        val elapsed: Long,
        val name: String,
        val detail: String,
    )

    private val queue = ArrayBlockingQueue<Event>(256)
    private val dropped = AtomicLong()
    private val stateSummary = AtomicReference("initializing".toByteArray())
    private val filesLock = Any()
    private lateinit var directory: File
    private lateinit var app: Application
    private const val TAG = "AhdCamera"

    fun initialize(application: Application) {
        app = application
        directory = File(application.filesDir, "diagnostics")
        Thread({ consume() }, "ahd-diagnostics").apply {
            isDaemon = true
            start()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The process is already failing. Preserve a capped crash marker without waiting on the
            // queue.
            runCatching {
                directory.mkdirs()
                File(directory, "last-java-crash.txt")
                    .writeText(
                        "${System.currentTimeMillis()} thread=${thread.name}\n${Log.getStackTraceString(error).take(16_384)}"
                    )
            }
            previous?.uncaughtException(thread, error)
        }
        event(
            "process_start",
            "${Build.MANUFACTURER}/${Build.MODEL} API=${Build.VERSION.SDK_INT} fingerprint=${Build.FINGERPRINT}",
        )
        if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build()
            )
        }
    }

    fun event(name: String, detail: String) {
        val event =
            Event(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                name.take(128),
                detail.take(12_288),
            )
        if (!queue.offer(event)) dropped.incrementAndGet()
        Log.i(TAG, "$name ${detail.take(1024)}")
    }

    fun error(name: String, error: Throwable) {
        event(name, Log.getStackTraceString(error))
    }

    fun annotate(summary: String) {
        stateSummary.set(summary.toByteArray(Charsets.UTF_8).take(128).toByteArray())
    }

    private fun consume() {
        directory.mkdirs()
        collectPreviousExits()
        var nextSnapshot = 0L
        while (true) {
            try {
                val item = queue.poll(1, TimeUnit.SECONDS)
                if (item != null)
                    synchronized(filesLock) {
                        val current = File(directory, "events-0.jsonl")
                        if (current.length() >= 256 * 1024) {
                            File(directory, "events-3.jsonl").delete()
                            for (index in 2 downTo 0) File(directory, "events-$index.jsonl")
                                .renameTo(File(directory, "events-${index+1}.jsonl"))
                        }
                        current.appendText(
                            JSONObject()
                                .apply {
                                    put("wall_ms", item.time)
                                    put("elapsed_ms", item.elapsed)
                                    put("event", item.name)
                                    put("detail", item.detail)
                                    put("diagnostic_events_dropped", dropped.get())
                                }
                                .toString() + "\n"
                        )
                    }
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSnapshot) {
                    snapshot()
                    nextSnapshot = now + 30_000
                }
            } catch (e: Exception) {
                Log.e(TAG, "diagnostic writer failed", e)
            }
        }
    }

    private fun snapshot() {
        app.getSystemService(ActivityManager::class.java).setProcessStateSummary(stateSummary.get())
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val thermal = app.getSystemService(PowerManager::class.java).currentThermalStatus
        event(
            "process_health",
            "pss_kb=${memory.totalPss} native_heap=${Debug.getNativeHeapAllocatedSize()} java_heap=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()} java_threads_approx=${Thread.activeCount()} thermal_status=$thermal",
        )
    }

    private fun collectPreviousExits() {
        runCatching {
                val manager = app.getSystemService(ActivityManager::class.java)
                val reports = CrashReports(app, directory)
                manager.getHistoricalProcessExitReasons(app.packageName, 0, 5).forEach { exit ->
                    runCatching { synchronized(filesLock) { reports.collect(exit) } }
                        .onFailure { Log.w(TAG, "crash report collection failed", it) }
                    event(
                        "previous_exit",
                        "time=${exit.timestamp} reason=${exit.reason} status=${exit.status} pss_kb=${exit.pss} description=${exit.description}",
                    )
                    if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                        exit.traceInputStream?.use { input ->
                            File(directory, "last-anr.txt").outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var remaining = 128 * 1024
                                while (remaining > 0) {
                                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                        }
                    }
                }
            }
            .onFailure { Log.w(TAG, "cannot read previous process exits", it) }
    }

    suspend fun export(
        context: Context,
        destination: Uri,
        includeCrashReports: Boolean = false,
    ): Unit =
        withContext(Dispatchers.IO) {
            val snapshot = File(context.cacheDir, "diagnostic-export-${System.nanoTime()}")
            try {
                snapshot.mkdirs()
                synchronized(filesLock) {
                    directory.listFiles().orEmpty().forEach { source ->
                        if (source.isFile)
                            source.copyTo(File(snapshot, source.name), overwrite = true)
                        else if (includeCrashReports && source.name == "pending")
                            source.copyRecursively(File(snapshot, source.name), overwrite = true)
                    }
                }
                val output =
                    context.contentResolver.openOutputStream(destination, "w") ?: error(context.getString(R.string.export_open_failed))
                ZipOutputStream(output).use { zip ->
                    snapshot
                        .walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            zip.putNextEntry(
                                ZipEntry(file.relativeTo(snapshot).invariantSeparatorsPath)
                            )
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
            } finally {
                snapshot.deleteRecursively()
            }
        }
}
