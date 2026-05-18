package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private data class WeekDayCell(
    val letter: String,
    val dayOfMonth: Int,
    val isToday: Boolean,
    val dateKey: String
)

@Composable
private fun rememberRollingWeekCells(): List<WeekDayCell> {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    val key = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR)
    return remember(key) {
        buildList {
            for (offset in 6 downTo 0) {
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, -offset)
                val dow = c.get(Calendar.DAY_OF_WEEK)
                val letter = when (dow) {
                    Calendar.SUNDAY -> "S"
                    Calendar.MONDAY -> "M"
                    Calendar.TUESDAY -> "T"
                    Calendar.WEDNESDAY -> "W"
                    Calendar.THURSDAY -> "T"
                    Calendar.FRIDAY -> "F"
                    Calendar.SATURDAY -> "S"
                    else -> "?"
                }
                add(
                    WeekDayCell(
                        letter = letter,
                        dayOfMonth = c.get(Calendar.DAY_OF_MONTH),
                        isToday = offset == 0,
                        dateKey = dateFormat.format(c.time)
                    )
                )
            }
        }
    }
}

enum class AnalysisPeriodSelectorMode {
    /** Leaderboard: "Last 7 days" chip + 30/90 slider (+ optional all time). */
    Default,
    /** AI analysis: week strip + manual rolling day count (replaces chip + slider). */
    AiAnalysis
}

/**
 * Rolling last 7 days as a week strip (tap a day = single calendar-day mode).
 * [AnalysisPeriodSelectorMode.Default]: optional "Last 7 days" chip, 30/90 slider, optional "All time".
 * [AnalysisPeriodSelectorMode.AiAnalysis]: scroll wheel for day count + quick preset chips (no chip/slider).
 */
@Composable
fun AnalysisPeriodSelector(
    mode: AnalysisPeriodSelectorMode = AnalysisPeriodSelectorMode.Default,
    rollingDays: Int,
    allTimeSelected: Boolean,
    selectedSessionDateKey: String?,
    includeAllTimeOption: Boolean,
    onRollingWeekSelected: () -> Unit = {},
    onLongRangeDaysSelected: (Int) -> Unit = {},
    onAiRollingDaysChanged: (Int) -> Unit = {},
    onAllTimeSelected: (() -> Unit)?,
    onSessionDaySelected: (String) -> Unit,
    /** AI mode only: tap the selected day again to clear single-day selection (optional). */
    onClearSessionDaySelection: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val cells = rememberRollingWeekCells()
    val weekMode = rollingDays == 7 && !allTimeSelected && selectedSessionDateKey == null
    val singleDayMode = selectedSessionDateKey != null

    var sliderPosition by remember { mutableFloatStateOf(30f) }
    if (mode == AnalysisPeriodSelectorMode.Default) {
        LaunchedEffect(rollingDays, allTimeSelected) {
            sliderPosition = when {
                allTimeSelected -> 30f
                rollingDays >= 90 -> 90f
                else -> 30f
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.period_this_week),
            style = MaterialTheme.typography.labelLarge,
            color = colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (weekMode || singleDayMode) 2.dp else 1.dp,
                    color = when {
                        weekMode -> colorScheme.primary
                        singleDayMode -> colorScheme.tertiary
                        else -> colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .background(
                    color = when {
                        weekMode -> colorScheme.primaryContainer.copy(alpha = 0.35f)
                        singleDayMode -> colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                        else -> colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            cells.forEach { cell ->
                val selected = selectedSessionDateKey == cell.dateKey
                Column(
                    modifier = Modifier
                        .widthIn(min = 28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) colorScheme.primary.copy(alpha = 0.22f)
                            else Color.Transparent
                        )
                        .clickable(enabled = !allTimeSelected) {
                            if (mode == AnalysisPeriodSelectorMode.AiAnalysis &&
                                onClearSessionDaySelection != null &&
                                selectedSessionDateKey == cell.dateKey
                            ) {
                                onClearSessionDaySelection()
                            } else {
                                onSessionDaySelected(cell.dateKey)
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = cell.letter,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Text(
                        text = cell.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            selected -> colorScheme.primary
                            cell.isToday -> colorScheme.primary
                            else -> colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (mode == AnalysisPeriodSelectorMode.Default) {
            FilterChip(
                selected = weekMode,
                onClick = onRollingWeekSelected,
                label = {
                    Text(
                        stringResource(R.string.period_rolling_7_days),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }

        if (mode == AnalysisPeriodSelectorMode.AiAnalysis) {
            val singleDayPicked = selectedSessionDateKey != null
            Text(
                text = stringResource(R.string.period_longer_range),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = stringResource(R.string.period_rolling_days_label),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant
            )
            RollingDaysWheelPicker(
                selectedDays = rollingDays.coerceIn(1, 366),
                onDaysSelected = onAiRollingDaysChanged,
                enabled = !allTimeSelected && !singleDayPicked,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (singleDayPicked) {
                    stringResource(R.string.period_single_day_rolling_locked_hint)
                } else {
                    stringResource(R.string.period_rolling_days_helper)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .alpha(if (allTimeSelected) 0.45f else 1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(7, 14, 30, 60, 90).forEach { d ->
                    FilterChip(
                        selected = rollingDays == d && selectedSessionDateKey == null && !allTimeSelected,
                        onClick = { onAiRollingDaysChanged(d) },
                        enabled = !allTimeSelected && !singleDayPicked,
                        label = {
                            Text(
                                stringResource(R.string.period_preset_days, d),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }

        if (mode == AnalysisPeriodSelectorMode.Default) {
            Text(
                text = stringResource(R.string.period_longer_range),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (allTimeSelected) 0.45f else 1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.timeframe_30_days),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 48.dp)
                )
                Slider(
                    value = sliderPosition,
                    onValueChange = { if (!allTimeSelected) sliderPosition = it },
                    onValueChangeFinished = {
                        if (allTimeSelected) return@Slider
                        val snapped = if (sliderPosition < 60f) 30f else 90f
                        sliderPosition = snapped
                        onLongRangeDaysSelected(snapped.toInt())
                    },
                    valueRange = 30f..90f,
                    enabled = !allTimeSelected,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = colorScheme.primary,
                        activeTrackColor = colorScheme.primary,
                        inactiveTrackColor = colorScheme.surfaceVariant
                    )
                )
                Text(
                    text = stringResource(R.string.timeframe_90_days),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 48.dp),
                    textAlign = TextAlign.End
                )
            }
        }

        if (includeAllTimeOption && onAllTimeSelected != null) {
            FilterChip(
                selected = allTimeSelected,
                onClick = onAllTimeSelected,
                label = { Text(stringResource(R.string.leaderboard_all_time)) }
            )
        }
    }
}
