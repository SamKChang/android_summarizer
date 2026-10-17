package com.gist.app.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class BubbleVisualState {
    IDLE,
    CAPTURING,
    STREAMING,
    ERROR
}

val GistPurple = Color(0xFF7C6EE6)
val GistPurpleLight = Color(0xFFEEEBFF)

/**
 * 56 dp circular FAB-like bubble with the Gist 4-point star glyph (§3.2):
 * - 85% opacity when idle
 * - States: idle · capturing (progress ring) · streaming (pulsing dot badge) · error (red dot)
 * - Supports tap to capture, drag to reposition + snap to nearest horizontal edge
 */
@Composable
fun Bubble(
    state: BubbleVisualState,
    sizeDp: Dp = 56.dp,
    idleOpacity: Float = 0.85f,
    onTap: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val alpha = if (state == BubbleVisualState.IDLE) idleOpacity else 1.0f

    val infiniteTransition = rememberInfiniteTransition(label = "streaming_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = modifier
            .size(sizeDp + 8.dp)
            .semantics { contentDescription = "Gist Summarize Bubble" }
            .testTag("gist_bubble")
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Main circular purple bubble
        Box(
            modifier = Modifier
                .size(sizeDp)
                .alpha(alpha)
                .shadow(elevation = 8.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(GistPurple),
            contentAlignment = Alignment.Center
        ) {
            // 4-point star glyph matching gist_demo_video.mp4
            Canvas(modifier = Modifier.size(sizeDp * 0.48f)) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f
                val starPath = Path().apply {
                    moveTo(cx, 0f)
                    cubicTo(cx + w * 0.06f, cy - h * 0.06f, cx + w * 0.06f, cy - h * 0.06f, w, cy)
                    cubicTo(cx + w * 0.06f, cy + h * 0.06f, cx + w * 0.06f, cy + h * 0.06f, cx, h)
                    cubicTo(cx - w * 0.06f, cy + h * 0.06f, cx - w * 0.06f, cy + h * 0.06f, 0f, cy)
                    cubicTo(cx - w * 0.06f, cy - h * 0.06f, cx - w * 0.06f, cy - h * 0.06f, cx, 0f)
                    close()
                }
                drawPath(path = starPath, color = Color.White)
            }

            if (state == BubbleVisualState.CAPTURING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(sizeDp - 4.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            }
        }

        // Badge for STREAMING (pulsing dot) or ERROR (red dot) (§3.2)
        if (state == BubbleVisualState.STREAMING || state == BubbleVisualState.ERROR) {
            val badgeColor = if (state == BubbleVisualState.ERROR) Color(0xFFE53935) else Color(0xFF00E676)
            val badgeSize = if (state == BubbleVisualState.STREAMING) (12.dp * pulseScale) else 12.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
        }
    }
}

@Composable
fun DismissTargetOverlay(
    isHovered: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isHovered) Color(0xFFE53935) else Color(0xCC222222)
    Box(
        modifier = modifier
            .size(if (isHovered) 68.dp else 56.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Dismiss Gist Bubble",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}
