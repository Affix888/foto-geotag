package com.fotogeotag.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

data class StampData(
    val title: String,
    val address: String,
    val coords: String,
    val dateLine: String,
    val showMap: Boolean,
)

/** Menggambar stempel (peta + kotak info) di bagian bawah foto. */
object StampRenderer {

    fun render(base: Bitmap, d: StampData, map: Bitmap?): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val u = min(w, h) / 100f

        val margin = 2.5f * u
        val mapSize = if (d.showMap) 30f * u else 0f
        val gap = if (d.showMap) 1.5f * u else 0f
        val boxLeft = margin + mapSize + gap
        val boxRight = w - margin
        val pad = 2.2f * u
        val textW = (boxRight - boxLeft - 2 * pad).toInt().coerceAtLeast(10)

        val titlePaint = textPaint(4.6f * u, u)
        val bodyPaint = textPaint(3.1f * u, u)

        val layouts = listOfNotNull(
            d.title.trim().takeIf { it.isNotEmpty() }?.let { layout(it, titlePaint, textW) },
            d.address.trim().takeIf { it.isNotEmpty() }?.let { layout(it, bodyPaint, textW) },
            d.coords.trim().takeIf { it.isNotEmpty() }?.let { layout(it, bodyPaint, textW) },
            d.dateLine.trim().takeIf { it.isNotEmpty() }?.let { layout(it, bodyPaint, textW) },
        )
        val spacing = 0.6f * u
        val textH = layouts.sumOf { it.height }.toFloat() + spacing * max(0, layouts.size - 1)
        val bottom = h - margin

        if (layouts.isNotEmpty()) {
            val top = bottom - (textH + 2 * pad)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
            c.drawRoundRect(RectF(boxLeft, top, boxRight, bottom), 2f * u, 2f * u, bg)
            var y = top + pad
            for (l in layouts) {
                c.save()
                c.translate(boxLeft + pad, y)
                l.draw(c)
                c.restore()
                y += l.height + spacing
            }
        }

        if (d.showMap) {
            drawMap(c, RectF(margin, bottom - mapSize, margin + mapSize, bottom), map, u)
        }
        return out
    }

    private fun textPaint(size: Float, u: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        setShadowLayer(0.25f * u, 0f, 0f, Color.argb(120, 0, 0, 0))
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()

    private fun drawMap(c: Canvas, rect: RectF, map: Bitmap?, u: Float) {
        val r = 2f * u
        val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        if (map != null) {
            c.drawBitmap(map, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            c.drawRect(rect, Paint().apply { color = Color.rgb(120, 135, 120) })
        }
        c.restore()

        // Pin merah di tengah
        val pr = rect.width() * 0.085f
        val cx = rect.centerX()
        val tipY = rect.centerY()
        val headY = tipY - pr * 1.7f
        val pin = Path().apply {
            addCircle(cx, headY, pr, Path.Direction.CW)
            moveTo(cx - pr * 0.82f, headY + pr * 0.55f)
            lineTo(cx + pr * 0.82f, headY + pr * 0.55f)
            lineTo(cx, tipY)
            close()
        }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(229, 57, 53)
            setShadowLayer(0.4f * u, 0f, 0.15f * u, Color.argb(140, 0, 0, 0))
        }
        c.drawPath(pin, red)
        c.drawCircle(cx, headY, pr * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 20, 20) })

        if (map != null) {
            val attr = textPaint(1.8f * u, u)
            c.drawText("Esri", rect.left + 1f * u, rect.bottom - 1f * u, attr)
        }
    }
}
