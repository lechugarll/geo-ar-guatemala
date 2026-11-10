package com.geoar.guatemala

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Tests unitarios para ArProyeccion.
 * Verifican que una dirección del mundo cae en el píxel correcto según la
 * orientación del teléfono y el campo de visión de la cámara.
 *
 * Ejecutar con: ./gradlew test
 */
class ArProyeccionTest
{
    private val ancho = 1080
    private val alto = 2400
    private val focalPx = 1500f

    // Teléfono vertical (portrait) con la cámara trasera mirando al norte:
    //   X del dispositivo = Este, Y = Arriba, Z = Sur (hacia el usuario)
    // Filas de R = ejes Este, Norte y Arriba expresados en el dispositivo.
    private fun matrizMirandoAlNorte(): FloatArray
    {
        return floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
    }

    private fun matrizVertical(azimuth: Float): FloatArray
    {
        val m = FloatArray(9)
        assertTrue(ArProyeccion.matrizDesdeGravedadYAzimuth(0f, 9.81f, 0f, azimuth, m))
        return m
    }

    // -------------------------------------------------------------------------
    // proyectar
    // -------------------------------------------------------------------------

    @Test
    fun `punto justo al frente debe caer en el centro de la pantalla`()
    {
        val p = ArProyeccion.proyectar(matrizMirandoAlNorte(), 0.0, 0.0, focalPx, ancho, alto)
        assertNotNull(p)
        assertEquals(540f, p!!.x, 0.5f)
        assertEquals(1200f, p.y, 0.5f)
        assertEquals(0.0, p.anguloAlCentro, 0.01)
    }

    @Test
    fun `punto 10 grados a la derecha debe caer a la derecha segun el campo de vision`()
    {
        val p = ArProyeccion.proyectar(matrizMirandoAlNorte(), 10.0, 0.0, focalPx, ancho, alto)!!
        val esperado = 540f + focalPx * tan(Math.toRadians(10.0)).toFloat()
        assertEquals(esperado, p.x, 0.5f)
        assertEquals(1200f, p.y, 0.5f)
        assertEquals(10.0, p.anguloAlCentro, 0.01)
    }

    @Test
    fun `punto a la izquierda debe caer a la izquierda del centro`()
    {
        val p = ArProyeccion.proyectar(matrizMirandoAlNorte(), 350.0, 0.0, focalPx, ancho, alto)!!
        assertTrue(p.x < 540f)
    }

    @Test
    fun `punto sobre el horizonte debe caer arriba del centro`()
    {
        val p = ArProyeccion.proyectar(matrizMirandoAlNorte(), 0.0, 10.0, focalPx, ancho, alto)!!
        val esperado = 1200f - focalPx * tan(Math.toRadians(10.0)).toFloat()
        assertEquals(540f, p.x, 0.5f)
        assertEquals(esperado, p.y, 0.5f)
    }

    @Test
    fun `punto detras de la camara no se proyecta`()
    {
        assertNull(ArProyeccion.proyectar(matrizMirandoAlNorte(), 180.0, 0.0, focalPx, ancho, alto))
        assertNull(ArProyeccion.proyectar(matrizMirandoAlNorte(), 90.0, 0.0, focalPx, ancho, alto))
    }

    @Test
    fun `camara apuntando exactamente al lugar lo deja en el centro`()
    {
        // Teléfono mirando al suroeste e inclinado 4.5° hacia arriba
        val azimuth = 232.0
        val inclinacion = Math.toRadians(4.5)
        val m = FloatArray(9)
        ArProyeccion.matrizDesdeGravedadYAzimuth(
            0f,
            (9.81 * cos(inclinacion)).toFloat(),
            (-9.81 * sin(inclinacion)).toFloat(),
            azimuth.toFloat(),
            m
        )

        val p = ArProyeccion.proyectar(m, azimuth, 4.5, focalPx, ancho, alto)!!
        assertEquals(540f, p.x, 1f)
        assertEquals(1200f, p.y, 1f)
    }

    @Test
    fun `con el telefono acostado la proyeccion gira junto con la pantalla`()
    {
        // Teléfono girado 90° (horizontal): el eje X del dispositivo apunta hacia arriba.
        // Un punto a la derecha en el mundo debe moverse sobre el eje vertical de la pantalla.
        val m = FloatArray(9)
        assertTrue(ArProyeccion.matrizDesdeGravedadYAzimuth(9.81f, 0f, 0f, 0f, m))

        val p = ArProyeccion.proyectar(m, 10.0, 0.0, focalPx, ancho, alto)!!
        assertEquals(540f, p.x, 1f)
        assertTrue(abs(p.y - 1200f) > 200f)

        // Y un punto sobre el horizonte se mueve sobre el eje horizontal de la pantalla
        val q = ArProyeccion.proyectar(m, 0.0, 10.0, focalPx, ancho, alto)!!
        assertEquals(1200f, q.y, 1f)
        assertTrue(q.x > 540f + 200f)
    }

    // -------------------------------------------------------------------------
    // azimuth y pitch de la cámara
    // -------------------------------------------------------------------------

    @Test
    fun `azimuth y pitch de la matriz mirando al norte`()
    {
        val m = matrizMirandoAlNorte()
        assertEquals(0f, ArProyeccion.azimuthCamara(m), 0.01f)
        assertEquals(0f, ArProyeccion.pitchCamara(m), 0.01f)
    }

