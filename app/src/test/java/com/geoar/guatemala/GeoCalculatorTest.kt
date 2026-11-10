package com.geoar.guatemala

import com.geoar.guatemala.models.LugarGeografico
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests unitarios para GeoCalculator.
 * Verifican la corrección de bearing, distancia Haversine, ángulo de elevación
 * (con curvatura terrestre) y el cálculo de candidatos visibles.
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
    fun `distancia de Ciudad de Guatemala a Volcan Pacaya debe ser aproximadamente 30 km`()
    {
        // Ciudad de Guatemala aprox 14.6349, -90.5069
        // Volcán Pacaya: 14.3814, -90.6014
        val distancia = GeoCalculator.calcularDistanciaKm(
            14.6349, -90.5069,
            14.3814, -90.6014
        )
        assertEquals(30.0, distancia, 2.0) // tolerancia ±2 km
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
    // Curvatura de la Tierra y horizonte
    // -------------------------------------------------------------------------

    @Test
    fun `la curvatura debe bajar el angulo de un punto lejano a la misma altura`()
    {
        // A 100 km y a la misma elevación, el punto queda bajo la horizontal
        val angulo = GeoCalculator.calcularAnguloElevacion(100.0, 1500, 1500)
        assertTrue(angulo < -0.3 && angulo > -0.5)
    }

    @Test
    fun `la curvatura casi no afecta a un punto cercano`()
    {
        // A 5 km la caída por curvatura es de menos de 2 m
        val angulo = GeoCalculator.calcularAnguloElevacion(5.0, 1500, 2500)
        assertEquals(11.3, angulo, 0.1)
    }

    @Test
    fun `punto cercano nunca queda oculto por la curvatura`()
    {
        // Desde 2500 m el horizonte está a ~190 km: un lago a 10 km se ve aunque esté más abajo
        assertTrue(!GeoCalculator.estaOcultoPorCurvatura(10.0, 2500, 1562))
    }

    @Test
    fun `punto bajo y lejano queda oculto por la curvatura`()
    {
        // Desde 2 m de altura el horizonte está a ~5 km; a 140 km solo se ve lo que pase de ~1200 m
        assertTrue(GeoCalculator.estaOcultoPorCurvatura(140.0, 2, 200))
        assertTrue(!GeoCalculator.estaOcultoPorCurvatura(140.0, 2, 3760))
    }

    // -------------------------------------------------------------------------
    // esPotencialmenteVisible
    // -------------------------------------------------------------------------

    @Test
    fun `lago lejano a mayor o igual altura que el usuario no es visible`()
    {
        // Atitlán (1562 msnm) desde Ciudad de Guatemala (1500 msnm) a ~75 km
        assertTrue(!GeoCalculator.esPotencialmenteVisible("lago", 75.0, 1500, 1562))
    }

    @Test
    fun `lago visto desde arriba si es visible`()
    {
        // Atitlán desde un mirador a 2500 msnm a 10 km
        assertTrue(GeoCalculator.esPotencialmenteVisible("lago", 10.0, 2500, 1562))
    }

    @Test
    fun `lago muy cercano siempre es visible`()
    {
        assertTrue(GeoCalculator.esPotencialmenteVisible("laguna", 2.0, 1565, 1562))
    }

    @Test
    fun `lugar tras la curvatura no es visible`()
    {
        assertTrue(!GeoCalculator.esPotencialmenteVisible("cerro", 140.0, 2, 200))
    }

    @Test
    fun `cerro lejano por debajo de la horizontal no se etiqueta`()
    {
        // Cerro de 900 m a 110 km visto desde 1500 m: queda bajo la horizontal
        assertTrue(!GeoCalculator.esPotencialmenteVisible("cerro", 110.0, 1500, 900))
        // El mismo cerro de cerca sí se etiqueta
        assertTrue(GeoCalculator.esPotencialmenteVisible("cerro", 8.0, 1500, 900))
    }

    @Test
    fun `volcan lejano que sobresale sobre la horizontal si se etiqueta`()
    {
        // Volcán de Agua (3760 m) a 32 km y Santa María (3772 m) a 113 km desde la capital
        assertTrue(GeoCalculator.esPotencialmenteVisible("volcan", 32.0, 1500, 3760))
        assertTrue(GeoCalculator.esPotencialmenteVisible("volcan", 113.0, 1500, 3772))
    }

    @Test
    fun `lugar bajo los pies del usuario no se etiqueta`()
    {
        assertTrue(!GeoCalculator.esPotencialmenteVisible("volcan", 0.05, 3760, 3763))
    }

    // -------------------------------------------------------------------------
    // calcularCandidatos — integración
    // -------------------------------------------------------------------------

    private fun lugar(
        nombre: String,
        tipo: String,
        latitud: Double,
        longitud: Double,
        elevacion: Int
    ): LugarGeografico
    {
        return LugarGeografico(
            id = nombre.hashCode(),
            nombre = nombre,
            tipo = tipo,
            latitud = latitud,
            longitud = longitud,
            elevacionMsnm = elevacion,
            descripcion = "Lugar de prueba",
            activo = false
        )
    }

    @Test
    fun `punto fuera del rango maximo no debe aparecer en candidatos`()
    {
        val lugarLejano = lugar("Lugar muy lejano", "cerro", 20.0, -90.0, 1000)

        val resultado = GeoCalculator.calcularCandidatos(
            latUsuario = 14.634915,
            lonUsuario = -90.506882,
            elevacionUsuarioM = 1500,
            todos = listOf(lugarLejano)
        )

        assertTrue(resultado.isEmpty())
    }

    @Test
    fun `candidatos deben venir ordenados por distancia con sus datos calculados`()
    {
        val agua = lugar("Volcán de Agua", "volcan", 14.4655, -90.7430, 3760)
        val pacaya = lugar("Volcán Pacaya", "volcan", 14.3813, -90.6011, 2552)
        val tajumulco = lugar("Volcán Tajumulco", "volcan", 15.0342, -91.9003, 4220)

        val resultado = GeoCalculator.calcularCandidatos(
            latUsuario = 14.634915,
            lonUsuario = -90.506882,
            elevacionUsuarioM = 1500,
            todos = listOf(tajumulco, pacaya, agua)
        )

        // Tajumulco está a ~156 km: fuera del rango de 150 km
        assertEquals(2, resultado.size)
        assertEquals("Volcán Pacaya", resultado[0].lugar.nombre)
        assertEquals("Volcán de Agua", resultado[1].lugar.nombre)

        // Agua queda al suroeste de la capital y por encima del horizonte
        val candidatoAgua = resultado[1]
        assertTrue(candidatoAgua.bearing > 220.0 && candidatoAgua.bearing < 250.0)
        assertTrue(candidatoAgua.anguloElevacion > 3.0 && candidatoAgua.anguloElevacion < 6.0)
    }

    // -------------------------------------------------------------------------
    // puntoCardinal
    // -------------------------------------------------------------------------

    @Test
    fun `punto cardinal debe corresponder al azimuth`()
    {
        assertEquals("N", GeoCalculator.puntoCardinal(0.0))
        assertEquals("N", GeoCalculator.puntoCardinal(359.0))
        assertEquals("NE", GeoCalculator.puntoCardinal(45.0))
        assertEquals("E", GeoCalculator.puntoCardinal(90.0))
        assertEquals("S", GeoCalculator.puntoCardinal(180.0))
        assertEquals("SO", GeoCalculator.puntoCardinal(225.0))
        assertEquals("O", GeoCalculator.puntoCardinal(270.0))
        assertEquals("NO", GeoCalculator.puntoCardinal(-45.0))
    }
}
