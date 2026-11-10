package com.geoar.guatemala

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Fusión de sensores para obtener orientación del dispositivo.
 *
 * Modos por prioridad según hardware disponible:
 *   1. ROTATION_VECTOR          — giroscopio + acel + mag (más estable)
 *   2. GEOMAGNETIC_ROTATION     — acelerómetro + magnetómetro, sin giroscopio
 *   3. ACCEL_MAG                — combinación manual acelerómetro + magnetómetro
 *   4. ACCEL_ONLY               — solo acelerómetro: pitch/roll del acelerómetro,
 *                                  azimuth del GPS (requiere llamar actualizarAzimuthGps)
 *                                  Caso del Samsung A05: sin brújula ni giroscopio.
 *   5. NINGUNO                  — sin sensores utilizables
 */
class SensorFusion(private val context: Context) : SensorEventListener
{
    companion object
    {
        private const val TAG = "SensorFusion"
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

    private val ALPHA_FALLBACK = 0.15f

    // Alpha adaptivo para azimuth: responde rápido a giros, filtra ruido en reposo
    private fun alphaAdaptivo(diferencia: Float): Float
    {
        return when
        {
            diferencia > 20f -> 0.5f   // giro rápido — seguir de inmediato
            diferencia > 8f  -> 0.3f   // giro normal
            diferencia > 3f  -> 0.15f  // ajuste fino
            else             -> 0.08f  // quieto — filtrar ruido del sensor
        }
    }

    var azimuth: Float = 0f
        private set
    var pitch: Float = 0f
        private set
    var roll: Float = 0f
        private set

    // Buffers para modos rotation vector
    private val matrizRotacion = FloatArray(9)
    private val matrizRemapeada = FloatArray(9)
    private val angulos = FloatArray(3)
    private val valoresSensor = FloatArray(5)

    // Buffers para modo ACCEL_MAG y ACCEL_ONLY
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var gravityValido = false
    private var geomagneticValido = false

    var onOrientacionCambiada: ((azimuth: Float, pitch: Float, roll: Float) -> Unit)? = null

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

    fun tieneRotationVector(): Boolean = modo == Modo.ROTATION_VECTOR

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
     * Actualiza el azimuth con el bearing del GPS.
     * Solo se aplica en modo ACCEL_ONLY (cuando no hay brújula).
     * Llamar desde MainActivity.onLocationChanged cuando location.hasBearing().
     */
    fun actualizarAzimuthGps(gpsBearing: Float)
    {
        if (modo == Modo.ACCEL_ONLY)
        {
            azimuth = filtrarAzimuth(azimuth, gpsBearing)
            // No llama onOrientacionCambiada aquí — se invoca desde el acelerómetro
        }
    }

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    fun iniciar()
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
                accelerometerSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                magnetometerSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }

            Modo.ACCEL_ONLY ->
                accelerometerSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }

            Modo.NINGUNO -> { /* sin sensores */ }
        }
    }

    fun detener()
    {
        sensorManager.unregisterListener(this)
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
                        calcularPitchDesdeAcelerometro()
                    }
                    else
                    {
                        calcularOrientacionFallback()
                    }
                }

                Sensor.TYPE_MAGNETIC_FIELD ->
                {
                    procesarMagnetometro(event)
                    calcularOrientacionFallback()
                }
            }
        }
        catch (e: Exception)
        {
            // Ignorar lecturas individuales corruptas
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    // -------------------------------------------------------------------------
    // Rotation vector (modos 1 y 2)
    // -------------------------------------------------------------------------

    private fun procesarRotationVector(event: SensorEvent)
    {
        System.arraycopy(
            event.values, 0,
            valoresSensor, 0,
            minOf(event.values.size, valoresSensor.size)
        )
        SensorManager.getRotationMatrixFromVector(matrizRotacion, valoresSensor)
        aplicarRemapeoYActualizar(matrizRotacion)
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

    private fun calcularOrientacionFallback()
    {
        if (!gravityValido || !geomagneticValido) return
        val rotMatrix = FloatArray(9)
        val incMatrix = FloatArray(9)
        val ok = SensorManager.getRotationMatrix(rotMatrix, incMatrix, gravity, geomagnetic)
        if (!ok) return
        aplicarRemapeoYActualizar(rotMatrix)
    }

    // -------------------------------------------------------------------------
    // Solo acelerómetro (modo ACCEL_ONLY — Samsung A05 y similares)
    // Pitch y roll desde gravedad; azimuth viene del GPS via actualizarAzimuthGps()
    // -------------------------------------------------------------------------

    private fun calcularPitchDesdeAcelerometro()
    {
        if (!gravityValido) return

        val gx = gravity[0].toDouble()
        val gy = gravity[1].toDouble()
        val gz = gravity[2].toDouble()

        // Pitch en modo portrait (phone vertical):
        //   0°  = mirando al horizonte (phone upright)
        //  +90° = mirando al cielo (phone horizontal face up)
        //  -90° = mirando al suelo (phone horizontal face down)
        //
        // Cuando el phone está vertical: gravity ≈ [0, g, 0] → gz≈0 → pitch≈0° ✓
        // Cuando se inclina hacia atrás (cámara arriba): gz se vuelve negativo → pitch > 0 ✓
        val nuevoPitch = Math.toDegrees(atan2(-gz, sqrt(gx * gx + gy * gy))).toFloat()

        // Roll desde el acelerómetro (inclinación lateral)
        val nuevoRoll = Math.toDegrees(atan2(gx, gy)).toFloat()

        pitch = filtrarLineal(pitch, nuevoPitch, ALPHA_FALLBACK)
        roll  = filtrarLineal(roll, nuevoRoll, ALPHA_FALLBACK)

        onOrientacionCambiada?.invoke(azimuth, pitch, roll)
    }

    // -------------------------------------------------------------------------
    // Remapeo (modos con rotation matrix completa)
    // -------------------------------------------------------------------------

    private fun aplicarRemapeoYActualizar(rotMatrix: FloatArray)
    {
        // --- AZIMUTH: dirección que apunta la cámara trasera en el plano horizontal ---
        //
        // La cámara trasera apunta en la dirección -Z del teléfono.
        // La matriz R transforma vectores del sistema del teléfono al sistema del mundo:
        //   worldVec = R * deviceVec
        //
        // Dirección de la cámara en el mundo = R * [0, 0, -1]:
        //   Componente Este  = -R[2]
        //   Componente Norte = -R[5]
        //
        // Azimuth = atan2(Este, Norte) — igual que un brújula:
        //   0° = Norte, 90° = Este, 180° = Sur, 270° = Oeste
        //
        // Este cálculo es correcto independientemente de la inclinación del teléfono
        // y no requiere remapeo de ejes.
        val cameraEast  = -rotMatrix[2]
        val cameraNorth = -rotMatrix[5]
        val nuevoAzimuth = Math.toDegrees(
            Math.atan2(cameraEast.toDouble(), cameraNorth.toDouble())
        ).toFloat()

        // --- PITCH y ROLL: desde la matriz remapeada para modo portrait ---
        // El remap AXIS_X / AXIS_MINUS_Z sitúa el eje Y en la dirección de la cámara,
        // haciendo que pitch=0° corresponda a mirar al horizonte.
        val ok = SensorManager.remapCoordinateSystem(
            rotMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_MINUS_Z,
            matrizRemapeada
        )
        if (!ok) return

        SensorManager.getOrientation(matrizRemapeada, angulos)

        val nuevoPitch = Math.toDegrees(angulos[1].toDouble()).toFloat()
        val nuevoRoll  = Math.toDegrees(angulos[2].toDouble()).toFloat()

        azimuth = filtrarAzimuth(azimuth, (nuevoAzimuth + 360f) % 360f)
        pitch   = filtrarLineal(pitch, nuevoPitch, ALPHA_FALLBACK)
        roll    = filtrarLineal(roll, nuevoRoll, ALPHA_FALLBACK)

        onOrientacionCambiada?.invoke(azimuth, pitch, roll)
    }

    // -------------------------------------------------------------------------
    // Filtros
    // -------------------------------------------------------------------------

    private fun filtrarAzimuth(anterior: Float, nuevo: Float): Float
    {
        var d = nuevo - anterior
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        val alpha = alphaAdaptivo(kotlin.math.abs(d))
        return (anterior + alpha * d + 360f) % 360f
    }

    private fun filtrarLineal(anterior: Float, nuevo: Float, alpha: Float): Float
    {
        return anterior + alpha * (nuevo - anterior)
    }
}
