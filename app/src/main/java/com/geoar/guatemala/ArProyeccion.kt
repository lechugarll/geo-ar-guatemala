package com.geoar.guatemala

import kotlin.math.*

/**
 * Resultado de proyectar una dirección del mundo sobre la pantalla.
 */
data class Proyeccion(
    val x: Float,                // Píxeles desde el borde izquierdo
    val y: Float,                // Píxeles desde el borde superior
    val anguloAlCentro: Double   // Grados entre la dirección y el centro de la cámara
)

/**
 * Proyección de realidad aumentada: convierte una dirección del mundo
 * (bearing + ángulo de elevación) en un píxel de la pantalla, usando la
 * orientación completa del teléfono y el campo de visión real de la cámara.
 *
 * Convenciones (las mismas que SensorManager de Android):
 *   - Mundo:       X = Este, Y = Norte, Z = Arriba
 *   - Dispositivo: X = derecha, Y = arriba de la pantalla, Z = saliendo de la pantalla
 *   - La cámara trasera mira hacia -Z del dispositivo
 *   - La matriz de rotación R (9 elementos, por filas) cumple mundo = R * dispositivo,
 *     así que sus filas son los ejes Este, Norte y Arriba expresados en el dispositivo
 *
 * Todas las funciones son puras (sin dependencias de Android), aptas para tests.
 */
object ArProyeccion
{
    // Campo de visión típico de la cámara principal de un teléfono (formato 4:3),
    // usado solo si no se pueden leer las características reales de la cámara.
    private const val FOV_LARGO_POR_DEFECTO_GRADOS = 66.0
    private const val FOV_CORTO_POR_DEFECTO_GRADOS = 52.0

    // Un punto con menos de este coseno respecto al eje de la cámara está de lado o detrás
    private const val COSENO_MINIMO_FRENTE = 0.1

    /**
     * Vector unitario (Este, Norte, Arriba) de una dirección del mundo.
     */
    fun direccionMundo(bearingGrados: Double, elevacionGrados: Double): DoubleArray
    {
        val b = Math.toRadians(bearingGrados)
        val e = Math.toRadians(elevacionGrados)
        return doubleArrayOf(cos(e) * sin(b), cos(e) * cos(b), sin(e))
    }

    /**
     * Proyecta una dirección del mundo sobre la pantalla (modelo de cámara estenopeica).
     * Devuelve null si el punto queda detrás o muy de lado respecto a la cámara.
     * El resultado puede caer fuera de los límites de la pantalla.
     *
     * @param matrizRotacion Matriz R de 9 elementos (mundo = R * dispositivo)
     * @param focalPx        Distancia focal expresada en píxeles de pantalla
     */
    fun proyectar(
        matrizRotacion: FloatArray,
        bearingGrados: Double,
        elevacionGrados: Double,
        focalPx: Float,
        anchoPantalla: Int,
        altoPantalla: Int
    ): Proyeccion?
    {
        val v = direccionMundo(bearingGrados, elevacionGrados)

        // dispositivo = R^T * mundo
        val xDisp = matrizRotacion[0] * v[0] + matrizRotacion[3] * v[1] + matrizRotacion[6] * v[2]
        val yDisp = matrizRotacion[1] * v[0] + matrizRotacion[4] * v[1] + matrizRotacion[7] * v[2]
        val zDisp = matrizRotacion[2] * v[0] + matrizRotacion[5] * v[1] + matrizRotacion[8] * v[2]

        // Profundidad a lo largo del eje de la cámara (-Z del dispositivo)
        val profundidad = -zDisp
        if (profundidad < COSENO_MINIMO_FRENTE) return null

        val x = anchoPantalla / 2f + (focalPx * xDisp / profundidad).toFloat()
        val y = altoPantalla / 2f - (focalPx * yDisp / profundidad).toFloat()
        val angulo = Math.toDegrees(acos(profundidad.coerceIn(-1.0, 1.0)))

        return Proyeccion(x, y, angulo)
    }

    /**
     * Azimuth (0-360°, 0 = Norte) hacia donde apunta la cámara trasera.
     */
    fun azimuthCamara(matrizRotacion: FloatArray): Float
    {
        val este = -matrizRotacion[2].toDouble()
        val norte = -matrizRotacion[5].toDouble()
        return ((Math.toDegrees(atan2(este, norte)) + 360.0) % 360.0).toFloat()
    }

    /**
     * Pitch de la cámara trasera: 0° = horizonte, + = hacia el cielo, - = hacia el suelo.
     */
    fun pitchCamara(matrizRotacion: FloatArray): Float
    {
        val arriba = (-matrizRotacion[8].toDouble()).coerceIn(-1.0, 1.0)
        return Math.toDegrees(asin(arriba)).toFloat()
    }

    /**
     * Distancia focal en píxeles de pantalla para un preview en modo portrait
     * que llena la vista recortando lo que sobra (scaleType fillCenter).
     *
     * El lado largo del sensor corresponde al alto de la pantalla.
     * Asume que el preview usa el área completa del sensor (formato 4:3).
     */
    fun calcularFocalPx(
        focalMm: Float,
        sensorLadoLargoMm: Float,
        sensorLadoCortoMm: Float,
        anchoVista: Int,
        altoVista: Int
    ): Float
    {
        val escalaPxPorMm = max(altoVista / sensorLadoLargoMm, anchoVista / sensorLadoCortoMm)
        return focalMm * escalaPxPorMm
    }

