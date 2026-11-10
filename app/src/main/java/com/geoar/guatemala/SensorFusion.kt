package com.geoar.guatemala

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Fusión de sensores para obtener la orientación completa del dispositivo.
 *
 * Entrega una matriz de rotación suavizada y referida al norte verdadero
 * (mundo = R * dispositivo), que ArProyeccion usa para ubicar cada lugar
 * en la pantalla. También expone azimuth y pitch derivados de esa matriz.
 *
 * Modos por prioridad según hardware disponible:
 *   1. ROTATION_VECTOR          — giroscopio + acel + mag (más estable)
 *   2. GEOMAGNETIC_ROTATION     — acelerómetro + magnetómetro, sin giroscopio
 *   3. ACCEL_MAG                — combinación manual acelerómetro + magnetómetro
 *   4. ACCEL_ONLY               — solo acelerómetro: inclinación del acelerómetro,
 *                                  azimuth del GPS (requiere llamar actualizarAzimuthGps)
 *                                  Caso del Samsung A05: sin brújula ni giroscopio.
 *   5. NINGUNO                  — sin sensores utilizables
 */
class SensorFusion(private val context: Context) : SensorEventListener
{
    companion object
    {
        private const val TAG = "SensorFusion"
        private const val ALPHA = 0.1f
        private const val ALPHA_FALLBACK = 0.15f
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val geomagneticRotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val accelerometerSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val magnetometerSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    enum class Modo { ROTATION_VECTOR, GEOMAGNETIC_ROTATION, ACCEL_MAG, ACCEL_ONLY, NINGUNO }

    val modo: Modo = when
    {
        rotationVectorSensor != null                               -> Modo.ROTATION_VECTOR
        geomagneticRotationSensor != null                          -> Modo.GEOMAGNETIC_ROTATION
        accelerometerSensor != null && magnetometerSensor != null  -> Modo.ACCEL_MAG
        accelerometerSensor != null                                -> Modo.ACCEL_ONLY
        else                                                       -> Modo.NINGUNO
    }

    /**
     * Matriz de rotación suavizada (9 elementos, por filas), referida al norte verdadero.
     * No modificar desde fuera.
     */
    val matrizRotacion = FloatArray(9)

    /** true cuando ya se recibió al menos una lectura válida de orientación. */
    var orientacionValida: Boolean = false
        private set

    /** Dirección de la cámara trasera: 0-360°, 0 = Norte verdadero. */
    var azimuth: Float = 0f
        private set

    /** Inclinación de la cámara trasera: 0° = horizonte, + = hacia el cielo. */
    var pitch: Float = 0f
        private set

    /**
     * Declinación magnética local en grados (norte magnético respecto al verdadero).
     * Los sensores de Android apuntan al norte magnético; MainActivity actualiza
     * este valor con GeomagneticField cuando conoce la ubicación.
     */
    var declinacionGrados: Float = 0f

    /** true si el sistema reporta que la brújula necesita calibración. */
    var brujulaDescalibrada: Boolean = false
        private set

    /** true si en modo ACCEL_ONLY todavía no se ha recibido un rumbo del GPS. */
    val esperandoRumboGps: Boolean
        get() = modo == Modo.ACCEL_ONLY && !azimuthGpsValido

    var onOrientacionCambiada: (() -> Unit)? = null

    // Buffers reutilizables
    private val matrizCruda = FloatArray(9)
    private val matrizInclinacion = FloatArray(9)
    private val valoresSensor = FloatArray(5)

    // Buffers para modo ACCEL_MAG y ACCEL_ONLY
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var gravityValido = false
    private var geomagneticValido = false

    // Azimuth del GPS para modo ACCEL_ONLY
    private var azimuthGps = 0f
    private var azimuthGpsValido = false

    init
    {
        Log.d(TAG, "Modo seleccionado: $modo")
        Log.d(TAG, "Sensores — RotVec:${rotationVectorSensor?.name} " +
            "GeoRotVec:${geomagneticRotationSensor?.name} " +
            "Accel:${accelerometerSensor?.name} " +
            "Mag:${magnetometerSensor?.name}")
    }

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    fun tieneSensoresDisponibles(): Boolean = modo != Modo.NINGUNO

    fun descripcionModo(): String = when (modo)
    {
        Modo.ROTATION_VECTOR      -> "Rotation Vector"
        Modo.GEOMAGNETIC_ROTATION -> "Geomagnetic Rotation"
        Modo.ACCEL_MAG            -> "Acelerómetro + Magnetómetro"
        Modo.ACCEL_ONLY           -> "Acelerómetro + GPS"
        Modo.NINGUNO              -> "Sin sensores"
    }

    /**
     * Actualiza el azimuth con el rumbo del GPS.
     * Solo se aplica en modo ACCEL_ONLY (cuando no hay brújula).
     * Llamar desde MainActivity cuando la ubicación trae rumbo y el usuario se mueve.
     */
    fun actualizarAzimuthGps(gpsBearing: Float)
    {
        if (modo != Modo.ACCEL_ONLY) return

        if (!azimuthGpsValido)
        {
            azimuthGps = gpsBearing
            azimuthGpsValido = true
        }
        else
        {
            azimuthGps = filtrarAzimuth(azimuthGps, gpsBearing, ALPHA_FALLBACK)
        }
    }

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    fun iniciar()
    {
        try
        {
            when (modo)
            {
                Modo.ROTATION_VECTOR ->
                    sensorManager.registerListener(
                        this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME
                    )

                Modo.GEOMAGNETIC_ROTATION ->
                    sensorManager.registerListener(
                        this, geomagneticRotationSensor, SensorManager.SENSOR_DELAY_GAME
                    )

                Modo.ACCEL_MAG ->
                {
                    sensorManager.registerListener(
                        this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME
                    )
                    sensorManager.registerListener(
                        this, magnetometerSensor, SensorManager.SENSOR_DELAY_GAME
                    )
                }

                Modo.ACCEL_ONLY ->
                    sensorManager.registerListener(
                        this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME
                    )

                Modo.NINGUNO -> { /* sin sensores */ }
            }

            // El magnetómetro se escucha (a baja frecuencia) también en los modos de
            // rotation vector, solo para saber si la brújula necesita calibración.
            if (modo == Modo.ROTATION_VECTOR || modo == Modo.GEOMAGNETIC_ROTATION)
            {
                magnetometerSensor?.let { sensor ->
                    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }
        catch (e: Exception)
        {
            Log.e(TAG, "No se pudieron registrar los sensores", e)
        }
    }

    fun detener()
    {
        try
        {
            sensorManager.unregisterListener(this)
        }
        catch (e: Exception)
        {
            Log.e(TAG, "No se pudieron detener los sensores", e)
        }
    }

    // -------------------------------------------------------------------------
    // SensorEventListener
    // -------------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent)
    {
        try
        {
            when (event.sensor.type)
            {
                Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> procesarRotationVector(event)

                Sensor.TYPE_ACCELEROMETER ->
                {
                    procesarAcelerometro(event)
                    if (modo == Modo.ACCEL_ONLY)
                    {
                        calcularOrientacionSoloAcelerometro()
                    }
                    else
                    {
                        calcularOrientacionAcelMag()
                    }
                }

                Sensor.TYPE_MAGNETIC_FIELD ->
                {
                    if (modo == Modo.ACCEL_MAG)
                    {
                        procesarMagnetometro(event)
                        calcularOrientacionAcelMag()
                    }
                }
            }
        }
        catch (e: Exception)
        {
            // Ignorar lecturas individuales corruptas
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int)
    {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD)
        {
            brujulaDescalibrada = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                                  accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }
    }

    // -------------------------------------------------------------------------
    // Rotation vector (modos 1 y 2)
    // -------------------------------------------------------------------------

    private fun procesarRotationVector(event: SensorEvent)
    {
        // Algunos dispositivos entregan más de 5 valores y getRotationMatrixFromVector falla
        System.arraycopy(
            event.values, 0,
            valoresSensor, 0,
            minOf(event.values.size, valoresSensor.size)
        )
        SensorManager.getRotationMatrixFromVector(matrizCruda, valoresSensor)
        ArProyeccion.aplicarDeclinacion(matrizCruda, declinacionGrados)
        actualizarOrientacion(matrizCruda, ALPHA)
    }

    // -------------------------------------------------------------------------
    // Acelerómetro + magnetómetro (modo ACCEL_MAG)
    // -------------------------------------------------------------------------

    private fun procesarAcelerometro(event: SensorEvent)
    {
        if (!gravityValido)
        {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            gravityValido = true
        }
        else
        {
            gravity[0] = ALPHA_FALLBACK * event.values[0] + (1 - ALPHA_FALLBACK) * gravity[0]
            gravity[1] = ALPHA_FALLBACK * event.values[1] + (1 - ALPHA_FALLBACK) * gravity[1]
            gravity[2] = ALPHA_FALLBACK * event.values[2] + (1 - ALPHA_FALLBACK) * gravity[2]
        }
    }

    private fun procesarMagnetometro(event: SensorEvent)
    {
        geomagnetic[0] = event.values[0]
        geomagnetic[1] = event.values[1]
        geomagnetic[2] = event.values[2]
        geomagneticValido = true
    }

    private fun calcularOrientacionAcelMag()
    {
        if (!gravityValido || !geomagneticValido) return

        val ok = SensorManager.getRotationMatrix(
            matrizCruda, matrizInclinacion, gravity, geomagnetic
        )
        if (!ok) return

        ArProyeccion.aplicarDeclinacion(matrizCruda, declinacionGrados)
        actualizarOrientacion(matrizCruda, ALPHA_FALLBACK)
    }

    // -------------------------------------------------------------------------
    // Solo acelerómetro (modo ACCEL_ONLY — Samsung A05 y similares)
    // Inclinación desde la gravedad; azimuth desde el GPS via actualizarAzimuthGps().
    // El rumbo del GPS ya es norte verdadero, así que no se aplica declinación.
    // -------------------------------------------------------------------------

    private fun calcularOrientacionSoloAcelerometro()
    {
        if (!gravityValido) return

        val ok = ArProyeccion.matrizDesdeGravedadYAzimuth(
            gravity[0], gravity[1], gravity[2],
            azimuthGps,
            matrizCruda
        )
        if (!ok) return

        // La gravedad y el azimuth ya vienen filtrados
        actualizarOrientacion(matrizCruda, 1f)
    }

    // -------------------------------------------------------------------------
    // Actualización común
    // -------------------------------------------------------------------------

    private fun actualizarOrientacion(nueva: FloatArray, alpha: Float)
    {
        if (!orientacionValida)
        {
            System.arraycopy(nueva, 0, matrizRotacion, 0, 9)
            orientacionValida = true
        }
        else
        {
            ArProyeccion.suavizarMatriz(matrizRotacion, nueva, alpha)
        }

        azimuth = ArProyeccion.azimuthCamara(matrizRotacion)
        pitch = ArProyeccion.pitchCamara(matrizRotacion)

        onOrientacionCambiada?.invoke()
    }

    // -------------------------------------------------------------------------
    // Filtros
    // -------------------------------------------------------------------------

    private fun filtrarAzimuth(anterior: Float, nuevo: Float, alpha: Float): Float
    {
        var d = nuevo - anterior
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return (anterior + alpha * d + 360f) % 360f
    }
}
