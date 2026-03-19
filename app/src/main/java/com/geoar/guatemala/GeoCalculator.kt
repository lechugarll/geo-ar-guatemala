package com.geoar.guatemala

import android.graphics.PointF
import com.geoar.guatemala.models.LugarGeografico
import kotlin.math.*

/**
 * Datos calculados de un punto geográfico visible en pantalla.
 */
data class PuntoVisible(
    val bearing: Double,         // Dirección hacia el punto (0-360°, 0=Norte)
    val distanciaKm: Double,     // Distancia en kilómetros
    val anguloElevacion: Double, // Ángulo vertical respecto al horizonte (+ = arriba)
    val difHorizontal: Double,   // Diferencia entre bearing y azimuth actual (+ = derecha)
    val difVertical: Double      // Diferencia entre ángulo de elevación y pitch actual (+ = arriba)
)

/**
 * Módulo de cálculo geográfico y AR.
 * Todas las funciones son puras (sin estado), aptas para tests unitarios.
 */
object GeoCalculator
{
    private const val RADIO_TIERRA_KM = 6371.0
    const val DISTANCIA_MAXIMA_KM = 150.0
    const val MARGEN_HORIZONTAL_GRADOS = 12.0
    const val MARGEN_VERTICAL_GRADOS = 20.0

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
     * Calcula el ángulo de elevación vertical hacia un punto geográfico.
     * Resultado positivo = punto está por encima del horizonte del usuario.
     * Resultado negativo = punto está por debajo (ej: lago desde una montaña).
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

        val diferenciaAlturaM = (elevacionPuntoM - elevacionUsuarioM).toDouble()
        val distanciaM = distanciaKm * 1000.0
        return Math.toDegrees(atan2(diferenciaAlturaM, distanciaM))
    }

    /**
     * Filtra los puntos geográficos visibles dado el azimuth y pitch actuales.
     *
     * El pitch debe estar en el sistema de coordenadas remapeado para portrait:
     * 0° = mirando al horizonte, + = mirando hacia arriba, - = mirando hacia abajo.
     *
     * Devuelve la lista ordenada por distancia (más cercano primero).
     */
    fun filtrarPuntosVisibles(
        latUsuario: Double,
        lonUsuario: Double,
        elevacionUsuarioM: Int,
        azimuth: Float,
        pitch: Float,
        todos: List<LugarGeografico>,
        distanciaMaxKm: Double = DISTANCIA_MAXIMA_KM
    ): List<Pair<LugarGeografico, PuntoVisible>>
    {
        val resultado = mutableListOf<Pair<LugarGeografico, PuntoVisible>>()

        for (lugar in todos)
        {
            val distancia = calcularDistanciaKm(
                latUsuario, lonUsuario,
                lugar.latitud, lugar.longitud
            )
            if (distancia > distanciaMaxKm) continue

            val bearing = calcularBearing(
                latUsuario, lonUsuario,
                lugar.latitud, lugar.longitud
            )
            val anguloElevacion = calcularAnguloElevacion(
                distancia,
                elevacionUsuarioM,
                lugar.elevacionMsnm
            )

            // Diferencia angular horizontal (manejo de wrap-around 0/360°)
            var difHorizontal = bearing - azimuth.toDouble()
            if (difHorizontal > 180.0) difHorizontal -= 360.0
            if (difHorizontal < -180.0) difHorizontal += 360.0

            // Diferencia angular vertical
            // pitch > 0 = cámara apuntando arriba, anguloElevacion > 0 = punto sobre el horizonte
            val difVertical = anguloElevacion - pitch.toDouble()

            if (abs(difHorizontal) <= MARGEN_HORIZONTAL_GRADOS &&
                abs(difVertical) <= MARGEN_VERTICAL_GRADOS)
            {
                resultado.add(
                    Pair(
                        lugar,
                        PuntoVisible(
                            bearing = bearing,
                            distanciaKm = distancia,
                            anguloElevacion = anguloElevacion,
                            difHorizontal = difHorizontal,
                            difVertical = difVertical
                        )
                    )
                )
            }
        }

        return resultado.sortedBy { it.second.distanciaKm }
    }

    /**
     * Convierte la posición angular relativa (difHorizontal, difVertical) a
     * coordenadas en píxeles dentro de la pantalla.
     *
     * El centro de la pantalla corresponde a difHorizontal=0, difVertical=0.
     * difHorizontal positivo = derecha en pantalla.
     * difVertical positivo = arriba en pantalla.
     */
    /**
     * Modo escaneo tipo Google Lens: devuelve los mejores candidatos geográficos
     * en la dirección actual sin restricción de margen, ordenados por proximidad angular.
     * Útil para el botón "Escanear" que identifica qué está viendo el usuario.
     */
    fun escanear(
        latUsuario: Double,
        lonUsuario: Double,
        elevacionUsuarioM: Int,
        azimuth: Float,
        pitch: Float,
        todos: List<LugarGeografico>,
        distanciaMaxKm: Double = DISTANCIA_MAXIMA_KM,
        maxResultados: Int = 3
    ): List<Pair<LugarGeografico, PuntoVisible>>
    {
        data class Candidato(val lugar: LugarGeografico, val punto: PuntoVisible, val error: Double)

        val candidatos = mutableListOf<Candidato>()

        for (lugar in todos)
        {
            val distancia = calcularDistanciaKm(latUsuario, lonUsuario, lugar.latitud, lugar.longitud)
            if (distancia > distanciaMaxKm) continue

            val bearing = calcularBearing(latUsuario, lonUsuario, lugar.latitud, lugar.longitud)
            val anguloElevacion = calcularAnguloElevacion(distancia, elevacionUsuarioM, lugar.elevacionMsnm)

            var difHorizontal = bearing - azimuth.toDouble()
            if (difHorizontal > 180.0) difHorizontal -= 360.0
            if (difHorizontal < -180.0) difHorizontal += 360.0

            val difVertical = anguloElevacion - pitch.toDouble()

            // Error combinado: horizontal pesa más porque el pitch es menos confiable sin brújula
            val error = Math.sqrt(difHorizontal * difHorizontal + difVertical * difVertical * 0.3)

            candidatos.add(
                Candidato(
                    lugar,
                    PuntoVisible(
                        bearing = bearing,
                        distanciaKm = distancia,
                        anguloElevacion = anguloElevacion,
                        difHorizontal = difHorizontal,
                        difVertical = difVertical
                    ),
                    error
                )
            )
        }

        return candidatos
            .sortedBy { it.error }
            .take(maxResultados)
            .map { Pair(it.lugar, it.punto) }
    }

    fun calcularPosicionEnPantalla(
        difHorizontal: Double,
        difVertical: Double,
        screenWidth: Int,
        screenHeight: Int
    ): PointF
    {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        val x = cx + ((difHorizontal / MARGEN_HORIZONTAL_GRADOS) * cx).toFloat()
        val y = cy - ((difVertical / MARGEN_VERTICAL_GRADOS) * cy).toFloat()

        return PointF(
            x.coerceIn(0f, screenWidth.toFloat()),
            y.coerceIn(0f, screenHeight.toFloat())
        )
    }
}
