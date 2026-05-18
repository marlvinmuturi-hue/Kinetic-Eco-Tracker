package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.RoutePoint
import Kinetic_Eco.Tracker.ui.theme.Green500
import Kinetic_Eco.Tracker.util.haversineMeters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MAX_DRAW_SAMPLES = 280

/** Distance (m) vs elevation (m) along the route; null if not enough altitude data. */
internal fun routeElevationSamples(points: List<RoutePoint>): List<Pair<Double, Double>>? {
    if (points.size < 2) return null
    val filled = filledAltitudesAlongRoute(points) ?: return null
    val pairs = mutableListOf<Pair<Double, Double>>()
    var cumDist = 0.0
    pairs.add(0.0 to filled[0])
    for (i in 1 until points.size) {
        cumDist += haversineMeters(
            points[i - 1].latitude,
            points[i - 1].longitude,
            points[i].latitude,
            points[i].longitude
        )
        pairs.add(cumDist to filled[i])
    }
    return downsamplePairs(pairs, MAX_DRAW_SAMPLES)
}

private fun filledAltitudesAlongRoute(points: List<RoutePoint>): List<Double>? {
    val n = points.size
    val raw = MutableList<Double?>(n) {
        points[it].altitudeMeters?.takeIf { a -> a.isFinite() }
    }
    if (raw.all { it == null }) return null
    var last: Double? = null
    for (i in 0 until n) {
        if (raw[i] != null) last = raw[i]
        else if (last != null) raw[i] = last
    }
    last = raw.lastOrNull { it != null }
    for (i in n - 1 downTo 0) {
        if (raw[i] != null) last = raw[i]
        else if (last != null) raw[i] = last
    }
    return raw.map { it ?: return null }
}

private fun downsamplePairs(
    pairs: List<Pair<Double, Double>>,
    maxPoints: Int
): List<Pair<Double, Double>> {
    if (pairs.size <= maxPoints) return pairs
    val last = pairs.lastIndex
    val step = last.toDouble() / (maxPoints - 1)
    return List(maxPoints) { k ->
        val idx = (k * step).roundToInt().coerceIn(0, last)
        pairs[idx]
    }
}

/** 1, 2, 5 × 10^n style step for axis labels (meters). */
private fun niceStep(raw: Double): Double {
    if (!raw.isFinite() || raw <= 0) return 1.0
    val exp = floor(log10(raw))
    val f = raw / 10.0.pow(exp)
    val nf = when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 8.0 -> 5.0
        else -> 10.0
    }
    return nf * 10.0.pow(exp)
}

private fun elevationAxisTicks(yLow: Double, yHigh: Double, target: Int = 4): List<Double> {
    val span = (yHigh - yLow).coerceAtLeast(1e-6)
    val step = niceStep(span / (target - 1).coerceAtLeast(1))
    val start = floor(yLow / step) * step
    val end = ceil(yHigh / step) * step
    val out = mutableListOf<Double>()
    var v = start
    while (v <= end + step * 0.001) {
        out.add(v)
        v += step
        if (out.size > 16) break
    }
    return out
}

private fun distanceAxisTicks(distMax: Double, target: Int = 4): List<Double> {
    if (distMax <= 0) return listOf(0.0)
    val step = niceStep(distMax / (target - 1).coerceAtLeast(1))
    val out = mutableListOf<Double>()
    var d = 0.0
    while (d <= distMax + step * 0.001) {
        out.add(d)
        d += step
        if (out.size > 12) break
    }
    val last = out.lastOrNull() ?: 0.0
    if (distMax - last > distMax * 0.02 || (distMax > 0 && last < distMax - 1e-6)) {
        if (!out.any { kotlin.math.abs(it - distMax) < step * 0.01 }) {
            out.add(distMax)
        }
    }
    return out.sorted().distinct()
}

/**
 * Elevation profile (distance on horizontal axis, meters ASL on vertical).
 * Route polyline colors stay activity-based; this chart is independent.
 */
