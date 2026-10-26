package io.github.ahdcamera

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ahdcamera.camera.*

/** Preview presentation; capture and device operations remain behind Surface callbacks. */
@Composable
internal fun CameraTile(
    camera: CameraInfo,
    status: TileStatus,
    size: StreamSize?,
    fps: Pair<Int, Int>?,
    fpsOptions: List<Pair<Int, Int>>,
    showFps: Boolean,
    showResolution: Boolean,
    controlsVisible: Boolean,
    expanded: Boolean = false,
    underGlobal: Boolean = false,
    configurationLocked: Boolean,
    onToggleControls: () -> Unit,
    recording: Boolean,
    modifier: Modifier,
    onSizeChange: (StreamSize) -> Unit,
    onFpsChange: (Pair<Int, Int>) -> Unit,
    onToggleRecording: () -> Unit,
    onAttach: (PreviewTarget) -> Unit,
    onDetach: (String, Surface) -> Unit,
    onFocus: () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxHeight().clipToBounds().background(Color.Black)
        .border(if (controlsVisible) 2.dp else 1.dp, if (recording) Color(0xFFFF6C74) else if (controlsVisible) WallPalette.ControlAccent else Color(0x1F99BDC2))
        .clickable(onClickLabel = stringResource(R.string.camera_controls, camera.id), onClick = onToggleControls)) {
        val compact = maxHeight < 140.dp || maxWidth < 180.dp
        if (size != null) CameraPreview(camera.id, size, onAttach, onDetach, status.streaming, Modifier.fillMaxSize())
        if (compact) Box(Modifier.fillMaxSize().clickable {
            onToggleControls()
            if (!configurationLocked) onFocus()
        })
        if (controlsVisible && !compact) {
            Row(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = if (underGlobal) 68.dp else 10.dp, end = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraActionChip(R.drawable.ic_wall_expand, stringResource(if (expanded) R.string.restore_preview else R.string.expand_preview), false, !configurationLocked, onFocus)
                CameraActionChip(null, stringResource(if (recording) R.string.stop else R.string.record), true, size != null && fpsOptions.isNotEmpty() && status.streaming, onToggleRecording)
            }
            CameraControlsPanel(
                size, fps, camera.sizes.distinct().sortedByDescending { it.area },
                fpsOptions, configurationLocked,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = if (underGlobal) 116.dp else 58.dp, end = 8.dp, start = 8.dp, bottom = 8.dp)
                    .widthIn(max = 366.dp).heightIn(max = (maxHeight - if (underGlobal) 132.dp else 74.dp).coerceAtLeast(40.dp)),
                onSize = onSizeChange, onFps = onFpsChange, onClose = onToggleControls,
            )
        }
        val errorText = if (!status.streaming) {
            if (size == null) stringResource(R.string.no_resolution, camera.facing)
            else status.message.ifEmpty { stringResource(R.string.waiting_preview) }
        } else null
        val fpsText = if (showFps && status.streaming) stringResource(R.string.compact_frame_rate, status.fps) else null
        val resolutionText = if (showResolution && size != null) stringResource(R.string.grid_dimensions, size.width, size.height) else null
        val badge = listOfNotNull(errorText, fpsText, resolutionText).joinToString(" · ")
        if (badge.isNotEmpty()) Text(
            badge,
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp)
                .background(WallPalette.Badge, CircleShape).border(1.dp, Color(0x596A7C85), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
            color = Color(0xFFF3FAFB), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )

    }
}

@Composable
private fun CameraPreview(id: String, size: StreamSize, onAttach: (PreviewTarget) -> Unit, onDetach: (String, Surface) -> Unit, streaming: Boolean, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        CameraSurface(id, size, onAttach, onDetach, Modifier.fillMaxSize())
        // Retain the Surface for reconnect while hiding stale frames after errors or privacy blocking.
        if (!streaming) Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
private fun CameraSurface(id: String, size: StreamSize, onAttach: (PreviewTarget) -> Unit, onDetach: (String, Surface) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        // Center crop: preserve aspect ratio and fill the tile, including non-16:9 grids.
        val aspect = size.width.toFloat() / size.height
        val contentWidth = maxOf(maxWidth, maxHeight * aspect)
        AndroidView(
            modifier = Modifier.requiredSize(contentWidth, contentWidth / aspect),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.setFixedSize(size.width, size.height)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            onAttach(PreviewTarget(id, holder.surface, size))
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { onDetach(id, holder.surface) }
                    })
                }
            },
            onRelease = { view -> onDetach(id, view.holder.surface) },
        )
    }
}

@Composable
internal fun EmptyCameraTile(modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(Color(0xFF0B1115)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        OutlinedIconButton(onClick = onClick, modifier = Modifier.size(52.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF52737A))) {
            Text("+", color = Color(0xFF7FDFCE), style = MaterialTheme.typography.headlineMedium)
        }
        Text(stringResource(R.string.add_camera_hint), Modifier.padding(top = 13.dp), color = Color(0xFF6E838F), style = MaterialTheme.typography.bodySmall)
    }
}
