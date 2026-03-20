package com.geoar.guatemala

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Vista personalizada que dibuja 4 corchetes de esquina (estilo visor de cámara).
 * Se usa en la pantalla de escaneo para enmarcar el sujeto.
 */
class ViewfinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs)
{
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA1D494")  // primary green
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }

    private val cornerLen get() = 40f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas)
    {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cl = cornerLen
        val sw = paint.strokeWidth / 2f

        // Top-left
        canvas.drawLine(sw, sw, sw + cl, sw, paint)
        canvas.drawLine(sw, sw, sw, sw + cl, paint)

        // Top-right
        canvas.drawLine(w - sw - cl, sw, w - sw, sw, paint)
        canvas.drawLine(w - sw, sw, w - sw, sw + cl, paint)

        // Bottom-left
        canvas.drawLine(sw, h - sw, sw + cl, h - sw, paint)
        canvas.drawLine(sw, h - sw - cl, sw, h - sw, paint)

        // Bottom-right
        canvas.drawLine(w - sw - cl, h - sw, w - sw, h - sw, paint)
        canvas.drawLine(w - sw, h - sw - cl, w - sw, h - sw, paint)
    }
}
