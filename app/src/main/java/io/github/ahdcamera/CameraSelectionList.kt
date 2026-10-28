package io.github.ahdcamera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import io.github.ahdcamera.camera.CameraInfo
import kotlinx.coroutines.launch

@Composable
fun CameraSelectionList(cameras: List<CameraInfo>, selected: Set<String>, onToggle: (String) -> Unit, canSelect: (String) -> Boolean = { true }) {
    val state = rememberLazyListState()
    Box(Modifier.heightIn(max = 420.dp)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().padding(end = 16.dp)) {
            items(cameras, key = { it.id }, contentType = { "camera" }) { camera ->
                val enabled = camera.id in selected || canSelect(camera.id)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled) { onToggle(camera.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(camera.id in selected, enabled = enabled, onCheckedChange = { onToggle(camera.id) })
                    Column {
                        Text(camera.id, fontWeight = FontWeight.Bold)
                        Text(camera.facing, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Box(Modifier.matchParentSize()) {
            CameraScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(16.dp))
        }
    }
}

/** Always visible when the list overflows; the track supports tapping and dragging. */
@Composable
private fun CameraScrollbar(state: LazyListState, modifier: Modifier) {
    val overflow by remember { derivedStateOf { state.canScrollBackward || state.canScrollForward } }
    if (!overflow) return
    val scope = rememberCoroutineScope()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val thumb = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .pointerInput(state) {
                fun seek(y: Float) {
                    val count = state.layoutInfo.totalItemsCount
                    if (count == 0 || size.height == 0) return
                    val index = ((y / size.height).coerceIn(0f, 1f) * (count - 1)).toInt()
                    scope.launch { state.scrollToItem(index) }
                }
                detectTapGestures { seek(it.y) }
            }
            .pointerInput(state) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    val count = state.layoutInfo.totalItemsCount
                    if (count > 0 && size.height > 0) {
                        val index = ((change.position.y / size.height).coerceIn(0f, 1f) * (count - 1)).toInt()
                        scope.launch { state.scrollToItem(index) }
                    }
                }
            }
    ) {
        val info = state.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val visible = info.visibleItemsInfo
        if (info.totalItemsCount == 0 || viewport <= 0 || visible.isEmpty()) return@Canvas
        val visibleCount = visible.sumOf { item ->
            val overlap = (minOf(item.offset + item.size, info.viewportEndOffset) -
                maxOf(item.offset, info.viewportStartOffset)).coerceAtLeast(0)
            overlap.toDouble() / item.size.coerceAtLeast(1)
        }.toFloat()
        val first = visible.first()
        val position = first.index +
            (info.viewportStartOffset - first.offset).toFloat() / first.size.coerceAtLeast(1)
        val height = (size.height * visibleCount / info.totalItemsCount).coerceIn(32.dp.toPx().coerceAtMost(size.height), size.height)
        val progress = (position / (info.totalItemsCount - visibleCount).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        val width = 4.dp.toPx()
        val x = (size.width - width) / 2
        drawRoundRect(track, Offset(x, 0f), Size(width, size.height), CornerRadius(width))
        drawRoundRect(thumb, Offset(x, progress * (size.height - height)), Size(width, height), CornerRadius(width))
    }
}
