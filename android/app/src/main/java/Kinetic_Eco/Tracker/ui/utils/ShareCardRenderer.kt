package Kinetic_Eco.Tracker.ui.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.RoutePoint
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a branded, shareable session "impact card" (1080×1350, story/post friendly) to a Bitmap
 * using the Canvas API. Canvas rather than Compose→bitmap because this project's Compose version
 * predates GraphicsLayer.toImageBitmap(); a direct Canvas render is deterministic and dependency-free.
 *
 * PROTOTYPE — layout is intentionally simple/hand-placed; tune paddings, colours and copy to taste.
 */
object ShareCardRenderer {

    private const val W = 1080
    private const val H = 1350
    private const val PAD = 90f

    fun render(stats: SessionStats, unitSystem: UnitSystem, equivalency: String?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Background: deep green → mid green vertical gradient.
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                Color.parseColor("#0B3D2E"), Color.parseColor("#1C7C54"), Shader.TileMode.CLAMP
            )
        })

        val white = Color.WHITE
        val dim = Color.argb(210, 255, 255, 255)
        val accent = Color.parseColor("#7CF0BD")

        // Brand row
        c.drawText("🌿  KINETIC ECO", PAD, 150f, paint(white, 46f, true))

        // ── Hero ────────────────────────────────────────────────────────────────
        var y = 340f
        val co2 = stats.co2Conserved
        if (co2 > 0.01) {
            c.drawText("${co2.format(2)} kg", PAD, y, paint(white, 168f, true))
            y += 80f
            c.drawText("CO₂ saved", PAD, y, paint(dim, 58f, true))
            y += 96f
            if (!equivalency.isNullOrBlank()) {
                y = drawWrapped(c, equivalency, PAD, y, W - 2 * PAD, paint(accent, 48f, false), 62f)
            }
        } else {
            // No CO₂ saved (e.g. a drive) — lead with distance + activity instead of a bare "0".
            c.drawText(distanceStr(stats, unitSystem), PAD, y, paint(white, 168f, true))
            y += 80f
            val (emoji, lbl) = activity(stats)
            c.drawText("$emoji $lbl", PAD, y, paint(dim, 58f, true))
            y += 96f
        }

        // ── Route sketch ──────────────────────────────────────────────────────────
        val pts = stats.routePath
        if (pts.size >= 2) {
            val boxTop = max(y + 30f, 700f)
            val rect = RectF(PAD, boxTop, W - PAD, boxTop + 380f)
            c.drawRoundRect(rect, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) })
            drawRoute(c, pts, rect, accent)
            y = rect.bottom + 80f
        } else {
            y = max(y + 40f, 980f)
        }

        // ── Stat row: distance | time | activity ─────────────────────────────────
        val (emoji, lbl) = activity(stats)
        val cells = listOf(
            distanceStr(stats, unitSystem) to "Distance",
            durationStr(stats) to "Time",
            "$emoji $lbl" to "Activity"
        )
        val colW = (W - 2 * PAD) / 3f
        cells.forEachIndexed { i, (value, cap) ->
            val cx = PAD + colW * i
            c.drawText(value, cx, y, paint(white, 46f, true))
            c.drawText(cap, cx, y + 46f, paint(dim, 32f, false))
        }

        // ── Footer ────────────────────────────────────────────────────────────────
        c.drawText("Track your real impact — Kinetic Eco", PAD, H - 150f, paint(dim, 38f, false))
        c.drawText("#KineticEco  #GreenCommute", PAD, H - 88f, paint(accent, 36f, true))

        return bmp
    }

    private fun paint(color: Int, size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Draws [text] wrapped to [maxWidth]; returns the y after the last line. */
    private fun drawWrapped(c: Canvas, text: String, x: Float, startY: Float, maxWidth: Float, p: Paint, lineH: Float): Float {
        var line = StringBuilder()
        var y = startY
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(test) > maxWidth && line.isNotEmpty()) {
                c.drawText(line.toString(), x, y, p); y += lineH
                line = StringBuilder(word)
            } else {
                line = StringBuilder(test)
            }
        }
        if (line.isNotEmpty()) { c.drawText(line.toString(), x, y, p); y += lineH }
        return y
    }

    /** Scales the GPS polyline to fit inside [box] (with inset) and strokes it. */
    private fun drawRoute(c: Canvas, pts: List<RoutePoint>, box: RectF, color: Int) {
        val minLat = pts.minOf { it.latitude }; val maxLat = pts.maxOf { it.latitude }
        val minLon = pts.minOf { it.longitude }; val maxLon = pts.maxOf { it.longitude }
        val spanLat = max(maxLat - minLat, 1e-6); val spanLon = max(maxLon - minLon, 1e-6)
        val inset = 40f
        val w = box.width() - 2 * inset; val h = box.height() - 2 * inset
        val scale = min(w / spanLon, h / spanLat)
        val offX = box.left + inset + (w - spanLon * scale) / 2f
        val offY = box.top + inset + (h - spanLat * scale) / 2f
        val path = Path()
        pts.forEachIndexed { i, pt ->
            val px = (offX + (pt.longitude - minLon) * scale).toFloat()
            val py = (offY + (maxLat - pt.latitude) * scale).toFloat()  // invert lat so north is up
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 9f; this.color = color
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
    }

    private fun distanceStr(s: SessionStats, u: UnitSystem): String =
        if (u.usesMetricDistance()) "${(s.totalDistance / 1000).format(2)} km"
        else "${(s.totalDistance / 1609.34).format(2)} mi"

    private fun durationStr(s: SessionStats): String {
        val m = TimeUnit.SECONDS.toMinutes(s.totalDuration)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun activity(s: SessionStats): Pair<String, String> {
        val main = s.breakdown.entries.filter { it.key != ActivityType.IDLE }.maxByOrNull { it.value.distance }?.key
        return when (main) {
            ActivityType.WALKING -> "🚶" to "Walking"
            ActivityType.RUNNING -> "🏃" to "Running"
            ActivityType.CYCLING -> "🚴" to "Cycling"
            ActivityType.MOTORCYCLE -> "🛵" to "Motorcycle"
            ActivityType.TRAIN -> "🚆" to "Train"
            ActivityType.DRIVING -> "🚗" to "Driving"
            ActivityType.ELECTRIC_VEHICLE -> "⚡" to "EV"
            ActivityType.FLYING -> "✈️" to "Flying"
            else -> "🌿" to "Activity"
        }
    }
}