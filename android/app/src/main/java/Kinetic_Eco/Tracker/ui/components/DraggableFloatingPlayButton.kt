package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.ui.theme.Red500
import kotlin.math.roundToInt

/** Pixels of movement above this counts as a drag (save position), not a tap. */
private const val DRAG_THRESHOLD_PX = 24f

/**
 * FAB with reliable tap (start / stop dialog) and optional drag to reposition.
 * Drag is handled on the outer [Box] so it does not compete with [FloatingActionButton]'s click.
 */
@Composable
fun DraggableFloatingPlayButton(
    isTracking: Boolean,
    onClick: () -> Unit,
    onShowStopDialog: () -> Unit,
    onSavePosition: (Float, Float) -> Unit,
    savedPosition: Pair<Float, Float>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val containerWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val containerHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val fabSize = 56.dp
    val buttonSizePx = with(density) { fabSize.toPx() }

    var offsetX by remember { mutableFloatStateOf(savedPosition.first) }
    var offsetY by remember { mutableFloatStateOf(savedPosition.second) }
    LaunchedEffect(savedPosition) {
        offsetX = savedPosition.first
        offsetY = savedPosition.second
    }

    fun clampedX() = offsetX.coerceIn(
        buttonSizePx / (2 * containerWidthPx),
        1f - buttonSizePx / (2 * containerWidthPx)
    )
    fun clampedY() = offsetY.coerceIn(
        buttonSizePx / (2 * containerHeightPx),
        1f - buttonSizePx / (2 * containerHeightPx)
    )

    var dragAccumPx by remember { mutableFloatStateOf(0f) }

    val animOffsetX by animateFloatAsState(targetValue = clampedX(), label = "offsetX")
    val animOffsetY by animateFloatAsState(targetValue = clampedY(), label = "offsetY")

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (animOffsetX * containerWidthPx - buttonSizePx / 2).roundToInt(),
                    (animOffsetY * containerHeightPx - buttonSizePx / 2).roundToInt()
                )
            }
            // Drag on the wrapper; FAB below handles tap/stop with Material click semantics.
            .pointerInput(containerWidthPx, containerHeightPx) {
                detectDragGestures(
                    onDragStart = { dragAccumPx = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumPx += kotlin.math.hypot(dragAmount.x, dragAmount.y)
                        offsetX = (offsetX + dragAmount.x / containerWidthPx).coerceIn(0f, 1f)
                        offsetY = (offsetY + dragAmount.y / containerHeightPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        offsetX = clampedX()
                        offsetY = clampedY()
                        if (dragAccumPx >= DRAG_THRESHOLD_PX) {
                            onSavePosition(offsetX, offsetY)
                        }
                    },
                    onDragCancel = {
                        offsetX = clampedX()
                        offsetY = clampedY()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        FloatingActionButton(
            onClick = {
                if (isTracking) onShowStopDialog() else onClick()
            },
            modifier = Modifier.size(fabSize),
            containerColor = (if (isTracking) Red500 else colorScheme.primary).copy(alpha = 0.75f),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 6.dp,
                pressedElevation = 12.dp
            )
        ) {
            if (isTracking) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.stop),
                    modifier = Modifier.size(36.dp),
                    tint = colorScheme.onPrimary
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = stringResource(R.string.start),
                    modifier = Modifier.size(36.dp),
                    tint = colorScheme.onPrimary
                )
            }
        }
    }
}
