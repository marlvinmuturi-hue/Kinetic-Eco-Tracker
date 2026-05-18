package Kinetic_Eco.Tracker.ui.components

import android.view.MotionEvent
import android.widget.NumberPicker
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import Kinetic_Eco.Tracker.R

private val WheelHeight = 220.dp

/**
 * Day-count selector as a native [NumberPicker] so it nests safely inside
 * [androidx.compose.foundation.verticalScroll] and [androidx.compose.foundation.lazy.LazyColumn].
 * Compose [VerticalPager] inside those parents often crashes due to nested vertical scroll.
 *
 * Parent scrollables (e.g. [androidx.compose.foundation.lazy.LazyColumn]) otherwise steal vertical
 * drags; we ask ancestors to stop intercepting while the user moves on the wheel.
 */
@Composable
fun RollingDaysWheelPicker(
    selectedDays: Int,
    onDaysSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 1..366,
    enabled: Boolean = true
) {
    val latestOnDays by rememberUpdatedState(onDaysSelected)
    val desc = stringResource(R.string.period_wheel_accessibility, selectedDays)

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(WheelHeight)
            .semantics { contentDescription = desc },
        factory = { ctx ->
            NumberPicker(ctx).apply {
                minValue = range.first
                maxValue = range.last
                wrapSelectorWheel = false
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                setOnValueChangedListener { _, _, newVal -> latestOnDays(newVal) }
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            var p = v.parent
                            while (p != null) {
                                p.requestDisallowInterceptTouchEvent(true)
                                p = p.parent
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            var p = v.parent
                            while (p != null) {
                                p.requestDisallowInterceptTouchEvent(false)
                                p = p.parent
                            }
                        }
                    }
                    false
                }
            }
        },
        update = { np ->
            if (np.minValue != range.first || np.maxValue != range.last) {
                np.setOnValueChangedListener(null)
                np.minValue = range.first
                np.maxValue = range.last
                np.setOnValueChangedListener { _, _, newVal -> latestOnDays(newVal) }
            }
            np.isEnabled = enabled
            val coerced = selectedDays.coerceIn(range.first, range.last)
            if (np.value != coerced) {
                np.setOnValueChangedListener(null)
                np.value = coerced
                np.setOnValueChangedListener { _, _, newVal -> latestOnDays(newVal) }
            }
        }
    )
}
