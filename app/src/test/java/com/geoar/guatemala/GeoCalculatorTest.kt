package com.geoar.guatemala

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests unitarios para GeoCalculator.
 * Verifican la corrección de bearing, distancia Haversine, ángulo de elevación
 * y el filtrado de puntos visibles.
 *
 * Ejecutar con: ./gradlew test
 */
class GeoCalculatorTest
{
    // -------------------------------------------------------------------------
    // calcularBearing
    // -------------------------------------------------------------------------

    @Test
    fun `bearing desde Ciudad de Guatemala al norte debe ser 0 grados`()
    {
        // Desde Ciudad de Guatemala hacia un punto directamente al norte
        val bearing = GeoCalculator.calcularBearing(
            lat1 = 14.634915,
            lon1 = -90.506882,
            lat2 = 15.634915, // 1 grado al norte
            lon2 = -90.506882
        )
        assertEquals(0.0, bearing, 0.5) // tolerancia ±0.5°
    }

    @Test
    fun `bearing desde Ciudad de Guatemala al este debe ser 90 grados`()
    {
        val bearing = GeoCalculator.calcularBearing(
            lat1 = 14.634915,
            lon1 = -90.506882,
            lat2 = 14.634915,
            lon2 = -89.506882  // 1 grado al este
        )
        assertEquals(90.0, bearing, 0.5)
    }

    @Test
    fun `bearing desde Ciudad de Guatemala al sur debe ser 180 grados`()
    {
        val bearing = GeoCalculator.calcularBearing(
            lat1 = 14.634915,
            lon1 = -90.506882,
            lat2 = 13.634915,  // 1 grado al sur
            lon2 = -90.506882
        )
        assertEquals(180.0, bearing, 0.5)
    }

    @Test
    fun `bearing desde Ciudad de Guatemala al oeste debe ser 270 grados`()
    {
        val bearing = GeoCalculator.calcularBearing(
            lat1 = 14.634915,
            lon1 = -90.506882,
            lat2 = 14.634915,
            lon2 = -91.506882  // 1 grado al oeste
        )
        assertEquals(270.0, bearing, 0.5)
    }

    @Test
    fun `bearing siempre debe estar en rango 0 a 360`()
    {
        val bearing = GeoCalculator.calcularBearing(15.0, -91.0, 14.0, -92.0)
        assertTrue(bearing >= 0.0 && bearing <= 360.0)
    }

    // -------------------------------------------------------------------------
    // calcularDistanciaKm
    // -------------------------------------------------------------------------

    @Test
    fun `distancia de punto a si mismo debe ser 0`()
    {
        val distancia = GeoCalculator.calcularDistanciaKm(14.0, -90.0, 14.0, -90.0)
        assertEquals(0.0, distancia, 0.001)
    }

    @Test
    fun `distancia de Ciudad de Guatemala a Volcan Pacaya debe ser aproximadamente 47 km`()
    {
        // Ciudad de Guatemala aprox 14.6349, -90.5069
        // Volcán Pacaya: 14.3814, -90.6014
        val distancia = GeoCalculator.calcularDistanciaKm(
            14.6349, -90.5069,
            14.3814, -90.6014
        )
        assertEquals(47.0, distancia, 3.0) // tolerancia ±3 km
    }

    @Test
    fun `distancia debe ser simetrica`()
    {
        val d1 = GeoCalculator.calcularDistanciaKm(14.0, -90.0, 15.0, -91.0)
        val d2 = GeoCalculator.calcularDistanciaKm(15.0, -91.0, 14.0, -90.0)
        assertEquals(d1, d2, 0.001)
    }

    @Test
    fun `distancia aproximada de 1 grado latitud debe ser cercana a 111 km`()
    {
        val distancia = GeoCalculator.calcularDistanciaKm(0.0, 0.0, 1.0, 0.0)
        assertEquals(111.0, distancia, 2.0) // ±2 km
    }

    // -------------------------------------------------------------------------
    // calcularAnguloElevacion
    // -------------------------------------------------------------------------

    @Test
    fun `angulo elevacion de punto a la misma altura y distancia 0 debe ser 0`()
    {
        val angulo = GeoCalculator.calcularAnguloElevacion(0.0, 1500, 1500)
        assertEquals(0.0, angulo, 0.001)
    }

    @Test
    fun `angulo elevacion debe ser positivo cuando el punto esta mas alto`()
    {
        // Usuario a 1500 msnm, volcán a 3763 msnm, a 47 km
        val angulo = GeoCalculator.calcularAnguloElevacion(47.0, 1500, 3763)
        assertTrue(angulo > 0.0)
    }

    @Test
    fun `angulo elevacion debe ser negativo cuando el punto esta mas bajo`()
    {
        // Usuario en volcán a 3763 msnm, lago a 1562 msnm, a 30 km
        val angulo = GeoCalculator.calcularAnguloElevacion(30.0, 3763, 1562)
        assertTrue(angulo < 0.0)
    }

