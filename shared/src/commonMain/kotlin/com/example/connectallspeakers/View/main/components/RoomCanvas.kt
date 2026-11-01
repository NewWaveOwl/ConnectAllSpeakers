package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.audio.OutputAvailability
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import connectallspeakers.shared.generated.resources.Res
import connectallspeakers.shared.generated.resources.grid_dot_render
import connectallspeakers.shared.generated.resources.listening_halo
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

// Layout settings
private val SpeakerCardWidth = 170.dp
private val SpeakerCardHeight = 68.dp
private val GridSpacing = 24.dp
private val CanvasCorner = 16.dp

/** Dotted room with normalized, bounded card travel and keyboard movement.
 * Zoom scales cards, grid and room markers; normalized positions remain visible without panning.
 * @param room Active session room.
 * @param testingSpeakerId Sample receiving a temporary visual-only highlight.
 * @param onAction Screen intent callback.
 * @param modifier Canvas size from the responsive screen.
 */
@Composable
fun RoomCanvas(room: RoomSetup, testingSpeakerId: String?, onAction: (MainAction) -> Unit, modifier: Modifier = Modifier) {
    val dot = painterResource(Res.drawable.grid_dot_render)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(CanvasCorner)).background(C.Ink)) {
        val cardWidth = (SpeakerCardWidth * room.zoom).coerceAtMost(maxWidth)
        val cardHeight = SpeakerCardHeight * room.zoom
        val travelX = (maxWidth - cardWidth).value.coerceAtLeast(1f)
        val travelY = (maxHeight - cardHeight).value.coerceAtLeast(1f)
        val stepX = GridSpacing.value * room.zoom / travelX
        val stepY = GridSpacing.value * room.zoom / travelY
        Canvas(Modifier.fillMaxSize()) {
            val grid = GridSpacing.toPx() * room.zoom
            val dotSize = Size(2.dp.toPx() * room.zoom, 2.dp.toPx() * room.zoom)
            for (x in 1..(size.width / grid).toInt()) for (y in 1..(size.height / grid).toInt()) {
                translate(x * grid, y * grid) { with(dot) { draw(dotSize) } }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 34.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(210.dp * room.zoom, 8.dp * room.zoom).background(C.Sky, RoundedCornerShape(4.dp)))
            Text("FRONT OF ROOM", color = C.Sand, fontSize = (10 * room.zoom).sp, lineHeight = (14 * room.zoom).sp)
        }
        Box(Modifier.align(Alignment.Center).offset(y = 32.dp).size(144.dp * room.zoom), contentAlignment = Alignment.Center) {
            Image(painterResource(Res.drawable.listening_halo), null, Modifier.fillMaxSize())
            Box(Modifier.size(56.dp * room.zoom).background(C.Plum, CircleShape).semantics { contentDescription = "Listening position" },
                contentAlignment = Alignment.Center) { DesignIcon(Glyph.Headphones, C.Sand, 26.dp * room.zoom) }
        }
        room.speakers.forEach { speaker -> key(speaker.id) {
            SpeakerCard(speaker, speaker.id == room.selectedSpeakerId, speaker.id == testingSpeakerId,
                cardWidth, cardHeight, travelX, travelY, stepX, stepY, onAction)
        } }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).background(C.Plum, RoundedCornerShape(10.dp))
            .padding(horizontal = 22.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignIcon(Glyph.Drag, C.Sand, 18.dp)
            Text("Drag speakers to arrange your room", color = C.Sand, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onAction(MainAction.ToggleSnap) }, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { stateDescription = if (room.snapToGrid) "On" else "Off" }) {
                Text("Snap to grid  ·  ${if (room.snapToGrid) "On" else "Off"}", color = C.Sand, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton({ onAction(MainAction.Zoom(-.25f)) }, enabled = room.zoom > .75f,
                modifier = Modifier.size(32.dp).semantics { contentDescription = "Zoom out" }, contentPadding = PaddingValues(0.dp)) { Text("−", color = C.Sand) }
            Text("${(room.zoom * 100).roundToInt()}%", color = C.Sand, style = MaterialTheme.typography.labelMedium)
            TextButton({ onAction(MainAction.Zoom(.25f)) }, enabled = room.zoom < 1.5f,
                modifier = Modifier.size(32.dp).semantics { contentDescription = "Zoom in" }, contentPadding = PaddingValues(0.dp)) { Text("+", color = C.Sand) }
        }
    }
}

