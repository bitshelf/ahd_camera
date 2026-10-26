package io.github.ahdcamera

import android.app.Application
import android.os.Trace
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ahdcamera.camera.*
import io.github.ahdcamera.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class WallViewModel(application: Application) : AndroidViewModel(application) {
    val engine: WallEngine = createEngine(application)
    private val prefs = application.getSharedPreferences("wall-v4l2", 0)
    private val mutableCatalog = MutableStateFlow(Catalog())
    val catalog = mutableCatalog.asStateFlow()
    private val mutableSelected = MutableStateFlow(prefs.getStringSet("selected", emptySet()).orEmpty().toSet())
    val selected = mutableSelected.asStateFlow()
    private val mutableRows = MutableStateFlow(prefs.getInt("rows", 2).coerceIn(1, 9))
    val rows = mutableRows.asStateFlow()
    private val mutableColumns = MutableStateFlow(prefs.getInt("columns", 2).coerceIn(1, 9))
    val columns = mutableColumns.asStateFlow()
    private val mutableShowFps = MutableStateFlow(prefs.getBoolean("showFps", true))
    val showFps = mutableShowFps.asStateFlow()
    private val mutableShowResolution = MutableStateFlow(prefs.getBoolean("showResolution", false))
    val showResolution = mutableShowResolution.asStateFlow()
    private val mutableSize = MutableStateFlow<Map<String, StreamSize>>(emptyMap())
    val size = mutableSize.asStateFlow()
    private val mutableFps = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val fps = mutableFps.asStateFlow()
    private val inputRates = mutableMapOf<Pair<String, StreamSize>, Int>()
    private val controllableRates = mutableSetOf<Pair<String, StreamSize>>()
    private val mutableSelectableFps = MutableStateFlow<Map<String, List<Pair<Int, Int>>>>(emptyMap())
    val selectableFps = mutableSelectableFps.asStateFlow()
    private val mutableRecording = MutableStateFlow(emptySet<String>())
    val recording = mutableRecording.asStateFlow()
    private val surfaces = mutableMapOf<String, PreviewTarget>()
    private var resumed = false

    init {
        refresh()
        viewModelScope.launch {
            engine.status.collect { statuses ->
                var changed = false
                statuses.forEach { (id, status) ->
                    val dimensions = size.value[id] ?: return@forEach
                    if (status.rateControllable && controllableRates.add(id to dimensions)) changed = true
                    val rate = status.inputFps
                    val integer = if (rate.isFinite()) rate.roundToInt() else 0
                    if (!status.rateControllable && status.streaming && integer > 0 && abs(rate - integer) < 0.2f &&
                        inputRates[id to dimensions] != integer) {
                        inputRates[id to dimensions] = integer
                        changed = true
                    }
                }
                if (changed && recording.value.isEmpty()) reconcileOptions()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                if (resumed) Diagnostics.event("stream_metrics", engine.status.value.entries.joinToString("; ") { (id, value) ->
                    "$id fps=${value.fps} failures=${value.failures} skipped=${value.skippedFrames} callback_age_ms=${value.callbackAgeMs} status=${value.message}"
                })
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            mutableCatalog.value = withContext(Dispatchers.IO) { readCatalog(getApplication()) }
            reconcileOptions()
        }
    }

    private fun chosenCameras() = catalog.value.cameras.filter { it.id in selected.value }

    private fun reconcileOptions() {
        val sizes = mutableMapOf<String, StreamSize>()
        val rates = mutableMapOf<String, Pair<Int, Int>>()
        for (camera in chosenCameras()) {
            val options = camera.sizes.distinct().sortedByDescending { it.area }
            val saved = StreamSize(prefs.getInt("width." + camera.id, 0), prefs.getInt("height." + camera.id, 0))
            val currentSize = (size.value[camera.id] ?: saved).takeIf { it in options } ?: options.firstOrNull() ?: continue
            sizes[camera.id] = currentSize
            val fpsOptions = ratesFor(camera, currentSize)
            val savedRate = prefs.getInt("fpsMin." + camera.id, 0) to prefs.getInt("fpsMax." + camera.id, 0)
            val currentRate = (fps.value[camera.id] ?: savedRate).takeIf { it in fpsOptions } ?: fpsOptions.firstOrNull()
            if (currentRate != null) rates[camera.id] = currentRate
        }
        mutableSize.value = sizes
        mutableFps.value = rates
        mutableSelectableFps.value = chosenCameras().associate { camera ->
            val dimensions = sizes[camera.id]
            camera.id to if (dimensions != null && (inputRates.containsKey(camera.id to dimensions) || controllableRates.contains(camera.id to dimensions))) ratesFor(camera, dimensions) else emptyList()
        }
        // Persist a corrected choice after the live input establishes the effective upper bound.
        prefs.edit().apply {
            rates.forEach { (id, rate) -> putInt("fpsMin." + id, rate.first); putInt("fpsMax." + id, rate.second) }
        }.apply()
        applyTargets()
    }

    private fun ratesFor(camera: CameraInfo, dimensions: StreamSize): List<Pair<Int, Int>> {
        val reported = StreamPolicy.supportedFps(listOf(camera), dimensions)
        return StreamPolicy.effectiveFps(reported, inputRates[camera.id to dimensions],
            controllableRates.contains(camera.id to dimensions))
    }

    fun toggle(id: String) {
        if (recording.value.isNotEmpty()) return
        mutableSelected.value = if (id in selected.value) selected.value - id else selected.value + id
        prefs.edit().putStringSet("selected", selected.value).apply()
        reconcileOptions()
    }

    fun resetDisplaySettings() {
        if (recording.value.isNotEmpty()) return
        setRows(2)
        setColumns(2)
        setShowFps(true)
        setShowResolution(false)
    }

    fun setShowFps(value: Boolean) {
        mutableShowFps.value = value
        prefs.edit().putBoolean("showFps", value).apply()
    }

    fun setShowResolution(value: Boolean) {
        mutableShowResolution.value = value
        prefs.edit().putBoolean("showResolution", value).apply()
    }

    fun setRows(value: Int) {
        if (recording.value.isNotEmpty() || value !in 1..9) return
        mutableRows.value = value
        prefs.edit().putInt("rows", value).apply()
    }

    fun setColumns(value: Int) {
        if (recording.value.isNotEmpty() || value !in 1..9) return
        mutableColumns.value = value
        prefs.edit().putInt("columns", value).apply()
    }

    fun setSize(id: String, value: StreamSize) {
        val camera = chosenCameras().firstOrNull { it.id == id } ?: return
        if (recording.value.isNotEmpty() || value !in camera.sizes) return
        mutableSize.value = size.value + (id to value)
        prefs.edit().putInt("width." + id, value.width).putInt("height." + id, value.height).apply()
        reconcileOptions()
    }

    fun setFps(id: String, value: Pair<Int, Int>) {
        val camera = chosenCameras().firstOrNull { it.id == id } ?: return
        if (recording.value.isNotEmpty() || value !in selectableFps.value[camera.id].orEmpty()) return
        mutableFps.value = fps.value + (id to value)
        prefs.edit().putInt("fpsMin." + id, value.first).putInt("fpsMax." + id, value.second).apply()
        applyTargets()
    }

    fun setResumed(value: Boolean) {
        Diagnostics.event("foreground", value.toString())
        resumed = value
        if (!value) mutableRecording.value = emptySet()
        applyTargets()
    }

    fun attach(target: PreviewTarget) {
        surfaces[target.id] = target
        applyTargets()
    }

    fun detach(id: String, surface: Surface) {
        if (surfaces[id]?.surface === surface) {
            surfaces.remove(id)
            mutableRecording.value = recording.value - id
            applyTargets()
        }
    }

    fun toggleRecording(id: String) {
        mutableRecording.value = if (id in recording.value) recording.value - id else recording.value + id
        if (recording.value.isEmpty()) reconcileOptions() else applyTargets()
    }

    private fun applyTargets() {
        val targets = if (resumed) selected.value.mapNotNull { id ->
            val currentSize = size.value[id] ?: return@mapNotNull null
            val currentFps = fps.value[id] ?: return@mapNotNull null
            surfaces[id]?.takeIf { it.size == currentSize }?.copy(recording = id in recording.value, fpsRange = currentFps)
        } else emptyList()
        Trace.setCounter("ahd.preview_streams", targets.size.toLong())
        Trace.setCounter("ahd.recording_streams", recording.value.size.toLong())
        Diagnostics.annotate("v3 backend=V4L2 streams=" + targets.size + " recording=" + recording.value.size)
        engine.configure(targets)
    }

    override fun onCleared() { engine.close() }
}