    /**
     * Distancia focal en píxeles usando un campo de visión típico,
     * para cuando no se pueden leer los datos reales de la cámara.
     */
    fun calcularFocalPxPorDefecto(anchoVista: Int, altoVista: Int): Float
    {
        val ladoLargo = (2.0 * tan(Math.toRadians(FOV_LARGO_POR_DEFECTO_GRADOS / 2.0))).toFloat()
        val ladoCorto = (2.0 * tan(Math.toRadians(FOV_CORTO_POR_DEFECTO_GRADOS / 2.0))).toFloat()
        return calcularFocalPx(1f, ladoLargo, ladoCorto, anchoVista, altoVista)
    }

    /**
     * Construye la matriz de rotación a partir del vector de gravedad (acelerómetro)
     * y un azimuth externo (por ejemplo el rumbo del GPS). Se usa en teléfonos
     * sin brújula. Devuelve false si el vector de gravedad no es válido.
     */
    fun matrizDesdeGravedadYAzimuth(
        gx: Float,
        gy: Float,
        gz: Float,
        azimuthGrados: Float,
        salida: FloatArray
    ): Boolean
    {
        val norma = sqrt((gx * gx + gy * gy + gz * gz).toDouble())
        if (norma < 1e-3) return false

        // Eje Arriba expresado en el dispositivo
        val ux = gx / norma
        val uy = gy / norma
        val uz = gz / norma

        // Dirección de la cámara (0, 0, -1) proyectada al plano horizontal
        var fx = uz * ux
        var fy = uz * uy
        var fz = -1.0 + uz * uz
        var normaF = sqrt(fx * fx + fy * fy + fz * fz)

        if (normaF < 1e-3)
        {
            // Cámara apuntando al cenit o al suelo: usar el borde superior del teléfono
            fx = -uy * ux
            fy = 1.0 - uy * uy
            fz = -uy * uz
            normaF = sqrt(fx * fx + fy * fy + fz * fz)
            if (normaF < 1e-3) return false
        }

        fx /= normaF
        fy /= normaF
        fz /= normaF

        // Derecha = frente × arriba
        val rx = fy * uz - fz * uy
        val ry = fz * ux - fx * uz
        val rz = fx * uy - fy * ux

        val a = Math.toRadians(azimuthGrados.toDouble())
        val c = cos(a)
        val s = sin(a)

        // Este
        salida[0] = (fx * s + rx * c).toFloat()
        salida[1] = (fy * s + ry * c).toFloat()
        salida[2] = (fz * s + rz * c).toFloat()
        // Norte
        salida[3] = (fx * c - rx * s).toFloat()
        salida[4] = (fy * c - ry * s).toFloat()
        salida[5] = (fz * c - rz * s).toFloat()
        // Arriba
        salida[6] = ux.toFloat()
        salida[7] = uy.toFloat()
        salida[8] = uz.toFloat()

        return true
    }

    /**
     * Gira la matriz del norte magnético al norte verdadero.
     * La declinación es positiva cuando el norte magnético queda al este del verdadero.
     */
    fun aplicarDeclinacion(matriz: FloatArray, declinacionGrados: Float)
    {
        if (declinacionGrados == 0f) return

        val d = Math.toRadians(declinacionGrados.toDouble())
        val c = cos(d).toFloat()
        val s = sin(d).toFloat()

        for (i in 0..2)
        {
            val este = matriz[i]
            val norte = matriz[3 + i]
            matriz[i] = este * c + norte * s
            matriz[3 + i] = norte * c - este * s
        }
    }

    /**
     * Filtro paso-bajo sobre la matriz de rotación completa. Suavizar la matriz
     * (y no los ángulos por separado) evita saltos en 0°/360° y cerca del cenit.
     *
     * @param estado Matriz suavizada actual; se modifica en el lugar
     * @param nueva  Lectura nueva del sensor
     * @param alpha  Peso de la lectura nueva (0 = ignorar, 1 = sin filtro)
     */
    fun suavizarMatriz(estado: FloatArray, nueva: FloatArray, alpha: Float)
    {
        for (i in 0..8)
        {
            estado[i] = estado[i] + alpha * (nueva[i] - estado[i])
        }

        if (!ortonormalizar(estado))
        {
            // La mezcla degeneró (giro brusco de casi 180°): saltar a la lectura nueva
            System.arraycopy(nueva, 0, estado, 0, 9)
        }
    }

    /**
     * Devuelve la matriz a una rotación válida (filas unitarias y perpendiculares).
     * Devuelve false si la matriz está demasiado degenerada para repararla.
     */
    fun ortonormalizar(m: FloatArray): Boolean
    {
        // Fila Arriba
        var ux = m[6].toDouble()
        var uy = m[7].toDouble()
        var uz = m[8].toDouble()
        val normaU = sqrt(ux * ux + uy * uy + uz * uz)
        if (normaU < 1e-3) return false
        ux /= normaU
        uy /= normaU
        uz /= normaU

        // Fila Este, quitando su componente sobre Arriba
        var ex = m[0].toDouble()
        var ey = m[1].toDouble()
        var ez = m[2].toDouble()
        val punto = ex * ux + ey * uy + ez * uz
        ex -= punto * ux
        ey -= punto * uy
        ez -= punto * uz
        val normaE = sqrt(ex * ex + ey * ey + ez * ez)
        if (normaE < 1e-3) return false
        ex /= normaE
        ey /= normaE
        ez /= normaE

        // Fila Norte = Arriba × Este
        val nx = uy * ez - uz * ey
        val ny = uz * ex - ux * ez
        val nz = ux * ey - uy * ex

        m[0] = ex.toFloat(); m[1] = ey.toFloat(); m[2] = ez.toFloat()
        m[3] = nx.toFloat(); m[4] = ny.toFloat(); m[5] = nz.toFloat()
        m[6] = ux.toFloat(); m[7] = uy.toFloat(); m[8] = uz.toFloat()

        return true
    }
}
