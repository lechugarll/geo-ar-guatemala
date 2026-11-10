package com.geoar.guatemala

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.geoar.guatemala.models.LugarGeografico

/**
 * Lugar ya proyectado sobre la pantalla, listo para dibujarse.
 */
data class EtiquetaAR(
    val candidato: Candidato,
    val x: Float,                // Posición del lugar en pantalla (píxeles)
    val y: Float,
    val anguloAlCentro: Double,  // Grados entre el lugar y la mira central
    val enfocada: Boolean        // true si es el lugar que está en la mira
)

/**
 * Vista personalizada que dibuja el overlay de realidad aumentada sobre la cámara.
 *
 * Para cada lugar visible dibuja:
 *   - Punto circular en la posición exacta del lugar
 *   - Línea indicadora desde el punto hasta su etiqueta
 *   - Etiqueta: rectángulo redondeado semitransparente (fondo negro 70%) con
 *     borde de color, ícono + nombre en blanco bold, distancia y elevación
 *
 * Las etiquetas nunca se enciman: si dos lugares quedan en la misma dirección,
 * la menos prioritaria se desplaza hacia arriba hasta encontrar espacio libre.
 * El lugar que está en la mira central se resalta y se dibuja primero.
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
    companion object
    {
        // Máximo de etiquetas simultáneas para no saturar la pantalla
        const val MAX_ETIQUETAS = 8

        // Intentos de desplazar una etiqueta hacia arriba antes de omitirla
        private const val MAX_INTENTOS_COLOCACION = 6
    }

    // -------------------------------------------------------------------------
    // Estado
    // -------------------------------------------------------------------------

    private var etiquetas: List<EtiquetaAR> = emptyList()
    private var textoEstado: String = ""
    private var textoAviso: String? = null

    /** Se invoca cuando el usuario toca una etiqueta. */
    var onEtiquetaTocada: ((Candidato) -> Unit)? = null

    // Rectángulos ya ocupados en el cuadro actual (reutilizados para no allocar en onDraw)
    private val rectangulos = Array(MAX_ETIQUETAS) { RectF() }
    private val candidatosDibujados = arrayOfNulls<Candidato>(MAX_ETIQUETAS)
    private var cantidadDibujadas = 0

    private val rectPrueba = RectF()

    // -------------------------------------------------------------------------
    // Dimensiones en píxeles
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
    }

    private val paintPunto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintAnillo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val paintMira = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
    }

    private val paintEstado = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val paintAviso = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        textAlign = Paint.Align.LEFT
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    /**
     * Actualiza lo que se dibuja.
     *
     * @param nuevasEtiquetas Lugares proyectados, ordenados por prioridad (primero el más importante)
     * @param estado          Línea de estado (rumbo, precisión del GPS, elevación)
     * @param aviso           Aviso opcional (ej: brújula descalibrada), o null
     */
    fun actualizar(nuevasEtiquetas: List<EtiquetaAR>, estado: String, aviso: String?)
    {
        etiquetas = nuevasEtiquetas
        textoEstado = estado
        textoAviso = aviso
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Dibujo
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas)
    {
        super.onDraw(canvas)

        // Tamaños dependientes de la densidad
        paintNombre.textSize = sp(16f)
        paintInfo.textSize = sp(12f)
        paintEstado.textSize = sp(13f)
        paintAviso.textSize = sp(13f)
        paintLinea.strokeWidth = dp(1.5f)
        paintMira.strokeWidth = dp(1.5f)

        val hayEnfocada = etiquetas.any { it.enfocada }

        dibujarMiraCentral(canvas, hayEnfocada)

        cantidadDibujadas = 0
        for (etiqueta in etiquetas)
        {
            dibujarEtiqueta(canvas, etiqueta)
        }

        dibujarEstado(canvas)
    }

    private fun dibujarMiraCentral(canvas: Canvas, hayEnfocada: Boolean)
    {
        val cx = width / 2f
        val cy = height / 2f
        val t = dp(18f)
        val gap = t * 0.35f

        // La mira se vuelve verde cuando hay un lugar identificado en el centro
        paintMira.color = if (hayEnfocada)
        {
            Color.parseColor("#69F0AE")
        }
        else
        {
            Color.argb(200, 255, 255, 255)
        }

        canvas.drawLine(cx - t, cy, cx - gap, cy, paintMira)
        canvas.drawLine(cx + gap, cy, cx + t, cy, paintMira)
        canvas.drawLine(cx, cy - t, cx, cy - gap, paintMira)
        canvas.drawLine(cx, cy + gap, cx, cy + t, paintMira)
    }

    private fun dibujarEstado(canvas: Canvas)
    {
        val margen = dp(16f)
        var y = margen + paintEstado.textSize

        if (textoEstado.isNotEmpty())
        {
            canvas.drawText(textoEstado, margen, y, paintEstado)
            y += paintAviso.textSize + dp(6f)
        }

        textoAviso?.let { aviso ->
            canvas.drawText(aviso, margen, y, paintAviso)
        }
    }

    private fun dibujarEtiqueta(canvas: Canvas, etiqueta: EtiquetaAR)
    {
        val lugar = etiqueta.candidato.lugar
        val colorAccento = colorAccento(lugar)

        // El punto siempre se dibuja, aunque la etiqueta no quepa
        paintPunto.color = colorAccento
        canvas.drawCircle(etiqueta.x, etiqueta.y, dp(5f), paintPunto)

        if (etiqueta.enfocada)
        {
            paintAnillo.color = colorAccento
            paintAnillo.strokeWidth = dp(2f)
            canvas.drawCircle(etiqueta.x, etiqueta.y, dp(11f), paintAnillo)
        }

        if (cantidadDibujadas >= MAX_ETIQUETAS) return

        val textoNombre = "${icono(lugar)} ${lugar.nombre}"
        val textoInfo = "${formatearDistancia(etiqueta.candidato.distanciaKm)} · ${lugar.elevacionMsnm} msnm"

        val padding = dp(12f)
        val radioEsquinas = dp(10f)
        val separacion = dp(6f)
        val altoLineaIndicador = dp(40f)
        val zonaSuperiorReservada = dp(64f)

        val anchoNombre = paintNombre.measureText(textoNombre)
        val anchoInfo = paintInfo.measureText(textoInfo)
        val anchoEtiqueta = maxOf(anchoNombre, anchoInfo) + padding * 2
        val altoNombre = paintNombre.textSize
        val altoInfo = paintInfo.textSize
        val altoEtiqueta = altoNombre + altoInfo + padding * 2.5f

        val xEtiqueta = (etiqueta.x - anchoEtiqueta / 2f)
            .coerceIn(0f, (width - anchoEtiqueta).coerceAtLeast(0f))

        // Buscar un hueco libre: primero justo encima del punto, luego cada vez más arriba
        var yEtiqueta = etiqueta.y - altoLineaIndicador - altoEtiqueta
        var colocada = false

        for (intento in 0 until MAX_INTENTOS_COLOCACION)
        {
            if (yEtiqueta < zonaSuperiorReservada) break

            rectPrueba.set(xEtiqueta, yEtiqueta, xEtiqueta + anchoEtiqueta, yEtiqueta + altoEtiqueta)
            if (!chocaConOtra(rectPrueba, separacion))
            {
                colocada = true
                break
            }
            yEtiqueta -= altoEtiqueta + separacion
        }

        if (!colocada) return

        val rect = rectangulos[cantidadDibujadas]
        rect.set(rectPrueba)
        candidatosDibujados[cantidadDibujadas] = etiqueta.candidato
        cantidadDibujadas++

        // Línea indicadora desde la etiqueta hasta el punto
        paintLinea.color = colorAccento
        paintLinea.alpha = 200
        canvas.drawLine(etiqueta.x, rect.bottom, etiqueta.x, etiqueta.y, paintLinea)

        // Fondo semitransparente
        paintFondo.color = Color.argb(178, 0, 0, 0)
        canvas.drawRoundRect(rect, radioEsquinas, radioEsquinas, paintFondo)

        // Borde coloreado (más grueso si es el lugar en la mira)
        paintBorde.color = colorAccento
        paintBorde.alpha = 220
        paintBorde.strokeWidth = if (etiqueta.enfocada) dp(3f) else dp(1.5f)
        canvas.drawRoundRect(rect, radioEsquinas, radioEsquinas, paintBorde)

        // Nombre
        canvas.drawText(textoNombre, rect.left + padding, rect.top + padding + altoNombre, paintNombre)

        // Info
        paintInfo.color = colorInfo(lugar)
        canvas.drawText(
            textoInfo,
            rect.left + padding,
            rect.top + padding + altoNombre + padding * 0.6f + altoInfo,
            paintInfo
        )
    }

    private fun chocaConOtra(rect: RectF, separacion: Float): Boolean
    {
        for (i in 0 until cantidadDibujadas)
        {
            val otra = rectangulos[i]
            if (rect.left < otra.right + separacion &&
                rect.right > otra.left - separacion &&
                rect.top < otra.bottom + separacion &&
                rect.bottom > otra.top - separacion)
            {
                return true
            }
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Toques: tocar una etiqueta abre la información del lugar
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean
    {
        if (event.action == MotionEvent.ACTION_DOWN)
        {
            return buscarEtiquetaEn(event.x, event.y) != null
        }

        if (event.action == MotionEvent.ACTION_UP)
        {
            val candidato = buscarEtiquetaEn(event.x, event.y)
            if (candidato != null)
            {
                performClick()
                onEtiquetaTocada?.invoke(candidato)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean
    {
        super.performClick()
        return true
    }

    private fun buscarEtiquetaEn(x: Float, y: Float): Candidato?
    {
        for (i in 0 until cantidadDibujadas)
        {
            if (rectangulos[i].contains(x, y))
            {
                return candidatosDibujados[i]
            }
        }
        return null
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
