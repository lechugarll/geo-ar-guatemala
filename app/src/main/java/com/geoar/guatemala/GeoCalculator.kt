package com.geoar.guatemala

import com.geoar.guatemala.models.LugarGeografico
import kotlin.math.*

/**
 * Lugar geográfico con sus datos calculados respecto a la posición del usuario.
 * Solo depende del GPS (no de la orientación), por lo que se recalcula
 * únicamente cuando cambia la ubicación.
 */
data class Candidato(
    val lugar: LugarGeografico,
    val bearing: Double,         // Dirección hacia el punto (0-360°, 0 = Norte verdadero)
    val distanciaKm: Double,     // Distancia en kilómetros
    val anguloElevacion: Double  // Ángulo vertical respecto al horizonte (+ = arriba)
)

/**
 * Módulo de cálculo geográfico.
 * Todas las funciones son puras (sin estado ni dependencias de Android),
 * aptas para tests unitarios en la JVM.
 */
object GeoCalculator
{
    private const val RADIO_TIERRA_KM = 6371.0

    // Coeficiente de refracción atmosférica estándar: la luz se curva levemente
    // hacia abajo, lo que equivale a una Tierra un poco más grande.
    private const val COEF_REFRACCION = 0.13
    private const val RADIO_EFECTIVO_M = RADIO_TIERRA_KM * 1000.0 / (1.0 - COEF_REFRACCION)

    const val DISTANCIA_MAXIMA_KM = 150.0

    // Distancia mínima: si el usuario está parado sobre el lugar no tiene sentido etiquetarlo
    const val DISTANCIA_MINIMA_KM = 0.2

    // Un lago solo se ve de lejos si el usuario lo mira desde arriba
    private const val LAGO_DISTANCIA_SIEMPRE_VISIBLE_KM = 5.0
    private const val LAGO_DISTANCIA_MAXIMA_KM = 60.0
    private const val LAGO_ANGULO_MAXIMO_GRADOS = -2.0

    // Sin modelo de terreno no se sabe qué tapa a qué. Como aproximación, un lugar
    // lejano que queda por debajo de la horizontal casi siempre está tapado por
    // el relieve intermedio, así que no se etiqueta.
    private const val DISTANCIA_EXIGE_SOBRE_HORIZONTE_KM = 40.0

