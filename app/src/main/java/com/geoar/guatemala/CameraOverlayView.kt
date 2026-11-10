package com.geoar.guatemala

import android.animation.ValueAnimator
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
 * Diseño premium (lenguaje visual tipo iOS):
 *   - Chip translúcido oscuro con esquinas muy redondeadas y borde hairline
 *   - Punto de color según tipo (en lugar de emojis) + nombre en medium
 *   - Info secundaria (distancia · elevación) en gris sistema
 *   - Línea guía de 1 dp con baja opacidad hacia el punto proyectado
 *   - Punto proyectado pequeño con halo sutil
 *
 * Reticle central (estilo Google Lens):
 *   - LIBRE:     aro blanco tenue — sin objetivo centrado
 *   - APUNTANDO: aro verde pulsante — objetivo centrado, contando 2 s
 *   - BLOQUEADO: aro verde sólido — identificación completa
 *
 * Colores: volcán activo = rojo, volcán inactivo = naranja,
 *          cerros/sierras = blanco, lagos/lagunas = celeste.
 */
class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
    // -------------------------------------------------------------------------
    // API: estado del reticle
    // -------------------------------------------------------------------------

    enum class EstadoReticulo { LIBRE, APUNTANDO, BLOQUEADO }

    private var _estadoReticulo: EstadoReticulo = EstadoReticulo.LIBRE
    var estadoReticulo: EstadoReticulo
        get() = _estadoReticulo
        set(value)
        {
            if (_estadoReticulo == value) return
            _estadoReticulo = value
            when (value)
            {
                EstadoReticulo.APUNTANDO -> animadorPulso.start()
                else ->
                {
                    animadorPulso.cancel()
                    fasePulso = 0f
                }
            }
            invalidate()
        }

    private var fasePulso = 0f

    private val animadorPulso = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        addUpdateListener { anim ->
            fasePulso = anim.animatedValue as Float
            invalidate()
        }
    }

    // -------------------------------------------------------------------------
    // Estado de puntos AR
    // -------------------------------------------------------------------------

    private var puntosVisibles: List<Pair<LugarGeografico, PuntoVisible>> = emptyList()

    // -------------------------------------------------------------------------
    // Dimensiones en píxeles
    // -------------------------------------------------------------------------

    private val dm get() = resources.displayMetrics

    private fun sp(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
    private fun dp(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, dm)

    // -------------------------------------------------------------------------
    // Paints — etiquetas AR
    // -------------------------------------------------------------------------

    private val paintFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(204, 22, 22, 24)
    }

    private val paintHairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(36, 255, 255, 255)
    }

    private val paintNombre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 242, 242, 247)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }

    private val paintInfo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 152, 152, 157)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }

    private val paintLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 255, 255, 255)
    }

    private val paintPunto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintDotTipo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // -------------------------------------------------------------------------
    // Paints — reticle central
    // -------------------------------------------------------------------------

    private val paintReticulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val paintReticuloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
    // Ciclo de vida
    // -------------------------------------------------------------------------

    override fun onDetachedFromWindow()
    {
        super.onDetachedFromWindow()
        animadorPulso.cancel()
    }

    // -------------------------------------------------------------------------
    // Dibujo
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas)
    {
        super.onDraw(canvas)

        // Tamaños dependientes de densidad
        paintNombre.textSize = sp(15f)
        paintInfo.textSize = sp(12f)
        paintHairline.strokeWidth = dp(1f)
        paintLinea.strokeWidth = dp(1f)

        dibujarReticulo(canvas)

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

    /**
     * Dibuja el reticle central estilo Google Lens.
     *
     * LIBRE:     aro blanco muy tenue (apenas visible, no distrae)
     * APUNTANDO: aro verde con onda expansiva pulsante (ValueAnimator)
     * BLOQUEADO: aro verde sólido + punto central (identificación completa)
     */
    private fun dibujarReticulo(canvas: Canvas)
    {
        val cx = width / 2f
        val cy = height / 2f
        val radioBase = dp(28f)
        val verdeAR = Color.parseColor("#3DBA85")  // verde selva visible sobre cámara

        when (estadoReticulo)
        {
            EstadoReticulo.LIBRE ->
            {
                // Aro blanco tenue — sutil guía de centro
                paintReticulo.strokeWidth = dp(1f)
                paintReticulo.color = Color.argb(55, 255, 255, 255)
                canvas.drawCircle(cx, cy, radioBase, paintReticulo)

                // Punto central muy pequeño
                paintReticuloFill.color = Color.argb(70, 255, 255, 255)
                canvas.drawCircle(cx, cy, dp(2f), paintReticuloFill)
            }

            EstadoReticulo.APUNTANDO ->
            {
                // Onda expansiva que crece y se desvanece
                val radioPulso = radioBase + dp(18f) * fasePulso
                val alphaPulso = ((1f - fasePulso) * 170).toInt()
                paintReticulo.strokeWidth = dp(1.5f)
                paintReticulo.color = Color.argb(alphaPulso, 61, 186, 133)
                canvas.drawCircle(cx, cy, radioPulso, paintReticulo)

                // Aro base verde estático
                paintReticulo.strokeWidth = dp(2f)
                paintReticulo.color = Color.argb(220, 61, 186, 133)
                canvas.drawCircle(cx, cy, radioBase, paintReticulo)

                // Punto central verde
                paintReticuloFill.color = Color.argb(220, 61, 186, 133)
                canvas.drawCircle(cx, cy, dp(3f), paintReticuloFill)

                // 4 pequeñas muescas en el aro (estilo visor)
                val gap = dp(5f)
                val mLen = dp(7f)
                paintReticulo.strokeWidth = dp(2f)
                // arriba
                canvas.drawLine(cx, cy - radioBase - gap, cx, cy - radioBase - gap - mLen, paintReticulo)
                // abajo
                canvas.drawLine(cx, cy + radioBase + gap, cx, cy + radioBase + gap + mLen, paintReticulo)
                // izquierda
                canvas.drawLine(cx - radioBase - gap, cy, cx - radioBase - gap - mLen, cy, paintReticulo)
                // derecha
                canvas.drawLine(cx + radioBase + gap, cy, cx + radioBase + gap + mLen, cy, paintReticulo)
            }

            EstadoReticulo.BLOQUEADO ->
            {
                // Aro verde sólido y brillante — bloqueado
                paintReticulo.strokeWidth = dp(2.5f)
                paintReticulo.color = Color.argb(255, 61, 186, 133)
                canvas.drawCircle(cx, cy, radioBase, paintReticulo)

                // Punto central relleno
                paintReticuloFill.color = Color.argb(255, 61, 186, 133)
                canvas.drawCircle(cx, cy, dp(4.5f), paintReticuloFill)

                // Halo suave exterior
                paintReticuloFill.color = Color.argb(30, 61, 186, 133)
                canvas.drawCircle(cx, cy, radioBase + dp(8f), paintReticuloFill)
            }
        }
    }

    private fun dibujarEtiqueta(
        canvas: Canvas,
        lugar: LugarGeografico,
        puntoVisible: PuntoVisible,
        posicion: PointF
    )
    {
        val acento = colorAccento(lugar)
        val textoInfo = "${formatearDistancia(puntoVisible.distanciaKm)} · ${lugar.elevacionMsnm} msnm"

        val paddingH = dp(14f)
        val paddingV = dp(10f)
        val radioDot = dp(3.5f)
        val espacioDot = radioDot * 2f + dp(8f)
        val altoLineaIndicador = dp(44f)

        val anchoNombre = espacioDot + paintNombre.measureText(lugar.nombre)
        val anchoInfo = espacioDot + paintInfo.measureText(textoInfo)
        val anchoEtiqueta = maxOf(anchoNombre, anchoInfo) + paddingH * 2
        val altoNombre = paintNombre.textSize
        val altoInfo = paintInfo.textSize
        val interlinea = dp(5f)
        val altoEtiqueta = paddingV * 2 + altoNombre + interlinea + altoInfo
        val radioEsquinas = altoEtiqueta / 2.6f

        val xEtiqueta = (posicion.x - anchoEtiqueta / 2f)
            .coerceIn(dp(8f), (width - anchoEtiqueta - dp(8f)).coerceAtLeast(0f))
        val yEtiqueta = (posicion.y - altoEtiqueta - altoLineaIndicador)
            .coerceIn(dp(8f), (height - altoEtiqueta).coerceAtLeast(0f))

        canvas.drawLine(posicion.x, yEtiqueta + altoEtiqueta + dp(4f), posicion.x, posicion.y - dp(7f), paintLinea)

        paintHalo.color = acento
        paintHalo.alpha = 56
        canvas.drawCircle(posicion.x, posicion.y, dp(9f), paintHalo)
        paintPunto.color = acento
        canvas.drawCircle(posicion.x, posicion.y, dp(3.5f), paintPunto)

        rectEtiqueta.set(xEtiqueta, yEtiqueta, xEtiqueta + anchoEtiqueta, yEtiqueta + altoEtiqueta)
        canvas.drawRoundRect(rectEtiqueta, radioEsquinas, radioEsquinas, paintFondo)
        canvas.drawRoundRect(rectEtiqueta, radioEsquinas, radioEsquinas, paintHairline)

        val yBaseNombre = yEtiqueta + paddingV + altoNombre - dp(1f)
        paintDotTipo.color = acento
        canvas.drawCircle(
            xEtiqueta + paddingH + radioDot,
            yBaseNombre - altoNombre * 0.32f,
            radioDot,
            paintDotTipo
        )

        canvas.drawText(lugar.nombre, xEtiqueta + paddingH + espacioDot, yBaseNombre, paintNombre)

        canvas.drawText(
            textoInfo,
            xEtiqueta + paddingH + espacioDot,
            yBaseNombre + interlinea + altoInfo,
            paintInfo
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun colorAccento(lugar: LugarGeografico): Int
    {
        return when (lugar.tipo)
        {
            "volcan"                     -> if (lugar.activo) Color.parseColor("#FF453A") else Color.parseColor("#FF9F0A")
            "cerro", "montana", "sierra" -> Color.parseColor("#F2F2F7")
            "lago", "laguna"             -> Color.parseColor("#64D2FF")
            else                         -> Color.parseColor("#F2F2F7")
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
