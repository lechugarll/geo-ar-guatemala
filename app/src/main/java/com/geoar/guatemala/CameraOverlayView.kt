package com.geoar.guatemala

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.geoar.guatemala.models.LugarGeografico

/**
 * Vista personalizada que dibuja el overlay de realidad aumentada sobre la cámara.
 *
 * Para cada punto geográfico visible dibuja:
 *   - Rectángulo redondeado semitransparente (fondo negro 70%)
 *   - Borde coloreado según tipo de accidente
 *   - Ícono tipo + nombre en blanco bold
 *   - Distancia en km y elevación en msnm
 *   - Línea vertical desde la etiqueta al punto proyectado
 *   - Punto circular en la posición proyectada
 *
 * Colores: volcán activo = rojo, volcán inactivo = naranja,
 *          cerros/sierras = blanco, lagos/lagunas = celeste.
 *
 * Nota: los lambdas de Kotlin (.apply, .also, etc.) requieren { en la misma línea.
 * Las llaves de clases, funciones e if/when usan estilo Allman.
 */
class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
    // -------------------------------------------------------------------------
    // Estado
    // -------------------------------------------------------------------------

    private var puntosVisibles: List<Pair<LugarGeografico, PuntoVisible>> = emptyList()

    // -------------------------------------------------------------------------
    // Dimensiones en píxeles — calculadas una sola vez
    // -------------------------------------------------------------------------

    private val dm get() = resources.displayMetrics

    private fun sp(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
    private fun dp(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, dm)

    // -------------------------------------------------------------------------
    // Paints (creados una sola vez para no allocar en onDraw)
    // Nota: { del lambda debe ir en la misma línea que .apply en Kotlin
    // -------------------------------------------------------------------------

    private val paintFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val paintNombre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private val paintInfo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private val paintLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val paintPunto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintMira = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Buffer reutilizable para el rectángulo de cada etiqueta
    private val rectEtiqueta = RectF()

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    fun actualizarPuntos(puntos: List<Pair<LugarGeografico, PuntoVisible>>)
    {
        puntosVisibles = puntos
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Dibujo
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas)
    {
        super.onDraw(canvas)

        // Actualizar tamaños de texto dependientes de la densidad
        paintNombre.textSize = sp(16f)
        paintBorde.strokeWidth = dp(1.5f)
        paintLinea.strokeWidth = dp(1.5f)
        paintMira.strokeWidth = dp(1.5f)
        paintInfo.textSize = sp(12f)

        dibujarMiraCentral(canvas)

        for ((lugar, puntoVisible) in puntosVisibles)
        {
            val posicion = GeoCalculator.calcularPosicionEnPantalla(
                puntoVisible.difHorizontal,
                puntoVisible.difVertical,
                width,
                height
            )
            dibujarEtiqueta(canvas, lugar, puntoVisible, posicion)
        }
    }

    private fun dibujarMiraCentral(canvas: Canvas)
    {
        val cx = width / 2f
        val cy = height / 2f
        val t = dp(18f)
        val gap = t * 0.35f

        canvas.drawLine(cx - t, cy, cx - gap, cy, paintMira)
        canvas.drawLine(cx + gap, cy, cx + t, cy, paintMira)
        canvas.drawLine(cx, cy - t, cx, cy - gap, paintMira)
        canvas.drawLine(cx, cy + gap, cx, cy + t, paintMira)
    }

    private fun dibujarEtiqueta(
        canvas: Canvas,
        lugar: LugarGeografico,
        puntoVisible: PuntoVisible,
        posicion: PointF
    )
    {
        val colorAccento = colorAccento(lugar)
        val textoNombre = "${icono(lugar)} ${lugar.nombre}"
        val textoInfo = "${formatearDistancia(puntoVisible.distanciaKm)} · ${lugar.elevacionMsnm} msnm"

        val padding = dp(12f)
        val radioEsquinas = dp(10f)
        val altoLineaIndicador = dp(48f)

        val anchoNombre = paintNombre.measureText(textoNombre)
        val anchoInfo = paintInfo.measureText(textoInfo)
        val anchoEtiqueta = maxOf(anchoNombre, anchoInfo) + padding * 2
        val altoNombre = paintNombre.textSize
        val altoInfo = paintInfo.textSize
        val altoEtiqueta = altoNombre + altoInfo + padding * 2.5f

        val xEtiqueta = (posicion.x - anchoEtiqueta / 2f)
            .coerceIn(0f, (width - anchoEtiqueta).coerceAtLeast(0f))
        val yEtiqueta = (posicion.y - altoEtiqueta - altoLineaIndicador)
            .coerceIn(0f, (height - altoEtiqueta).coerceAtLeast(0f))

        // Línea indicadora
        paintLinea.color = colorAccento
        paintLinea.alpha = 200
        canvas.drawLine(posicion.x, yEtiqueta + altoEtiqueta, posicion.x, posicion.y, paintLinea)

        // Punto proyectado
        paintPunto.color = colorAccento
        canvas.drawCircle(posicion.x, posicion.y, dp(5f), paintPunto)

        // Fondo semitransparente
        rectEtiqueta.set(xEtiqueta, yEtiqueta, xEtiqueta + anchoEtiqueta, yEtiqueta + altoEtiqueta)
        paintFondo.color = Color.argb(178, 0, 0, 0)
        canvas.drawRoundRect(rectEtiqueta, radioEsquinas, radioEsquinas, paintFondo)

        // Borde coloreado
        paintBorde.color = colorAccento
        paintBorde.alpha = 220
        canvas.drawRoundRect(rectEtiqueta, radioEsquinas, radioEsquinas, paintBorde)

        // Nombre
        paintNombre.color = Color.WHITE
        canvas.drawText(textoNombre, xEtiqueta + padding, yEtiqueta + padding + altoNombre, paintNombre)

        // Info
        paintInfo.color = colorInfo(lugar)
        canvas.drawText(textoInfo, xEtiqueta + padding, yEtiqueta + padding + altoNombre + padding * 0.6f + altoInfo, paintInfo)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun icono(lugar: LugarGeografico): String
    {
        return when (lugar.tipo)
        {
            "volcan"          -> if (lugar.activo) "🌋" else "⛰️"
            "cerro"           -> "⛰️"
            "montana"         -> "⛰️"
            "sierra"          -> "⛰️"
            "lago"            -> "💧"
            "laguna"          -> "💧"
            else              -> "📍"
        }
    }

    private fun colorAccento(lugar: LugarGeografico): Int
    {
        return when (lugar.tipo)
        {
            "volcan"                     -> if (lugar.activo) Color.parseColor("#F44336") else Color.parseColor("#FF6600")
            "cerro", "montana", "sierra" -> Color.WHITE
            "lago", "laguna"             -> Color.parseColor("#4FC3F7")
            else                         -> Color.WHITE
        }
    }

    private fun colorInfo(lugar: LugarGeografico): Int
    {
        return when (lugar.tipo)
        {
            "volcan"         -> if (lugar.activo) Color.parseColor("#FF8A80") else Color.parseColor("#FFAB40")
            "lago", "laguna" -> Color.parseColor("#80D8FF")
            else             -> Color.parseColor("#BDBDBD")
        }
    }

    private fun formatearDistancia(distanciaKm: Double): String
    {
        return if (distanciaKm < 10.0)
        {
            String.format("%.1f km", distanciaKm)
        }
        else
        {
            "${distanciaKm.toInt()} km"
        }
    }
}