    @Test
    fun `matriz desde gravedad y azimuth coincide con la matriz teorica`()
    {
        val teorica = matrizMirandoAlNorte()
        val calculada = matrizVertical(0f)
        for (i in 0..8)
        {
            assertEquals(teorica[i], calculada[i], 0.001f)
        }
    }

    @Test
    fun `azimuth de la camara debe coincidir con el azimuth dado`()
    {
        for (azimuth in listOf(0f, 45f, 90f, 180f, 232f, 359f))
        {
            assertEquals(azimuth, ArProyeccion.azimuthCamara(matrizVertical(azimuth)), 0.01f)
        }
    }

    @Test
    fun `inclinar la camara hacia el cielo da pitch positivo`()
    {
        val inclinacion = Math.toRadians(30.0)
        val m = FloatArray(9)
        ArProyeccion.matrizDesdeGravedadYAzimuth(
            0f,
            (9.81 * cos(inclinacion)).toFloat(),
            (-9.81 * sin(inclinacion)).toFloat(),
            90f,
            m
        )
        assertEquals(30f, ArProyeccion.pitchCamara(m), 0.01f)
        assertEquals(90f, ArProyeccion.azimuthCamara(m), 0.01f)
    }

    @Test
    fun `vector de gravedad nulo no produce matriz`()
    {
        assertTrue(!ArProyeccion.matrizDesdeGravedadYAzimuth(0f, 0f, 0f, 0f, FloatArray(9)))
    }

    // -------------------------------------------------------------------------
    // Declinación magnética
    // -------------------------------------------------------------------------

    @Test
    fun `declinacion positiva gira el azimuth hacia el este`()
    {
        // La cámara apunta al norte magnético; con declinación +5° eso es 5° verdadero
        val m = matrizMirandoAlNorte()
        ArProyeccion.aplicarDeclinacion(m, 5f)
        assertEquals(5f, ArProyeccion.azimuthCamara(m), 0.01f)
        assertEquals(0f, ArProyeccion.pitchCamara(m), 0.01f)
    }

    @Test
    fun `declinacion negativa gira el azimuth hacia el oeste`()
    {
        val m = matrizMirandoAlNorte()
        ArProyeccion.aplicarDeclinacion(m, -3f)
        assertEquals(357f, ArProyeccion.azimuthCamara(m), 0.01f)
    }

    // -------------------------------------------------------------------------
    // Campo de visión
    // -------------------------------------------------------------------------

    @Test
    fun `focal en pixeles segun el sensor y la pantalla`()
    {
        // Sensor 6.4 x 4.8 mm con lente de 4 mm en pantalla 1080 x 2400:
        // la imagen se escala para llenar el alto (2400 px / 6.4 mm = 375 px/mm)
        val focal = ArProyeccion.calcularFocalPx(4f, 6.4f, 4.8f, 1080, 2400)
        assertEquals(1500f, focal, 0.5f)
    }

    @Test
    fun `focal por defecto da un campo de vision razonable`()
    {
        val focal = ArProyeccion.calcularFocalPxPorDefecto(1080, 2400)
        // Campo de visión vertical de 66° sobre 2400 px
        assertEquals(1200f / tan(Math.toRadians(33.0)).toFloat(), focal, 1f)
    }

    // -------------------------------------------------------------------------
    // Suavizado
    // -------------------------------------------------------------------------

    private fun esRotacionValida(m: FloatArray): Boolean
    {
        for (fila in 0..2)
        {
            val norma = sqrt(
                m[fila * 3] * m[fila * 3] +
                m[fila * 3 + 1] * m[fila * 3 + 1] +
                m[fila * 3 + 2] * m[fila * 3 + 2]
            )
            if (abs(norma - 1f) > 0.001f) return false
        }
        val esteNorte = m[0] * m[3] + m[1] * m[4] + m[2] * m[5]
        val esteArriba = m[0] * m[6] + m[1] * m[7] + m[2] * m[8]
        return abs(esteNorte) < 0.001f && abs(esteArriba) < 0.001f
    }

    @Test
    fun `suavizado converge a la orientacion nueva sin dejar de ser una rotacion`()
    {
        val estado = matrizVertical(350f)
        val objetivo = matrizVertical(20f)

        ArProyeccion.suavizarMatriz(estado, objetivo, 0.1f)
        assertTrue(esRotacionValida(estado))

        // Tras el primer paso el azimuth avanza hacia 20° cruzando por 0° (no por 180°)
        val trasUnPaso = ArProyeccion.azimuthCamara(estado)
        assertTrue(trasUnPaso > 350f && trasUnPaso < 360f)

        repeat(100) { ArProyeccion.suavizarMatriz(estado, objetivo, 0.1f) }
        assertTrue(esRotacionValida(estado))
        assertEquals(20f, ArProyeccion.azimuthCamara(estado), 0.1f)
    }

    @Test
    fun `suavizado con giro de 180 grados no produce una matriz invalida`()
    {
        val estado = matrizVertical(0f)
        val objetivo = matrizVertical(180f)

        repeat(60) {
            ArProyeccion.suavizarMatriz(estado, objetivo, 0.5f)
            assertTrue(esRotacionValida(estado))
        }
        assertEquals(180f, ArProyeccion.azimuthCamara(estado), 0.5f)
    }
}