@Composable
fun RouteElevationProfile(
    routePath: List<RoutePoint>,
    modifier: Modifier = Modifier
) {
    val samples = remember(routePath) { routeElevationSamples(routePath) }
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.elevation_profile),
                style = typography.labelMedium,
                color = colorScheme.onSurface
            )
            if (samples == null || samples.size < 2) {
                Text(
                    text = stringResource(R.string.no_elevation_data),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                val distMax = samples.last().first
                val altMin = samples.minOf { it.second }
                val altMax = samples.maxOf { it.second }
                val altPad = (altMax - altMin).coerceAtLeast(1.0) * 0.06
                var yLow = altMin - altPad
                var yHigh = altMax + altPad
                val yTicks = elevationAxisTicks(yLow, yHigh, 4)
                yLow = min(yLow, yTicks.minOrNull() ?: yLow)
                yHigh = max(yHigh, yTicks.maxOrNull() ?: yHigh)
                val ySpan = (yHigh - yLow).coerceAtLeast(1e-6)

                val xTickCount = when {
                    distMax < 150.0 -> 2
                    distMax < 2500.0 -> 3
                    else -> 4
                }
                val xTicks = distanceAxisTicks(distMax, xTickCount)

                val textMeasurer = rememberTextMeasurer()

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp)
                        .padding(top = 6.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val padL = 40f
                    val padR = 6f
                    val padT = 6f
                    val padB = 22f
                    val innerW = (w - padL - padR).coerceAtLeast(1f)
                    val innerH = (h - padT - padB).coerceAtLeast(1f)
                    val plotLeft = padL
                    val plotRight = w - padR
                    val plotTop = padT
                    val plotBottom = h - padB

                    fun xAt(d: Double): Float =
                        plotLeft + ((d / distMax.coerceAtLeast(1e-6)) * innerW).toFloat()
                    fun yAt(a: Double): Float =
                        plotTop + ((yHigh - a) / ySpan * innerH).toFloat()

                    val gridColor = colorScheme.outline.copy(alpha = 0.14f)
                    val axisLabelStyle = TextStyle(
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                        fontSize = 9.sp
                    )

                    for (yt in yTicks) {
                        if (yt < yLow - 0.01 || yt > yHigh + 0.01) continue
                        val yy = yAt(yt)
                        drawLine(
                            color = gridColor,
                            start = Offset(plotLeft, yy),
                            end = Offset(plotRight, yy),
                            strokeWidth = 1f
                        )
                    }
                    for (xt in xTicks) {
                        if (xt < 0 || xt > distMax + 0.01) continue
                        val xx = xAt(xt)
                        drawLine(
                            color = gridColor,
                            start = Offset(xx, plotTop),
                            end = Offset(xx, plotBottom),
                            strokeWidth = 1f
                        )
                    }

                    val fillPath = Path().apply {
                        val first = samples.first()
                        moveTo(xAt(first.first), yAt(first.second))
                        for (i in 1 until samples.size) {
                            val (d, a) = samples[i]
                            lineTo(xAt(d), yAt(a))
                        }
                        lineTo(xAt(samples.last().first), plotBottom)
                        lineTo(xAt(samples.first().first), plotBottom)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Green500.copy(alpha = 0.32f),
                                Green500.copy(alpha = 0.04f)
                            ),
                            startY = plotTop,
                            endY = plotBottom
                        )
                    )
                    val strokePath = Path().apply {
                        val first = samples.first()
                        moveTo(xAt(first.first), yAt(first.second))
                        for (i in 1 until samples.size) {
                            val (d, a) = samples[i]
                            lineTo(xAt(d), yAt(a))
                        }
                    }
                    drawPath(
                        path = strokePath,
                        color = Green500,
                        style = Stroke(width = 3f, cap = StrokeCap.Round)
                    )

                    for (yt in yTicks) {
                        if (yt < yLow - 0.01 || yt > yHigh + 0.01) continue
                        val label = "${yt.roundToInt()} m"
                        val layout = textMeasurer.measure(
                            AnnotatedString(label),
                            style = axisLabelStyle
                        )
                        val yy = yAt(yt) - layout.size.height / 2f
                        val lx = (plotLeft - layout.size.width - 5f).coerceAtLeast(0f)
                        drawText(layout, topLeft = Offset(lx, yy))
                    }
                    for (xt in xTicks) {
                        if (xt < 0 || xt > distMax + 0.01) continue
                        val label = formatRouteDistance(xt)
                        val layout = textMeasurer.measure(
                            AnnotatedString(label),
                            style = axisLabelStyle
                        )
                        val xx = xAt(xt) - layout.size.width / 2f
                        val lx = xx.coerceIn(0f, w - layout.size.width)
                        drawText(
                            layout,
                            topLeft = Offset(lx, plotBottom + 4f)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.elevation_axis_caption),
                    style = typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun formatRouteDistance(meters: Double): String {
    return if (meters >= 1000.0) {
        "%.2f km".format(meters / 1000.0)
    } else {
        "%.0f m".format(meters)
    }
}
