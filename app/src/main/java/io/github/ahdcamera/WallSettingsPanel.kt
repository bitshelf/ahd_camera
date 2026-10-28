package io.github.ahdcamera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Tokens mirror docs/AHD_Camera_Interactive_Prototype.html; no camera data lives here. */
internal object WallPalette {
    val Accent = Color(0xFF14D4BD)
    val ControlAccent = Accent
    val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    val Border: Color @Composable get() = MaterialTheme.colorScheme.outline
    val Field: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val Badge = Color(0xC7050B0F)
}

internal enum class WallSection { Display, Cameras, Application, About }

@Composable
internal fun WallActionButton(icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick, modifier = Modifier.size(48.dp), shape = CircleShape,
        border = BorderStroke(1.dp, if (active) WallPalette.Accent else Color(0x7896B9C5)),
        colors = IconButtonDefaults.outlinedIconButtonColors(
            containerColor = if (active) Color(0xE6082D2C) else Color(0xD4060F14),
            contentColor = if (active) WallPalette.Accent else Color(0xFFE7F1F4),
        ),
    ) { Icon(painterResource(icon), label, Modifier.size(19.dp)) }
}

/** Shared shell keeps all global sections anchored to the same screen edge. */
@Composable
internal fun WallPopover(
    section: WallSection, modifier: Modifier = Modifier,
    onBack: () -> Unit, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.pointerInput(Unit) { detectTapGestures {} },
        shape = RoundedCornerShape(16.dp), color = WallPalette.Panel,
        border = BorderStroke(1.dp, WallPalette.Border), shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PanelClose(onClose)
            }
            if (section != WallSection.Display) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.back_to_settings)) }
                Text(stringResource(when (section) {
                    WallSection.Cameras -> R.string.cameras
                    WallSection.Application -> R.string.application_settings
                    else -> R.string.about
                }), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            content()
        }
    }
}

@Composable
internal fun DisplaySettingsContent(
    rows: Int, columns: Int, showFps: Boolean, showResolution: Boolean, language: String,
    recording: Boolean,
    onRows: (Int) -> Unit, onColumns: (Int) -> Unit,
    onShowFps: (Boolean) -> Unit, onShowResolution: (Boolean) -> Unit,
    onLanguage: (String) -> Unit, onSection: (WallSection) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(painterResource(R.drawable.ic_wall_display), null, Modifier.size(19.dp), tint = WallPalette.Accent)
            Text(stringResource(R.string.display_settings), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
        if (recording) Text(stringResource(R.string.recording_settings_locked), style = MaterialTheme.typography.bodySmall)
        PanelDropdown(stringResource(R.string.layout_rows), rows, (1..9).toList(), !recording, onRows) { it.toString() }
        PanelDropdown(stringResource(R.string.layout_columns), columns, (1..9).toList(), !recording, onColumns) { it.toString() }
        PanelDropdown(stringResource(R.string.language), language, listOf("", "zh", "en"), !recording, onLanguage) {
            stringResource(when (it) { "en" -> R.string.language_english; "zh" -> R.string.language_chinese; else -> R.string.language_system })
        }
        PanelSwitch(stringResource(R.string.show_frame_rate), showFps, onShowFps)
        PanelSwitch(stringResource(R.string.show_resolution), showResolution, onShowResolution)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = WallPalette.Border)
        PanelLink(R.drawable.ic_wall_cameras, stringResource(R.string.cameras)) { onSection(WallSection.Cameras) }
        PanelLink(R.drawable.ic_wall_settings, stringResource(R.string.application_settings)) { onSection(WallSection.Application) }
        PanelLink(R.drawable.ic_wall_about, stringResource(R.string.about)) { onSection(WallSection.About) }
    }
}

@Composable
internal fun PanelClose(onClose: () -> Unit) {
    IconButton(onClick = onClose, modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
        Icon(painterResource(R.drawable.ic_wall_close), stringResource(R.string.close), Modifier.size(17.dp))
    }
}