/** One focusable sample card. Arrow keys move it one grid step without changing channel.
 * @param speaker Immutable sample.
 * @param selected Whether the inspector targets this sample.
 * @param testing Whether to show a temporary sage highlight.
 * @param width Effective zoomed card width in dp.
 * @param height Effective zoomed card height in dp.
 * @param travelX Available horizontal travel in dp.
 * @param travelY Available vertical travel in dp.
 * @param stepX Horizontal grid step in normalized travel.
 * @param stepY Vertical grid step in normalized travel.
 * @param onAction User intent callback.
 */
@Composable
private fun SpeakerCard(speaker: RoomSpeaker, selected: Boolean, testing: Boolean, width: Dp, height: Dp,
    travelX: Float, travelY: Float, stepX: Float, stepY: Float, onAction: (MainAction) -> Unit) {
    val current by rememberUpdatedState(speaker)
    val dispatch by rememberUpdatedState(onAction)
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val scale = height / SpeakerCardHeight
    val accent = if (speaker.availability == OutputAvailability.Unavailable) MaterialTheme.colorScheme.error
        else if (speaker.channel == SpeakerChannel.Right) C.Sky else C.Teal
    val status = when {
        speaker.availability == OutputAvailability.Unavailable -> "Unavailable"
        speaker.availability == OutputAvailability.Unknown -> "Availability unknown"
        else -> speaker.channel.caption
    }
    Row(Modifier.offset { IntOffset((speaker.position.x * travelX * density).roundToInt(), (speaker.position.y * travelY * density).roundToInt()) }
        .size(width, height).shadow(if (selected) 10.dp else 0.dp, RoundedCornerShape(13.dp))
        .background(if (testing) C.Sage else Color.White, RoundedCornerShape(13.dp))
        .border(if (testing) 4.dp else 2.dp, if (testing) C.Sage else accent, RoundedCornerShape(13.dp))
        .semantics { contentDescription = "${if (speaker.isSample) "Sample speaker" else "Windows output"} ${speaker.name}, ${speaker.channel.label}. $status. Use arrow keys to move."; this.selected = selected }
        .onPreviewKeyEvent { event ->
            val delta = when (event.key) { Key.DirectionLeft -> RoomPosition(-stepX, 0f); Key.DirectionRight -> RoomPosition(stepX, 0f)
                Key.DirectionUp -> RoomPosition(0f, -stepY); Key.DirectionDown -> RoomPosition(0f, stepY); else -> null }
            if (delta == null || event.type != KeyEventType.KeyDown) false else {
                dispatch(MainAction.SelectSpeaker(current.id))
                dispatch(MainAction.MoveSpeaker(current.id, RoomPosition(current.position.x + delta.x, current.position.y + delta.y)))
                dispatch(MainAction.FinishMove(current.id, stepX, stepY)); true
            }
        }
        .clickable { onAction(MainAction.SelectSpeaker(speaker.id)) }
        .pointerInput(speaker.id, travelX, travelY, stepX, stepY, density) {
            var dragPosition = current.position
            detectDragGestures(
                onDragStart = { dragPosition = current.position; dispatch(MainAction.SelectSpeaker(current.id)) },
                onDragEnd = { dispatch(MainAction.FinishMove(current.id, stepX, stepY)) },
                onDragCancel = { dispatch(MainAction.FinishMove(current.id, stepX, stepY)) },
            ) { change, amount ->
                change.consume()
                dragPosition = RoomPosition((dragPosition.x + amount.x / density / travelX).coerceIn(0f, 1f),
                    (dragPosition.y + amount.y / density / travelY).coerceIn(0f, 1f))
                dispatch(MainAction.MoveSpeaker(current.id, dragPosition))
            }
        }.padding(12.dp * scale), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp * scale)) {
        DesignIcon(Glyph.Speaker, accent, 28.dp * scale)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp * scale)) {
            Text(speaker.name.ifBlank { "Unnamed speaker" }, fontSize = (13 * scale).sp, lineHeight = (18 * scale).sp,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = if (speaker.availability == OutputAvailability.Unavailable) accent else C.Muted,
                fontSize = (11 * scale).sp, lineHeight = (15 * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
