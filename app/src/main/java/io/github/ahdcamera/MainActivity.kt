package io.github.ahdcamera

import android.app.Activity
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ahdcamera.camera.*
import io.github.ahdcamera.diagnostics.Diagnostics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: WallViewModel by viewModels()
    private var permissionGranted by mutableStateOf(false)
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            model.setResumed(granted)
            model.refresh()
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private fun enterFullscreen() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        enterFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            WallTheme { appearance, onAppearance ->
                WallScreen(
                    model,
                    permissionGranted,
                    appearance, onAppearance,
                    requestPermission = { cameraPermission.launch(Manifest.permission.CAMERA) },
                    settings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"),
                            )
                        )
                    },
                    rationale = { shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        model.setResumed(permissionGranted)
        model.refresh()
    }

    override fun onPause() {
        model.setResumed(false)
        super.onPause()
    }
}

@Composable
private fun WallScreen(
    model: WallViewModel,
    granted: Boolean,
    appearance: String,
    onAppearance: (String) -> Unit,
    requestPermission: () -> Unit,
    settings: () -> Unit,
    rationale: () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var includeCrashReports by rememberSaveable { mutableStateOf(false) }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { destination ->
            if (destination != null)
                scope.launch {
                    exportStatus = context.getString(R.string.export_in_progress)
                    exportStatus =
                        try {
                            Diagnostics.export(context, destination, includeCrashReports)
                            context.getString(R.string.export_complete)
                        } catch (e: Exception) {
                            Diagnostics.error("diagnostic_export_failed", e)
                            context.getString(R.string.export_failed)
                        }
                }
        }
    val catalog by model.catalog.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val columns by model.columns.collectAsStateWithLifecycle()
    val rows by model.rows.collectAsStateWithLifecycle()
    val size by model.size.collectAsStateWithLifecycle()
    val fps by model.fps.collectAsStateWithLifecycle()
    val dewarp by model.dewarp.collectAsStateWithLifecycle()
    val selectableFps by model.selectableFps.collectAsStateWithLifecycle()
    val showFps by model.showFps.collectAsStateWithLifecycle()
    val showResolution by model.showResolution.collectAsStateWithLifecycle()
    val recording by model.recording.collectAsStateWithLifecycle()
    val statuses by model.engine.status.collectAsStateWithLifecycle()
    val message by model.engine.messages.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(WallSection.Display) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    var controlsCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    val cameras = catalog.cameras.filter { it.id in selected }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val capacity = rows * columns
    val pageCount = ((cameras.size + capacity - 1) / capacity).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    val visible = if (focused == null) cameras.drop(currentPage * capacity).take(capacity)
        else cameras.filter { it.id == focused }
    var toolsVisible by remember { mutableStateOf(true) }
    var toolsActivity by remember { mutableLongStateOf(0L) }
    val toolsPinned = showSettings || controlsCameraId != null || visible.isEmpty() || !granted
    val actionsVisible = toolsVisible || toolsPinned
    LaunchedEffect(toolsActivity, toolsPinned) {
        if (toolsPinned) toolsVisible = true
        else {
            delay(5_000)
            toolsVisible = false
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message, exportStatus) {
        val result = exportStatus.ifEmpty { message }
        if (result.isNotEmpty()) snackbar.showSnackbar(result)
    }
    BackHandler(focused != null && !showSettings) { focused = null }
    BackHandler(controlsCameraId != null && !showSettings) { controlsCameraId = null }
    BackHandler(showSettings) { if (section != WallSection.Display) section = WallSection.Display else showSettings = false }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) {
            // Observe without consuming the child's click. Preview taps decide
            // whether to reveal tools or open parameters; panels keep tools pinned.
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press)
                        toolsActivity++
                }
            }
        }
        .onPreviewKeyEvent { toolsActivity++; false }) {
        val panelWidth = (maxWidth - 26.dp).coerceAtMost(325.dp)
        val panelHeight = (maxHeight - 78.dp).coerceAtLeast(80.dp)
        when {
            !granted -> Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.allow_camera), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.preview_privacy), Modifier.padding(16.dp))
                Button(onClick = {
                    if (rationale()) showRationale = true
                    else if (requested) settings()
                    else { requested = true; requestPermission() }
                }) {
                    Text(if (requested && !rationale()) stringResource(R.string.permission_settings) else stringResource(R.string.grant_camera))
                }
            }
            visible.isEmpty() -> Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.select_first_camera), style = MaterialTheme.typography.headlineSmall)
                Text(catalog.error ?: if (catalog.cameras.isEmpty()) stringResource(R.string.no_cameras)
                    else stringResource(R.string.select_hint), Modifier.padding(16.dp))
                Button(onClick = { showSettings = true; section = WallSection.Cameras; controlsCameraId = null }) { Text(stringResource(R.string.select_cameras)) }
            }
            else -> {
                val cols = if (focused != null) 1 else columns
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(if (focused != null) 1 else rows) { rowIndex ->
                        val row = visible.drop(rowIndex * cols).take(cols)
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEachIndexed { columnIndex, camera ->
                                key(camera.id, size[camera.id]) {
                                    CameraTile(
                                        camera, statuses[camera.id] ?: TileStatus(), size[camera.id],
                                        fps = fps[camera.id]?.takeIf { it in selectableFps[camera.id].orEmpty() },
                                        fpsOptions = selectableFps[camera.id].orEmpty(), showFps = showFps, showResolution = showResolution,
                                        controlsVisible = controlsCameraId == camera.id, expanded = focused == camera.id,
                                        revealControlsOnly = !actionsVisible,
                                        underGlobal = rowIndex == 0 && columnIndex == cols - 1,
                                        configurationLocked = recording.isNotEmpty(),
                                        dewarp = dewarp[camera.id] ?: model.dewarpFor(camera.id),
                                        onDewarp = { model.setDewarp(camera.id, it) },
                                        onToggleControls = {
                                            toolsActivity++
                                            toolsVisible = true
                                            if (actionsVisible) {
                                                showSettings = false
                                                controlsCameraId = if (controlsCameraId == camera.id) null else camera.id
                                            }
                                        },
                                        recording = camera.id in recording, modifier = Modifier.weight(1f),
                                        onSizeChange = { model.setSize(camera.id, it) }, onFpsChange = { model.setFps(camera.id, it) },
                                        onToggleRecording = { model.toggleRecording(camera.id) },
                                        onAttach = model::attach, onDetach = model::detach,
                                        onFocus = { focused = if (focused == camera.id) null else camera.id },
                                    )
                                }
                            }
                            repeat(cols - row.size) {
                                EmptyCameraTile(Modifier.weight(1f).fillMaxHeight()) {
                                    showSettings = true; section = WallSection.Cameras; controlsCameraId = null
                                }
                            }
                        }
                    }
                }
            }
        }
        if (pageCount > 1 && focused == null) Surface(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp),
            shape = RoundedCornerShape(8.dp), color = WallPalette.Badge,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = currentPage > 0 && recording.isEmpty(), onClick = { page = currentPage - 1 }) { Text(stringResource(R.string.previous_page)) }
                Text(stringResource(R.string.page_count, currentPage + 1, pageCount), style = MaterialTheme.typography.labelSmall)
                TextButton(enabled = currentPage + 1 < pageCount && recording.isEmpty(), onClick = { page = currentPage + 1 }) { Text(stringResource(R.string.next_page)) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp))
        if (showSettings) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { showSettings = false })
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                WallPopover(
                    section,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp).width(panelWidth).heightIn(max = panelHeight),
                    onBack = { section = WallSection.Display }, onClose = { showSettings = false },
                ) {
                    when (section) {
                        WallSection.Display -> DisplaySettingsContent(
                            rows, columns, showFps, showResolution, AppLanguage.current(context).substringBefore('-'), recording.isNotEmpty(),
                            onRows = { model.setRows(it); focused = null; page = 0 },
                            onColumns = { model.setColumns(it); focused = null; page = 0 },
                            onShowFps = model::setShowFps, onShowResolution = model::setShowResolution,
                            onLanguage = { AppLanguage.select(context as Activity, it) }, onSection = { section = it },
                        )
                        WallSection.Cameras -> {
                            Text(pluralStringResource(R.plurals.selected_cameras, selected.size, selected.size), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.concurrent_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                            Box(Modifier.weight(1f, fill = false)) { CameraSelectionList(catalog.cameras, selected.toSet(), model::toggle, model::canSelect) }
                            if (catalog.cameras.isEmpty()) Text(catalog.error ?: stringResource(R.string.no_cameras), style = MaterialTheme.typography.bodySmall)
                            if (selected.any { id -> catalog.cameras.none { it.id == id } }) TextButton(onClick = {
                                selected.filter { id -> catalog.cameras.none { it.id == id } }.forEach(model::toggle)
                            }) { Text(stringResource(R.string.remove_disconnected)) }
                            TextButton(onClick = model::refresh) { Text(stringResource(R.string.refresh_devices)) }
                        }
                        WallSection.Application -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            PanelDropdown(stringResource(R.string.appearance), appearance, listOf("system", "dark", "light"), true, onAppearance) {
                                stringResource(when (it) { "dark" -> R.string.theme_dark; "light" -> R.string.theme_light; else -> R.string.language_system })
                            }
                            OutlinedButton(enabled = recording.isEmpty(), onClick = {
                                model.resetDisplaySettings(); focused = null; page = 0; onAppearance("dark")
                                AppLanguage.select(context as Activity, "")
                            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restore_defaults)) }
                            TextButton(enabled = recording.isEmpty(), onClick = { model.engine.retry(); model.refresh() }) { Text(stringResource(R.string.reconnect)) }
                            TextButton(onClick = { showSettings = false; showDiagnostics = true }) { Text(stringResource(R.string.diagnostics)) }
                            TextButton(onClick = settings) { Text(stringResource(R.string.permission_settings)) }
                        }
                        WallSection.About -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.app_version, context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()), modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        // Draw after the dismiss surface so these controls remain usable while a panel is open.
        AnimatedVisibility(
            visible = actionsVisible,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(horizontal = 14.dp, vertical = 12.dp),
            enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                WallActionButton(R.drawable.ic_wall_settings, stringResource(R.string.settings), showSettings && section != WallSection.Cameras) {
                    val close = showSettings && section == WallSection.Display
                    section = WallSection.Display; showSettings = !close; controlsCameraId = null
                }
                WallActionButton(R.drawable.ic_wall_cameras, stringResource(R.string.cameras), showSettings && section == WallSection.Cameras) {
                    val close = showSettings && section == WallSection.Cameras
                    section = WallSection.Cameras; showSettings = !close; controlsCameraId = null
                }
            }
        }
    }
    if (showRationale)
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.camera_permission)) },
            text = { Text(stringResource(R.string.permission_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        requested = true
                        requestPermission()
                    }
                ) {
                    Text(stringResource(R.string.continue_permission))
                }
            },
        )
    if (showDiagnostics)
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.diagnostics_description))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            includeCrashReports,
                            onCheckedChange = { includeCrashReports = it },
                        )
                        Text(stringResource(R.string.include_dumps))
                    }
                    Text(
                        stringResource(R.string.dumps_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiagnostics = false
                        export.launch("ahd-diagnostics-${System.currentTimeMillis()}.zip")
                    }
                ) {
                    Text(stringResource(R.string.choose_export_location))
                }
            },
            dismissButton = { TextButton(onClick = { showDiagnostics = false }) { Text(stringResource(R.string.cancel)) } },
        )
}
