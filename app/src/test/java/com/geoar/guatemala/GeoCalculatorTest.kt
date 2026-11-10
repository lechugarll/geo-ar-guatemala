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
        // Con curvatura: caída ≈ 149 m → atan((1052-149)/47000) ≈ 1.10°
        val angulo = GeoCalculator.calcularAnguloElevacion(47.0, 1500, 2552)
        assertEquals(1.10, angulo, 0.1)
    }

    @Test
    fun `curvatura terrestre hunde picos muy lejanos bajo el horizonte`()
    {
        // Tajumulco (4220 m) desde Ciudad de Guatemala (~1500 m) a ~210 km:
        // caída por curvatura ≈ 2966 m > diferencia de altura (2720 m)
        // → ángulo aparente negativo (bajo el horizonte)
        val angulo = GeoCalculator.calcularAnguloElevacion(210.0, 1500, 4220)
        assertTrue(angulo < 0.0)
    }

    // -------------------------------------------------------------------------
    // esRealisticamenteVisible
    // -------------------------------------------------------------------------

    private fun crearLugar(
        tipo: String,
        elevacionMsnm: Int,
        nombre: String = "Test"
    ) = com.geoar.guatemala.models.LugarGeografico(
        id = 0,
        nombre = nombre,
        tipo = tipo,
        latitud = 0.0,
        longitud = 0.0,
        elevacionMsnm = elevacionMsnm,
        descripcion = "",
        activo = false
    )

    @Test
    fun `sierra de Chuacus a 61 km no debe ser visible desde Ciudad de Guatemala`()
    {
        // Prominencia aparente ~0.4° < umbral lejano (0.6°) → invisible al ojo humano
        val sierra = crearLugar("sierra", 2178, "Sierra de Chuacús")
        val angulo = GeoCalculator.calcularAnguloElevacion(61.0, 1500, 2178)
        assertTrue(!GeoCalculator.esRealisticamenteVisible(sierra, 61.0, angulo, 1500))
    }

    @Test
    fun `volcan de Agua a 30 km debe ser visible desde Ciudad de Guatemala`()
    {
        val agua = crearLugar("volcan", 3766, "Volcán de Agua")
        val angulo = GeoCalculator.calcularAnguloElevacion(30.0, 1500, 3766)
        assertTrue(GeoCalculator.esRealisticamenteVisible(agua, 30.0, angulo, 1500))
    }

    @Test
    fun `volcanes de Atitlan a 85 km deben ser visibles por su prominencia`()
    {
        // Picos grandes sobresalen ~1° incluso a 85 km → visibles en día claro
        val atitlan = crearLugar("volcan", 3537, "Volcán Atitlán")
        val angulo = GeoCalculator.calcularAnguloElevacion(85.0, 1500, 3537)
        assertTrue(GeoCalculator.esRealisticamenteVisible(atitlan, 85.0, angulo, 1500))
    }

    @Test
    fun `cerro cercano al mismo nivel debe ser visible`()
    {
        val cerro = crearLugar("cerro", 1500)
        val angulo = GeoCalculator.calcularAnguloElevacion(11.0, 1500, 1500)
        assertTrue(GeoCalculator.esRealisticamenteVisible(cerro, 11.0, angulo, 1500))
    }

    @Test
    fun `lago lejano no debe ser visible si el usuario no esta elevado`()
    {
        // Lago de Atitlán (1562 m) desde Ciudad de Guatemala (1500 m) a ~70 km:
        // el usuario está por debajo del espejo de agua → invisible
        val lago = crearLugar("lago", 1562, "Lago de Atitlán")
        val angulo = GeoCalculator.calcularAnguloElevacion(70.0, 1500, 1562)
        assertTrue(!GeoCalculator.esRealisticamenteVisible(lago, 70.0, angulo, 1500))
    }

    @Test
    fun `lago debe ser visible desde la orilla`()
    {
        val lago = crearLugar("lago", 1562, "Lago de Atitlán")
        val angulo = GeoCalculator.calcularAnguloElevacion(3.0, 1573, 1562)
        assertTrue(GeoCalculator.esRealisticamenteVisible(lago, 3.0, angulo, 1573))
    }

    @Test
    fun `lago debe ser visible desde un mirador elevado`()
    {
        // Usuario en un volcán a 2500 m viendo el lago (1562 m) a 15 km
        val lago = crearLugar("lago", 1562, "Lago de Atitlán")
        val angulo = GeoCalculator.calcularAnguloElevacion(15.0, 2500, 1562)
        assertTrue(GeoCalculator.esRealisticamenteVisible(lago, 15.0, angulo, 2500))
    }

    // -------------------------------------------------------------------------
    // estaOcluido — oclusión por relieve intermedio
    // -------------------------------------------------------------------------

    private fun crearLugarEn(
        id: Int,
        tipo: String,
        lat: Double,
        lon: Double,
        elevacionMsnm: Int,
        nombre: String = "Test"
    ) = com.geoar.guatemala.models.LugarGeografico(
        id = id,
        nombre = nombre,
        tipo = tipo,
        latitud = lat,
        longitud = lon,
        elevacionMsnm = elevacionMsnm,
        descripcion = "",
        activo = false
    )

    // Geometría compartida: usuario en (14.6, -90.5), objetivo volcán de 3766 m
    // a ~33 km al norte, obstáculo cerro de 3000 m a ~11 km al norte (misma línea).
    private val objetivoNorte = crearLugarEn(1, "volcan", 14.9, -90.5, 3766, "Volcán objetivo")
    private val cerroIntermedio = crearLugarEn(2, "cerro", 14.7, -90.5, 3000, "Cerro intermedio")

    @Test
    fun `volcan tapado por cerro intermedio desde el valle`()
    {
        // Usuario bajo (1500 m): ángulo al obstáculo 7.6° > ángulo al objetivo 3.8°
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val bearing = GeoCalculator.calcularBearing(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 1500, 3766)
        assertTrue(GeoCalculator.estaOcluido(
            14.6, -90.5, 1500, objetivoNorte, dist, bearing, angulo,
            listOf(objetivoNorte, cerroIntermedio)))
    }

    @Test
    fun `volcan visible desde mirador elevado por encima del cerro intermedio`()
    {
        // Caso mirador Carretera a El Salvador: usuario a 3000 m, su línea de
        // visión pasa por encima del obstáculo (ángulo obstáculo ≈ 0° < 1.2°)
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val bearing = GeoCalculator.calcularBearing(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 3000, 3766)
        assertTrue(!GeoCalculator.estaOcluido(
            14.6, -90.5, 3000, objetivoNorte, dist, bearing, angulo,
            listOf(objetivoNorte, cerroIntermedio)))
    }

    @Test
    fun `cerro fuera de la linea de vision no ocluye`()
    {
        // Obstáculo alto pero al noreste (bearing ~55°), objetivo al norte (0°)
        val cerroAlEste = crearLugarEn(3, "cerro", 14.7, -90.35, 3000, "Cerro al este")
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val bearing = GeoCalculator.calcularBearing(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 1500, 3766)
        assertTrue(!GeoCalculator.estaOcluido(
            14.6, -90.5, 1500, objetivoNorte, dist, bearing, angulo,
            listOf(objetivoNorte, cerroAlEste)))
    }

    @Test
    fun `los lagos nunca ocluyen aunque esten en la linea de vision`()
    {
        // Mismo punto que el cerro intermedio pero tipo lago → se ignora
        val lagoIntermedio = crearLugarEn(4, "lago", 14.7, -90.5, 3000, "Lago intermedio")
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val bearing = GeoCalculator.calcularBearing(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 1500, 3766)
        assertTrue(!GeoCalculator.estaOcluido(
            14.6, -90.5, 1500, objetivoNorte, dist, bearing, angulo,
            listOf(objetivoNorte, lagoIntermedio)))
    }

    // -------------------------------------------------------------------------
    // estaOcluidoPorTerreno — oclusión con DEM sintético
    // -------------------------------------------------------------------------

    // Terreno sintético: cordón de 3000 m cruzando la línea de visión
    // (lat 14.68–14.72, a ~9-13 km del usuario), valle de 800 m en el resto
    private val demConCordon = ModeloElevacion { lat, _ ->
        if (lat in 14.68..14.72) 3000.0 else 800.0
    }

    private val demPlano = ModeloElevacion { _, _ -> 800.0 }

    @Test
    fun `DEM - cordon intermedio tapa el volcan desde el valle`()
    {
        // Usuario a 1500 m: ángulo al volcán 3.8°, el cordón sobresale 7.6°
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 1500, 3766)
        assertTrue(GeoCalculator.estaOcluidoPorTerreno(
            14.6, -90.5, 1500, 14.9, -90.5, dist, angulo, demConCordon))
    }

    @Test
    fun `DEM - desde un mirador elevado la linea de vision pasa sobre el cordon`()
    {
        // Usuario a 3200 m: el cordón queda bajo el horizonte (-1.1° < 0.84°)
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 3200, 3766)
        assertTrue(!GeoCalculator.estaOcluidoPorTerreno(
            14.6, -90.5, 3200, 14.9, -90.5, dist, angulo, demConCordon))
    }

    @Test
    fun `DEM - terreno plano no ocluye nada`()
    {
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 1500, 3766)
        assertTrue(!GeoCalculator.estaOcluidoPorTerreno(
            14.6, -90.5, 1500, 14.9, -90.5, dist, angulo, demPlano))
    }

    @Test
    fun `DEM - la falda del propio volcan objetivo no se cuenta como obstaculo`()
    {
        // Terreno que sube hacia el objetivo (cono): a 92-100% de la distancia
        // está la falda alta, pero el muestreo se detiene al 92%
        val demCono = ModeloElevacion { lat, _ ->
            val fraccion = ((lat - 14.6) / 0.3).coerceIn(0.0, 1.0)
            800.0 + (3766.0 - 800.0) * fraccion * fraccion
        }
        val dist = GeoCalculator.calcularDistanciaKm(14.6, -90.5, 14.9, -90.5)
        val angulo = GeoCalculator.calcularAnguloElevacion(dist, 1500, 3766)
        assertTrue(!GeoCalculator.estaOcluidoPorTerreno(
            14.6, -90.5, 1500, 14.9, -90.5, dist, angulo, demCono))
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
