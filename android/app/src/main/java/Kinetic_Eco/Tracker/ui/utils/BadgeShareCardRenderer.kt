package Kinetic_Eco.Tracker.ui.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import Kinetic_Eco.Tracker.data.AchievementBadge
import Kinetic_Eco.Tracker.ui.components.badgeArtworkDrawableRes
import Kinetic_Eco.Tracker.ui.components.badgeFmtVal
import Kinetic_Eco.Tracker.ui.components.badgeTierColorArgb
import kotlin.math.min

/**
 * Renders a branded, shareable achievement-badge image (1080×1350, story/post friendly) to a Bitmap
 * using the Canvas API — the same approach and dimensions as [ShareCardRenderer], so the two share
 * cards feel like one family. Loading the tier artwork off a resource id and compositing it here
 * keeps the shared image independent of Compose (this project's Compose predates
 * GraphicsLayer.toImageBitmap()).
 *
 * Call off the main thread — it decodes a bitmap and allocates a 1080×1350 ARGB_8888 canvas.
 */
object BadgeShareCardRenderer {

    private const val W = 1080
    private const val H = 1350

    fun render(context: Context, badge: AchievementBadge): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = W / 2f

        val tier = badgeTierColorArgb(badge.tier)
        val white = Color.WHITE
        val dim = Color.argb(210, 255, 255, 255)

        // Background: deep green → mid green vertical gradient (brand-consistent with the session card).
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                Color.parseColor("#0B3D2E"), Color.parseColor("#123F30"), Shader.TileMode.CLAMP
            )
        })

        // Brand row + "achievement unlocked" eyebrow.
        c.drawText("🌿  KINETIC ECO", cx, 150f, paint(white, 44f, true))
        c.drawText("ACHIEVEMENT UNLOCKED", cx, 236f, paint(tier, 46f, true).apply { letterSpacing = 0.14f })

        // Badge artwork, centred, over a soft tier-coloured glow.
        val badgeTop = 320f
        val badgeSize = 500f
        val res = badgeArtworkDrawableRes(badge.id, badge.tier)
        val src = res?.let { runCatching { BitmapFactory.decodeResource(context.resources, it) }.getOrNull() }
        val glowCy = badgeTop + badgeSize / 2f
        c.drawCircle(cx, glowCy, badgeSize * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, glowCy, badgeSize * 0.62f,
                intArrayOf(withAlpha(tier, 120), withAlpha(tier, 0)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        })
        if (src != null) {
            val scale = min(badgeSize / src.width, badgeSize / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            val left = cx - dw / 2f
            c.drawBitmap(
                src, null, RectF(left, badgeTop, left + dw, badgeTop + dh),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            )
        } else {
            // No dedicated artwork — fall back to the badge emoji.
            c.drawText(badge.emoji, cx, glowCy + 90f, paint(white, 260f, false))
        }

        // Title + tier + achieved amount.
        var y = badgeTop + badgeSize + 92f
        c.drawText(badge.title, cx, y, paint(white, 78f, true))
        y += 66f
        c.drawText("${badge.tier.label} tier", cx, y, paint(tier, 46f, true))
        y += 96f
        c.drawText("${badgeFmtVal(badge.currentValue, badge.unit)} ${badge.unit}", cx, y, paint(white, 66f, true))
        y += 52f
        c.drawText(badge.description, cx, y, paint(dim, 36f, false))

        // Footer.
        c.drawText("Track your real impact — Kinetic Eco", cx, H - 132f, paint(dim, 36f, false))
        c.drawText("#KineticEco  #GreenCommute", cx, H - 78f, paint(tier, 36f, true))

        return bmp
    }

    private fun paint(color: Int, size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Returns [argb] with its alpha channel replaced by [alpha] (0..255). */
    private fun withAlpha(argb: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(argb), Color.green(argb), Color.blue(argb))
}