    /**
     * Calcula el bearing esférico desde el punto 1 hacia el punto 2.
     * Resultado en grados, rango 0-360 (0 = Norte, 90 = Este).
     */
    fun calcularBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
    {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLonRad = Math.toRadians(lon2 - lon1)

        val x = sin(deltaLonRad) * cos(lat2Rad)
        val y = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLonRad)

        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /**
     * Calcula la distancia en kilómetros entre dos coordenadas usando la fórmula de Haversine.
     */
    fun calcularDistanciaKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
    {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLatRad = Math.toRadians(lat2 - lat1)
        val deltaLonRad = Math.toRadians(lon2 - lon1)

        val a = sin(deltaLatRad / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLonRad / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return RADIO_TIERRA_KM * c
    }

    /**
     * Calcula el ángulo de elevación vertical hacia un punto geográfico,
     * corrigiendo por la curvatura de la Tierra y la refracción atmosférica.
     *
     * A 100 km la curvatura "hunde" el punto unos 680 m, así que ignorarla
     * dibujaría las etiquetas lejanas demasiado arriba.
     *
     * Resultado positivo = punto por encima del horizonte del usuario.
     * Resultado negativo = punto por debajo (ej: lago visto desde una montaña).
     *
     * @param distanciaKm       Distancia horizontal al punto
     * @param elevacionUsuarioM Elevación del usuario en metros
     * @param elevacionPuntoM   Elevación del punto objetivo en metros
     */
    fun calcularAnguloElevacion(
        distanciaKm: Double,
        elevacionUsuarioM: Int,
        elevacionPuntoM: Int
    ): Double
    {
        if (distanciaKm == 0.0) return 0.0

        val distanciaM = distanciaKm * 1000.0
        val caidaPorCurvaturaM = distanciaM * distanciaM / (2.0 * RADIO_EFECTIVO_M)
        val diferenciaAlturaM = (elevacionPuntoM - elevacionUsuarioM).toDouble() - caidaPorCurvaturaM

        return Math.toDegrees(atan2(diferenciaAlturaM, distanciaM))
    }

    /**
     * Indica si un punto queda oculto detrás de la curvatura de la Tierra
     * (suponiendo terreno al nivel del mar entre el usuario y el punto).
     *
     * Desde una altura h el horizonte queda a sqrt(2·R·h). Más allá de esa
     * distancia solo se ve lo que sobresale por encima de la línea de visión.
     */
    fun estaOcultoPorCurvatura(
        distanciaKm: Double,
        elevacionUsuarioM: Int,
        elevacionPuntoM: Int
    ): Boolean
    {
        val distanciaM = distanciaKm * 1000.0
        val alturaUsuario = elevacionUsuarioM.coerceAtLeast(0).toDouble()
        val distanciaHorizonteM = sqrt(2.0 * RADIO_EFECTIVO_M * alturaUsuario)

        if (distanciaM <= distanciaHorizonteM) return false

        val masAllaDelHorizonteM = distanciaM - distanciaHorizonteM
        val alturaMinimaVisibleM = masAllaDelHorizonteM * masAllaDelHorizonteM / (2.0 * RADIO_EFECTIVO_M)

        return elevacionPuntoM < alturaMinimaVisibleM
    }

    /**
     * Decide si un lugar puede verse desde la posición del usuario.
     * No usa modelo de terreno: descarta lo que queda tras la curvatura,
     * los lagos que no se están mirando desde arriba y los lugares lejanos
     * que quedan por debajo de la horizontal.
     */
    fun esPotencialmenteVisible(
        tipo: String,
        distanciaKm: Double,
        elevacionUsuarioM: Int,
        elevacionPuntoM: Int,
        distanciaMaxKm: Double = DISTANCIA_MAXIMA_KM
    ): Boolean
    {
        if (distanciaKm > distanciaMaxKm) return false
        if (distanciaKm < DISTANCIA_MINIMA_KM) return false
        if (estaOcultoPorCurvatura(distanciaKm, elevacionUsuarioM, elevacionPuntoM)) return false

        val anguloElevacion = calcularAnguloElevacion(
            distanciaKm, elevacionUsuarioM, elevacionPuntoM
        )

        if (tipo == "lago" || tipo == "laguna")
        {
            if (distanciaKm <= LAGO_DISTANCIA_SIEMPRE_VISIBLE_KM) return true

            return distanciaKm <= LAGO_DISTANCIA_MAXIMA_KM &&
                   anguloElevacion <= LAGO_ANGULO_MAXIMO_GRADOS
        }

        if (distanciaKm > DISTANCIA_EXIGE_SOBRE_HORIZONTE_KM && anguloElevacion < 0.0)
        {
            return false
        }

        return true
    }

    /**
     * Calcula bearing, distancia y ángulo de elevación de todos los lugares
     * que podrían verse desde la posición del usuario.
     *
     * Devuelve la lista ordenada por distancia (más cercano primero).
     */
    fun calcularCandidatos(
        latUsuario: Double,
        lonUsuario: Double,
        elevacionUsuarioM: Int,
        todos: List<LugarGeografico>,
        distanciaMaxKm: Double = DISTANCIA_MAXIMA_KM
    ): List<Candidato>
    {
        val resultado = mutableListOf<Candidato>()

        for (lugar in todos)
        {
            val distancia = calcularDistanciaKm(
                latUsuario, lonUsuario,
                lugar.latitud, lugar.longitud
            )
            if (distancia > distanciaMaxKm) continue

            if (!esPotencialmenteVisible(
                    lugar.tipo, distancia, elevacionUsuarioM, lugar.elevacionMsnm, distanciaMaxKm
                ))
            {
                continue
            }

            val anguloElevacion = calcularAnguloElevacion(
                distancia,
                elevacionUsuarioM,
                lugar.elevacionMsnm
            )

            val bearing = calcularBearing(
                latUsuario, lonUsuario,
                lugar.latitud, lugar.longitud
            )

            resultado.add(Candidato(lugar, bearing, distancia, anguloElevacion))
        }

        return resultado.sortedBy { it.distanciaKm }
    }

    /**
     * Convierte un azimuth en grados a su punto cardinal abreviado (N, NE, E...).
     */
    fun puntoCardinal(azimuth: Double): String
    {
        val nombres = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val normalizado = ((azimuth % 360.0) + 360.0) % 360.0
        val indice = ((normalizado + 22.5) / 45.0).toInt() % 8
        return nombres[indice]
    }
}
