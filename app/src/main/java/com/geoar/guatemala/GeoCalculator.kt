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
    const val DISTANCIA_MAXIMA_KM = 150.0          // legado — usar calcularDistanciaMaxima()
    const val MARGEN_HORIZONTAL_GRADOS = 12.0
    const val MARGEN_VERTICAL_GRADOS = 20.0
    const val MARGEN_MAXIMO_ESCANEO_GRADOS = 25.0  // umbral máximo horizontal en escanear()

    private const val DISTANCIA_CAP_KM = 120.0
    private const val DISTANCIA_MIN_KM = 20.0

    // --- Modelo de visibilidad realista ---
    // Radio efectivo de la Tierra con refracción atmosférica estándar (7/6 · R).
    // La refracción "levanta" ligeramente los objetos lejanos, por eso el radio
    // efectivo es mayor que el real.
    private const val RADIO_EFECTIVO_KM = 7433.0

    // Umbrales de prominencia angular: cuánto debe sobresalir un punto sobre el
    // horizonte (en grados, ya con curvatura aplicada) para considerarse visible
    // al ojo humano según su distancia.
    const val UMBRAL_CERCANO_KM = 20.0             // < 20 km: siempre visible
    const val UMBRAL_MEDIO_KM = 50.0               // 20-50 km: requiere 0.2°
    const val PROMINENCIA_MEDIA_GRADOS = 0.2
    const val PROMINENCIA_LEJANA_GRADOS = 0.6      // > 50 km: solo picos prominentes

    // Visibilidad de cuerpos de agua: solo desde la orilla o desde una posición elevada
    const val DISTANCIA_ORILLA_KM = 8.0
    const val DISTANCIA_AGUA_ELEVADA_KM = 30.0
    const val ALTURA_MINIMA_SOBRE_AGUA_M = 150

    // --- Oclusión por relieve intermedio ---
    // Radio horizontal aproximado del macizo de un volcán/cerro (para estimar
    // cuánto "ancho angular" ocupa como obstáculo visto desde el usuario)
    private const val RADIO_MACIZO_KM = 2.5
    // Cuánto debe sobresalir el obstáculo por encima de la línea de visión
    // hacia el objetivo para considerarse bloqueo real
    private const val MARGEN_OCLUSION_GRADOS = 0.3

    // --- Oclusión por DEM (modelo digital de elevación) ---
    // Margen angular del terreno sobre la línea de visión (el DEM de ~1 km de
    // celda suaviza picos, por eso el margen es menor que el del catálogo)
    private const val MARGEN_OCLUSION_DEM_GRADOS = 0.15
    // Fracción de la distancia al objetivo donde termina el muestreo: evita que
    // la propia falda del volcán objetivo se detecte como obstáculo
    private const val FRACCION_FIN_MUESTREO = 0.92
    // Altura del observador sobre el terreno (ojos / teléfono en mano)
    private const val ALTURA_OBSERVADOR_M = 2.0

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
     * Calcula el ángulo de elevación vertical APARENTE hacia un punto geográfico,
     * considerando la curvatura terrestre y la refracción atmosférica estándar.
     *
     * La curvatura "hunde" los objetos lejanos bajo el horizonte:
     * caída ≈ d² / (2·R_efectivo). Ejemplo: a 61 km la caída es ~250 m, por eso
     * la Sierra de Chuacús (~700 m sobre el observador) apenas sobresale 0.4°
     * desde Ciudad de Guatemala — prácticamente invisible.
     *
     * Resultado positivo = punto sobre el horizonte aparente del usuario.
     * Resultado negativo = punto bajo el horizonte (ej: lago desde una montaña,
     * o un pico lejano hundido por la curvatura).
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

        // Caída por curvatura terrestre (en metros) con refracción incluida
        val caidaCurvaturaM = (distanciaKm * distanciaKm) / (2.0 * RADIO_EFECTIVO_KM) * 1000.0

        val diferenciaAlturaM = (elevacionPuntoM - elevacionUsuarioM).toDouble() - caidaCurvaturaM
        val distanciaM = distanciaKm * 1000.0
        return Math.toDegrees(atan2(diferenciaAlturaM, distanciaM))
    }

    /**
     * Determina si un lugar es realistamente visible al ojo humano desde la
     * posición del usuario, combinando distancia, prominencia angular y tipo.
     *
     * Reglas:
     *  - Montañas/volcanes/cerros/sierras:
     *      < 20 km  → visible (el relieve cercano casi siempre se ve)
     *      20-50 km → debe sobresalir ≥ 0.2° sobre el horizonte aparente
     *      > 50 km  → debe sobresalir ≥ 0.6° (solo picos prominentes,
     *                 ej. Volcán de Agua o los volcanes de Atitlán en día claro)
     *  - Lagos/lagunas: solo desde la orilla (< 8 km) o si el usuario está al
     *    menos 150 m por encima del espejo de agua y a menos de 30 km.
     *
     * @param anguloElevacion Ángulo aparente ya calculado con calcularAnguloElevacion()
     */
    fun esRealisticamenteVisible(
        lugar: LugarGeografico,
        distanciaKm: Double,
        anguloElevacion: Double,
        elevacionUsuarioM: Int
    ): Boolean
    {
        val esAgua = lugar.tipo == "lago" || lugar.tipo == "laguna"

        if (esAgua)
        {
            if (distanciaKm <= DISTANCIA_ORILLA_KM) return true
            return elevacionUsuarioM >= lugar.elevacionMsnm + ALTURA_MINIMA_SOBRE_AGUA_M &&
                   distanciaKm <= DISTANCIA_AGUA_ELEVADA_KM
        }

        return when
        {
            distanciaKm <= UMBRAL_CERCANO_KM -> true
            distanciaKm <= UMBRAL_MEDIO_KM   -> anguloElevacion >= PROMINENCIA_MEDIA_GRADOS
            else                             -> anguloElevacion >= PROMINENCIA_LEJANA_GRADOS
        }
    }

    /**
     * Determina si otro accidente geográfico del catálogo bloquea la línea de
     * visión entre el usuario y el objetivo (oclusión por relieve intermedio).
     *
     * Modelo: cada cerro/volcán/sierra del catálogo es un obstáculo con radio
     * horizontal ~2.5 km. Si un obstáculo está en la misma dirección (dentro de
     * su ancho angular), más cerca que el objetivo, y su ángulo aparente supera
     * la línea de visión hacia el objetivo, el objetivo está tapado.
     *
     * Esto captura el caso clave de Guatemala: desde el valle no ves un volcán
     * lejano porque un cerro intermedio lo tapa, pero desde un mirador elevado
     * (ej. Carretera a El Salvador) tu línea de visión pasa por encima del
     * obstáculo y el volcán reaparece.
     *
     * Limitación: solo conoce el relieve del catálogo, no cada loma del país.
     * La solución completa requiere un modelo digital de elevación (DEM).
     */
    fun estaOcluido(
        latUsuario: Double,
        lonUsuario: Double,
        elevacionUsuarioM: Int,
        objetivo: LugarGeografico,
        distanciaObjetivoKm: Double,
        bearingObjetivo: Double,
        anguloObjetivo: Double,
        todos: List<LugarGeografico>
    ): Boolean
    {
        for (otro in todos)
        {
            if (otro.id == objetivo.id) continue

            // Los cuerpos de agua no tapan nada (están a nivel del terreno)
            if (otro.tipo == "lago" || otro.tipo == "laguna") continue

            val dist = calcularDistanciaKm(latUsuario, lonUsuario, otro.latitud, otro.longitud)

            // Solo ocluyen obstáculos claramente ENTRE el usuario y el objetivo
            if (dist < 1.0 || dist > distanciaObjetivoKm * 0.95) continue

            val bearingOtro = calcularBearing(latUsuario, lonUsuario, otro.latitud, otro.longitud)
            var difBearing = bearingOtro - bearingObjetivo
            if (difBearing > 180.0) difBearing -= 360.0
            if (difBearing < -180.0) difBearing += 360.0

            // Ancho angular del obstáculo visto desde el usuario
            val medioAnchoGrados = Math.toDegrees(atan2(RADIO_MACIZO_KM, dist))
            if (abs(difBearing) > medioAnchoGrados) continue

            // ¿El obstáculo sobresale por encima de la línea de visión al objetivo?
            val anguloOtro = calcularAnguloElevacion(dist, elevacionUsuarioM, otro.elevacionMsnm)
            if (anguloOtro >= anguloObjetivo + MARGEN_OCLUSION_GRADOS)
            {
                return true
            }
        }
        return false
    }

    /**
     * Oclusión real por terreno usando un modelo digital de elevación (DEM).
     *
     * Recorre la línea recta entre el usuario y el objetivo muestreando la
     * elevación del terreno cada ~300-1000 m. Si en algún punto el ángulo
     * aparente del terreno (con curvatura y refracción) supera la línea de
     * visión hacia la cima del objetivo, el objetivo está tapado.
     *
     * Es el mismo principio que usa PeakFinder: línea de visión contra el
     * relieve real del país, no solo contra los puntos del catálogo. Captura
     * el caso del mirador: desde el valle una loma intermedia tapa el volcán,
     * pero al subir 200 m la línea de visión pasa por encima y reaparece.
     *
     * La interpolación lineal de coordenadas es suficiente: a < 150 km la
     * diferencia con el círculo máximo es de pocos metros.
     */
    fun estaOcluidoPorTerreno(
        latUsuario: Double,
        lonUsuario: Double,
        elevacionUsuarioM: Int,
        latObjetivo: Double,
        lonObjetivo: Double,
        distanciaObjetivoKm: Double,
        anguloObjetivo: Double,
        dem: ModeloElevacion
    ): Boolean
    {
        if (distanciaObjetivoKm < 1.0) return false

        val elevacionOjosM = elevacionUsuarioM + ALTURA_OBSERVADOR_M

        // Paso adaptativo: fino de cerca, ≤ 1 km de lejos (≈ resolución del DEM)
        val pasoKm = (distanciaObjetivoKm / 150.0).coerceIn(0.3, 1.0)
        val finKm = distanciaObjetivoKm * FRACCION_FIN_MUESTREO

        var d = 0.5
        while (d <= finKm)
        {
            val fraccion = d / distanciaObjetivoKm
            val lat = latUsuario + (latObjetivo - latUsuario) * fraccion
            val lon = lonUsuario + (lonObjetivo - lonUsuario) * fraccion

            val elevTerreno = dem.elevacionEn(lat, lon)
            if (elevTerreno != null)
            {
                // Ángulo aparente del terreno en este punto (con curvatura)
                val caidaM = (d * d) / (2.0 * RADIO_EFECTIVO_KM) * 1000.0
                val anguloTerreno = Math.toDegrees(
                    atan2(elevTerreno - elevacionOjosM - caidaM, d * 1000.0)
                )

                if (anguloTerreno >= anguloObjetivo + MARGEN_OCLUSION_DEM_GRADOS)
                {
                    return true
                }
            }

            d += pasoKm
        }
        return false
    }

    /**
     * Decide si el objetivo está tapado, usando el DEM si está disponible
     * (relieve real del país) o el catálogo como fallback.
     */
    private fun estaTapado(
        latUsuario: Double,
        lonUsuario: Double,
        elevacionUsuarioM: Int,
        lugar: LugarGeografico,
        distanciaKm: Double,
        bearing: Double,
        anguloElevacion: Double,
        todos: List<LugarGeografico>,
        dem: ModeloElevacion?
    ): Boolean
    {
        return if (dem != null)
        {
            estaOcluidoPorTerreno(
                latUsuario, lonUsuario, elevacionUsuarioM,
                lugar.latitud, lugar.longitud,
                distanciaKm, anguloElevacion, dem
            )
        }
        else
        {
            estaOcluido(
                latUsuario, lonUsuario, elevacionUsuarioM,
                lugar, distanciaKm, bearing, anguloElevacion, todos
            )
        }
    }

    /**
     * Calcula la distancia máxima realista de visibilidad en Guatemala.
     *
     * La visibilidad atmosférica en Guatemala varía entre 30-80 km según la elevación
     * del usuario. Los picos más altos que el observador suman hasta 30 km extra porque
     * sobresalen sobre el relieve intermedio y la neblina baja.
     *
     * Rangos base (ajustados al relieve guatemalteco):
     *   < 800 m msnm  → tierras bajas / costa → 40 km
     *   800-1 500 m   → tierras medias / valles → 60 km
     *   1 500-2 500 m → altiplano / Guatemala City → 80 km
     *   > 2 500 m     → cimas / sierra → 100 km
     *
     * Cap absoluto: 120 km · Mínimo: 20 km
     */
    fun calcularDistanciaMaxima(elevacionUsuarioM: Int, elevacionPuntoM: Int): Double
    {
        val base = when
        {
            elevacionUsuarioM > 2500 -> 100.0
            elevacionUsuarioM > 1500 -> 80.0
            elevacionUsuarioM > 800  -> 60.0
            else                     -> 40.0
        }
        // Bonus si el punto está mucho más alto que el usuario (pico sobresale)
        val alturaRelativa = (elevacionPuntoM - elevacionUsuarioM).toDouble()
        val bonusPico = (alturaRelativa / 100.0).coerceIn(0.0, 30.0)
        return (base + bonusPico).coerceIn(DISTANCIA_MIN_KM, DISTANCIA_CAP_KM)
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
        distanciaMaxKm: Double? = null,
        dem: ModeloElevacion? = null
    ): List<Pair<LugarGeografico, PuntoVisible>>
    {
        val resultado = mutableListOf<Pair<LugarGeografico, PuntoVisible>>()

        for (lugar in todos)
        {
            val distancia = calcularDistanciaKm(
                latUsuario, lonUsuario,
                lugar.latitud, lugar.longitud
            )
            val limite = distanciaMaxKm ?: calcularDistanciaMaxima(elevacionUsuarioM, lugar.elevacionMsnm)
            if (distancia > limite) continue

            val bearing = calcularBearing(
                latUsuario, lonUsuario,
                lugar.latitud, lugar.longitud
            )
            val anguloElevacion = calcularAnguloElevacion(
                distancia,
                elevacionUsuarioM,
                lugar.elevacionMsnm
            )

            // Descartar lugares que la curvatura/prominencia hacen invisibles
            if (!esRealisticamenteVisible(lugar, distancia, anguloElevacion, elevacionUsuarioM)) continue

            // Descartar lugares tapados por relieve intermedio (DEM o catálogo)
            if (estaTapado(latUsuario, lonUsuario, elevacionUsuarioM, lugar,
                    distancia, bearing, anguloElevacion, todos, dem)) continue

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
        maxResultados: Int = 3,
        dem: ModeloElevacion? = null
    ): List<Pair<LugarGeografico, PuntoVisible>>
    {
        data class Candidato(val lugar: LugarGeografico, val punto: PuntoVisible, val error: Double)

        val candidatos = mutableListOf<Candidato>()

        for (lugar in todos)
        {
            val distancia = calcularDistanciaKm(latUsuario, lonUsuario, lugar.latitud, lugar.longitud)
            if (distancia > calcularDistanciaMaxima(elevacionUsuarioM, lugar.elevacionMsnm)) continue

            val bearing = calcularBearing(latUsuario, lonUsuario, lugar.latitud, lugar.longitud)
            val anguloElevacion = calcularAnguloElevacion(distancia, elevacionUsuarioM, lugar.elevacionMsnm)

            // Descartar lugares que la curvatura/prominencia hacen invisibles
            if (!esRealisticamenteVisible(lugar, distancia, anguloElevacion, elevacionUsuarioM)) continue

            // Descartar lugares tapados por relieve intermedio (DEM o catálogo)
            if (estaTapado(latUsuario, lonUsuario, elevacionUsuarioM, lugar,
                    distancia, bearing, anguloElevacion, todos, dem)) continue

            var difHorizontal = bearing - azimuth.toDouble()
            if (difHorizontal > 180.0) difHorizontal -= 360.0
            if (difHorizontal < -180.0) difHorizontal += 360.0

            // Descartar candidatos fuera del campo de visión horizontal realista
            if (kotlin.math.abs(difHorizontal) > MARGEN_MAXIMO_ESCANEO_GRADOS) continue

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

    /**
     * Modo sin brújula (ACCEL_ONLY): devuelve los lugares más cercanos por distancia GPS,
     * sin filtrar por dirección. Útil cuando el dispositivo no tiene magnetómetro.
     */
    fun buscarCercanos(
        latUsuario: Double,
        lonUsuario: Double,
        elevacionUsuarioM: Int,
        todos: List<LugarGeografico>,
        maxResultados: Int = 3
    ): List<Pair<LugarGeografico, PuntoVisible>>
    {
        return todos
            .mapNotNull { lugar ->
                val distancia = calcularDistanciaKm(latUsuario, lonUsuario, lugar.latitud, lugar.longitud)
                if (distancia > calcularDistanciaMaxima(elevacionUsuarioM, lugar.elevacionMsnm)) return@mapNotNull null

                val bearing = calcularBearing(latUsuario, lonUsuario, lugar.latitud, lugar.longitud)
                val anguloElevacion = calcularAnguloElevacion(distancia, elevacionUsuarioM, lugar.elevacionMsnm)

                // Descartar lugares que la curvatura/prominencia hacen invisibles
                if (!esRealisticamenteVisible(lugar, distancia, anguloElevacion, elevacionUsuarioM)) return@mapNotNull null

                Pair(
                    lugar,
                    PuntoVisible(
                        bearing           = bearing,
                        distanciaKm       = distancia,
                        anguloElevacion   = anguloElevacion,
                        difHorizontal     = 0.0,
                        difVertical       = 0.0
                    )
                )
            }
            .sortedBy { it.second.distanciaKm }
            .take(maxResultados)
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
