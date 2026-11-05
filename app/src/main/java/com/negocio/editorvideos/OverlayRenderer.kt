package com.negocio.editorvideos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * Dibuja el texto y el logo sobre una imagen transparente del mismo tamaño que el video.
 * Esa imagen se pone encima de cada cuadro del video al exportar, y también se usa
 * para la vista previa (a menor tamaño; todo se calcula en proporción).
 */
object OverlayRenderer {

    enum class TextPos { TOP, CENTER, BOTTOM }
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    fun render(
        width: Int,
        height: Int,
        text: String,
        textPos: TextPos,
        logo: Bitmap?,
        corner: Corner
    ): Bitmap? {
        if (text.isBlank() && logo == null) return null

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val minSide = min(width, height).toFloat()
        val margin = minSide * 0.05f
        // En vertical dejamos espacio arriba y abajo para los botones de Estados/Reels/TikTok
        val safeV = if (height > width) height * 0.09f else margin

        var logoTopEdge = height.toFloat()   // borde superior del logo si está abajo
        var logoBottomEdge = 0f              // borde inferior del logo si está arriba
        val logoOnTop = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT

        if (logo != null && logo.width > 0 && logo.height > 0) {
            val target = minSide * 0.20f
            val scale = target / max(logo.width, logo.height)
            val lw = logo.width * scale
            val lh = logo.height * scale
            val left = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) margin
            else width - margin - lw
            val top = if (logoOnTop) safeV else height - safeV - lh
            canvas.drawBitmap(
                logo, null, RectF(left, top, left + lw, top + lh),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            if (logoOnTop) logoBottomEdge = top + lh else logoTopEdge = top
        }

        val cleanText = text.trim()
        if (cleanText.isNotEmpty()) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = minSide * 0.062f
                typeface = Typeface.DEFAULT_BOLD
            }
            val layoutWidth = max(1, (width - margin * 4).toInt())
            val layout = StaticLayout.Builder
                .obtain(cleanText, 0, cleanText.length, paint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.1f)
                .build()

            var textW = 0f
            for (i in 0 until layout.lineCount) textW = max(textW, layout.getLineWidth(i))
            val pad = paint.textSize * 0.45f
            val boxW = textW + pad * 2
            val boxH = layout.height + pad * 2
            val boxLeft = (width - boxW) / 2f

            val boxTop = when (textPos) {
                TextPos.TOP -> {
                    val base = if (logo != null && logoOnTop) logoBottomEdge + margin * 0.5f else safeV
                    base
                }
                TextPos.CENTER -> (height - boxH) / 2f
                TextPos.BOTTOM -> {
                    val base = if (logo != null && !logoOnTop) logoTopEdge - margin * 0.5f else height - safeV
                    base - boxH
                }
            }

            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 0, 0, 0) }
            val radius = paint.textSize * 0.4f
            canvas.drawRoundRect(RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH), radius, radius, bg)

            canvas.save()
            canvas.translate((width - layoutWidth) / 2f, boxTop + pad)
            layout.draw(canvas)
            canvas.restore()
        }
        return bmp
    }
}