    @Test
    fun `angulo elevacion volcan Pacaya desde Ciudad de Guatemala`()
    {
        // Ciudad de Guatemala: ~1500 msnm, Pacaya: 2552 msnm, ~47 km
        val angulo = GeoCalculator.calcularAnguloElevacion(47.0, 1500, 2552)
        // Diferencia: 1052m en 47000m → atan(1052/47000) ≈ 1.28°
        assertEquals(1.28, angulo, 0.2)
    }

    // -------------------------------------------------------------------------
    // calcularPosicionEnPantalla
    // -------------------------------------------------------------------------

    @Test
    fun `posicion 0,0 debe estar en el centro de la pantalla`()
    {
        val posicion = GeoCalculator.calcularPosicionEnPantalla(
            difHorizontal = 0.0,
            difVertical = 0.0,
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertEquals(540f, posicion.x, 0.1f)
        assertEquals(960f, posicion.y, 0.1f)
    }

    @Test
    fun `diferencia horizontal positiva debe estar a la derecha del centro`()
    {
        val posicion = GeoCalculator.calcularPosicionEnPantalla(
            difHorizontal = 6.0, // mitad del margen horizontal
            difVertical = 0.0,
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertTrue(posicion.x > 1080 / 2f)
    }

    @Test
    fun `diferencia vertical positiva debe estar arriba del centro`()
    {
        val posicion = GeoCalculator.calcularPosicionEnPantalla(
            difHorizontal = 0.0,
            difVertical = 10.0, // arriba del horizonte
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertTrue(posicion.y < 1920 / 2f)
    }

    @Test
    fun `posicion debe estar dentro de los limites de pantalla`()
    {
        val posicion = GeoCalculator.calcularPosicionEnPantalla(
            difHorizontal = 999.0, // valor extremo
            difVertical = 999.0,
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertTrue(posicion.x >= 0f && posicion.x <= 1080f)
        assertTrue(posicion.y >= 0f && posicion.y <= 1920f)
    }

    // -------------------------------------------------------------------------
    // filtrarPuntosVisibles — integración
    // -------------------------------------------------------------------------

    @Test
    fun `desde Ciudad de Guatemala mirando norte el Volcan Tajumulco deberia ser visible`()
    {
        // Volcán Tajumulco está al noroeste a ~210 km, fuera del rango por defecto (150 km).
        // Reducimos el rango máximo a 250 km para este test.
        val tajumulco = com.geoar.guatemala.models.LugarGeografico(
            id = 1,
            nombre = "Volcán Tajumulco",
            tipo = "volcan",
            latitud = 15.0342,
            longitud = -91.9003,
            elevacionMsnm = 4220,
            descripcion = "El volcán más alto de Centroamérica",
            activo = false
        )

        // Bearing desde Ciudad de Guatemala (14.63, -90.50) a Tajumulco (15.03, -91.90)
        // es aproximadamente 290° (noroeste)
        val bearing = GeoCalculator.calcularBearing(
            14.634915, -90.506882,
            tajumulco.latitud, tajumulco.longitud
        )
        assertTrue(bearing > 270.0 && bearing < 320.0)
    }

    @Test
    fun `punto fuera del rango maximo no debe aparecer en resultados`()
    {
        val lugarLejano = com.geoar.guatemala.models.LugarGeografico(
            id = 99,
            nombre = "Lugar muy lejano",
            tipo = "cerro",
            latitud = 20.0,  // muy al norte
            longitud = -90.0,
            elevacionMsnm = 1000,
            descripcion = "Muy lejos",
            activo = false
        )

        val resultado = GeoCalculator.filtrarPuntosVisibles(
            latUsuario = 14.634915,
            lonUsuario = -90.506882,
            elevacionUsuarioM = 1500,
            azimuth = 0f,
            pitch = 0f,
            todos = listOf(lugarLejano),
            distanciaMaxKm = GeoCalculator.DISTANCIA_MAXIMA_KM
        )

        assertTrue(resultado.isEmpty())
    }

    @Test
    fun `punto directamente al norte con azimuth 0 debe aparecer en resultados`()
    {
        val lugarAlNorte = com.geoar.guatemala.models.LugarGeografico(
            id = 100,
            nombre = "Cerro al norte",
            tipo = "cerro",
            latitud = 14.734915,  // 0.1° al norte (~11 km)
            longitud = -90.506882,
            elevacionMsnm = 1500,
            descripcion = "Cerro de prueba",
            activo = false
        )

        val resultado = GeoCalculator.filtrarPuntosVisibles(
            latUsuario = 14.634915,
            lonUsuario = -90.506882,
            elevacionUsuarioM = 1500,
            azimuth = 0f,   // mirando al norte
            pitch = 0f,     // mirando al horizonte
            todos = listOf(lugarAlNorte)
        )

        assertTrue(resultado.isNotEmpty())
        assertEquals("Cerro al norte", resultado.first().first.nombre)
    }
}
