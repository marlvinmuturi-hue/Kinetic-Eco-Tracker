package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.ui.utils.formatDurationSumHoursMinutes
import Kinetic_Eco.Tracker.ui.theme.Green500
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Line chart for four 6-hour buckets for **one selected calendar day**; [bucketMs] is active
 * duration (ms) per bucket that day.
 */
@Composable
fun ActivityTimeOfDayLineChart(
    bucketMs: LongArray,
    selectedDateKey: String,
    onDateKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val axisHourLabels = listOf(
        stringResource(R.string.time_axis_00),
        stringResource(R.string.time_axis_06),
        stringResource(R.string.time_axis_12),
        stringResource(R.string.time_axis_18),
        stringResource(R.string.time_axis_00)
    )
    val peakSegmentLabels = listOf(
        stringResource(R.string.time_segment_00_06),
        stringResource(R.string.time_segment_06_12),
        stringResource(R.string.time_segment_12_18),
        stringResource(R.string.time_segment_18_24)
    )

    val selectedDate = remember(selectedDateKey) { LocalDate.parse(selectedDateKey) }
    val today = LocalDate.now(zone)
    val canGoNext = selectedDate.isBefore(today)
    val dateLabel = remember(selectedDateKey) {
        val fmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        selectedDate.format(fmt)
    }

    val maxMs = remember(bucketMs) { bucketMs.maxOrNull()?.coerceAtLeast(1L) ?: 1L }
    val peakIndex = remember(bucketMs) {
        bucketMs.indices.maxByOrNull { bucketMs[it] } ?: 0
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.activity_by_time_of_day),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.activity_by_time_of_day_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        onDateKeyChange(selectedDate.minusDays(1).toString())
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.activity_chart_prev_day),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                IconButton(
                    onClick = {
                        if (canGoNext) onDateKeyChange(selectedDate.plusDays(1).toString())
                    },
                    enabled = canGoNext,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.activity_chart_next_day),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }
            if (selectedDate != today) {
                TextButton(
                    onClick = { onDateKeyChange(today.toString()) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.activity_chart_jump_today))
                }
            }

            val totalMs = bucketMs.sum()
            if (totalMs <= 0L) {
                Text(
                    text = stringResource(R.string.activity_time_chart_empty_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.peak_activity_window, peakSegmentLabels[peakIndex]),
                    style = MaterialTheme.typography.labelLarge,
                    color = Green500,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )

                val lineColor = Green500
                val fillColor = Green500.copy(alpha = 0.12f)
                val pointColor = lineColor
                val surfaceColor = colorScheme.surface

                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)) {
                    val w = size.width
                    val h = size.height
                    val padX = 8.dp.toPx()
                    val padY = 8.dp.toPx()
                    val chartW = w - padX * 2
                    val chartH = h - padY * 2
                    val n = 4
                    fun xAt(i: Int): Float = padX + chartW * i / (n - 1).coerceAtLeast(1)
                    fun yAt(ms: Long): Float {
                        val t = ms.toFloat() / maxMs.toFloat()
                        return padY + chartH * (1f - t.coerceIn(0f, 1f))
                    }

                    val path = Path()
                    val fillPath = Path()
                    for (i in 0 until n) {
                        val x = xAt(i)
                        val y = yAt(bucketMs[i])
                        if (i == 0) {
                            path.moveTo(x, y)
                            fillPath.moveTo(x, h - padY)
                            fillPath.lineTo(x, y)
                        } else {
                            path.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    fillPath.lineTo(xAt(n - 1), h - padY)
                    fillPath.close()
                    drawPath(fillPath, fillColor)
                    drawPath(
                        path,
                        color = lineColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                    for (i in 0 until n) {
                        drawCircle(
                            color = pointColor,
                            radius = 5.dp.toPx(),
                            center = Offset(xAt(i), yAt(bucketMs[i]))
                        )
                        drawCircle(
                            color = surfaceColor,
                            radius = 2.dp.toPx(),
                            center = Offset(xAt(i), yAt(bucketMs[i]))
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    axisHourLabels.forEach { hour ->
                        Text(
                            text = hour,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    for (i in 0 until 4) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = formatDurationSumHoursMinutes(bucketMs[i] / 1000L),
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