@Composable
internal fun <T> PanelDropdown(label: String, value: T, options: List<T>, enabled: Boolean, onSelected: (T) -> Unit, icon: Int? = null, accented: Boolean = false, text: @Composable (T) -> String) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints {
    val fieldWidth = (maxWidth * if (accented) 0.66f else 0.54f).coerceAtMost(215.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = if (accented) 48.dp else 47.dp)
        .background(if (accented) Color.White.copy(alpha = 0.025f) else Color.Transparent, RoundedCornerShape(10.dp))
        .padding(horizontal = if (accented) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.padding(end = 8.dp).size(18.dp), tint = WallPalette.ControlAccent)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.width(fieldWidth)) {
            OutlinedButton(
                onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                shape = RoundedCornerShape(if (accented) 11.dp else 8.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = WallPalette.Field, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, WallPalette.Border),
            ) {
                Text(text(value), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.dropdown_indicator))
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options.forEach { item -> DropdownMenuItem(text = { Text(text(item)) }, onClick = { expanded = false; onSelected(item) }) }
            }
        }
    }
}
}

@Composable
private fun PanelSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF07AE99), checkedThumbColor = Color.White))
    }
}

@Composable
private fun PanelLink(icon: Int, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(16.dp))
        Text(label, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
        Icon(painterResource(R.drawable.ic_wall_chevron), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = WallPalette.Border)
}

/** A small per-camera popover using the same fields and palette as global settings. */
@Composable
internal fun CameraControlsPanel(
    size: io.github.ahdcamera.camera.StreamSize?, fps: Pair<Int, Int>?,
    sizes: List<io.github.ahdcamera.camera.StreamSize>, rates: List<Pair<Int, Int>>,
    locked: Boolean,
    dewarp: io.github.ahdcamera.processing.DewarpSettings,
    onDewarp: (io.github.ahdcamera.processing.DewarpSettings) -> Unit,
    modifier: Modifier = Modifier,
    onSize: (io.github.ahdcamera.camera.StreamSize) -> Unit, onFps: (Pair<Int, Int>) -> Unit,
    onClose: () -> Unit,
) {
    var showAdjustment by remember { mutableStateOf(false) }
    Surface(
        modifier.pointerInput(Unit) { detectTapGestures {} },
        shape = RoundedCornerShape(14.dp), color = WallPalette.Panel,
        border = BorderStroke(1.dp, WallPalette.Border), shadowElevation = 12.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.picture_settings), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                PanelClose(onClose)
            }
            Spacer(Modifier.height(2.dp))
            PanelDropdown(stringResource(R.string.resolution), size, sizes, !locked && sizes.isNotEmpty(), { it?.let(onSize) }, accented = true) {
                if (it == null) stringResource(R.string.capability_not_reported) else stringResource(R.string.grid_dimensions, it.width, it.height)
            }
            PanelDropdown(stringResource(R.string.frame_rate_label), fps, rates, !locked && rates.isNotEmpty(), { it?.let(onFps) }, accented = true) {
                when { it == null -> stringResource(R.string.reading_frame_rate)
                    it.first == it.second -> stringResource(R.string.frame_rate, it.second)
                    else -> stringResource(R.string.frame_rate_range, it.first, it.second) }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = WallPalette.Border)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dewarp_approximate), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (dewarp.enabled) IconButton(onClick = { showAdjustment = !showAdjustment }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_wall_chevron), stringResource(if (showAdjustment) R.string.hide_dewarp_adjustment else R.string.dewarp_adjustment), Modifier.size(16.dp))
                }
                Switch(dewarp.enabled, { onDewarp(dewarp.copy(enabled = it)) }, enabled = !locked)
            }
            if (dewarp.enabled && showAdjustment) {
                Text(stringResource(R.string.dewarp_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.dewarp_strength, (dewarp.strength * 100).toInt()))
                Slider(dewarp.strength, { onDewarp(dewarp.copy(strength = it)) }, enabled = !locked)
                Text(stringResource(R.string.dewarp_lens_fov, dewarp.lensFov.toInt()))
                Slider(dewarp.lensFov, { onDewarp(dewarp.copy(lensFov = it)) }, valueRange = 100f..220f, enabled = !locked)
                Text(stringResource(R.string.dewarp_output_fov, dewarp.outputFov.toInt()))
                Slider(dewarp.outputFov, { onDewarp(dewarp.copy(outputFov = it)) }, valueRange = 50f..120f, enabled = !locked)
            }

        }
    }
}

@Composable
internal fun CameraActionChip(icon: Int?, label: String, recordingIndicator: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp), shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(1.dp, WallPalette.ControlAccent.copy(alpha = 0.7f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = WallPalette.Badge, contentColor = Color(0xFFE7F1F4)),
    ) {
        if (recordingIndicator) Box(Modifier.size(12.dp).background(Color(0xFFFF3D45), CircleShape))
        else if (icon != null) Icon(painterResource(icon), null, Modifier.size(16.dp), tint = WallPalette.ControlAccent)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}
