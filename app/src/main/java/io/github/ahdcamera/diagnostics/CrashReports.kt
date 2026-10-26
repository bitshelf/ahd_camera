package io.github.ahdcamera.diagnostics

import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Dump + annotations + bounded pending reports, inspired by Firefox's report separation. */
internal class CrashReports(private val app: Application, private val root: File) {
    fun collect(exit: ApplicationExitInfo) {
        if (
            exit.reason !in
                setOf(
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_CRASH,
                )
        )
            return
        val pending = File(root, "pending").apply { mkdirs() }
        val id = "${exit.timestamp}-${exit.pid}-${exit.reason}"
        val report = File(pending, id)
        if (File(report, "report.extra.json").exists()) return
        report.mkdirs()
        val dumpName =
            when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "tombstone.pb"
                ApplicationExitInfo.REASON_ANR -> "anr.txt"
                else -> null
            }
        var available = false
        var complete = false
        var sha256: String? = null
        var failure: String? = null
        if (dumpName != null)
            try {
                exit.traceInputStream?.use { input ->
                    available = true
                    val partial = File(report, "$dumpName.partial")
                    val digest = MessageDigest.getInstance("SHA-256")
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var remaining = 2 * 1024 * 1024
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) {
                                complete = true
                                break
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            remaining -= count
                        }
                        if (!complete && remaining == 0) complete = input.read() == -1
                    }
                    if (complete) {
                        check(partial.renameTo(File(report, dumpName)))
                        sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    } else {
                        partial.delete()
                        failure =
                            "dump exceeded 2 MiB cap; omitted instead of exporting a corrupted protobuf"
                    }
                }
            } catch (e: Exception) {
                failure = e.toString()
            }
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        val annotation =
            JSONObject().apply {
                put("report_id", id)
                put("source", "Android ApplicationExitInfo / debuggerd")
                put("time_ms", exit.timestamp)
                put("pid", exit.pid)
                put("process", exit.processName)
                put("reason", exit.reason)
                put("status", exit.status)
                put("description", exit.description)
                put("pss_kb", exit.pss)
                put("rss_kb", exit.rss)
                put("app_package", app.packageName)
                put("current_app_version", version.versionName)
                put("current_version_code", version.longVersionCode)
                put("current_firmware", Build.FINGERPRINT)
                put(
                    "version_note",
                    "current startup version; historical module Build IDs are authoritative",
                )
                put("dump_available", available)
                put("dump_complete", complete)
                put("dump_file", if (complete) dumpName else JSONObject.NULL)
                put("dump_sha256", sha256 ?: JSONObject.NULL)
                put("collection_error", failure ?: JSONObject.NULL)
                put(
                    "process_state_summary",
                    exit.processStateSummary?.toString(Charsets.UTF_8) ?: JSONObject.NULL,
                )
            }
        File(report, "report.extra.json.partial").writeText(annotation.toString(2))
        check(File(report, "report.extra.json.partial").renameTo(File(report, "report.extra.json")))
        pending
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.name.substringBefore('-').toLongOrNull() ?: 0L }
            .drop(5)
            .forEach { it.deleteRecursively() }
    }
}
