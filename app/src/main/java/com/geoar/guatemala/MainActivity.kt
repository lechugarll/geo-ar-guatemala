package com.geoar.guatemala

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.geoar.guatemala.databinding.ActivityMainBinding
import com.geoar.guatemala.models.LugarGeografico
import kotlin.math.max
import kotlin.math.min

/**
 * Actividad principal de GeoAR Guatemala.
 *
 * Responsabilidades:
 *   1. Solicitar permisos de cámara y ubicación
 *   2. Iniciar el preview de CameraX y leer el campo de visión real de la cámara
 *   3. Iniciar SensorFusion para leer la orientación del dispositivo
 *   4. Recibir actualizaciones de ubicación (GPS y red) via LocationManager
 *   5. Proyectar cada lugar sobre la pantalla y pasarlo al overlay AR
 *   6. Identificar el lugar que está en la mira central (estilo Google Lens)
 *
 * Nota: los lambdas de Kotlin ({}) requieren { en la misma línea que la función.
 * Las llaves de clases, funciones, if/when usan estilo Allman.
 */
class MainActivity : AppCompatActivity()
{
    companion object
    {
        private const val TAG = "MainActivity"

        // Un lugar a menos de estos grados de la mira se considera identificado
        private const val UMBRAL_ENFOQUE_GRADOS = 6.0

        // Hasta estos grados se ofrece como coincidencia probable al escanear
        private const val UMBRAL_PROBABLE_GRADOS = 15.0

        // Elevación asumida mientras el GPS no entrega altitud (altiplano central)
        private const val ELEVACION_POR_DEFECTO_M = 1500

        // Altitud del GPS: solo se acepta si su error vertical es razonable
        private const val ERROR_VERTICAL_MAXIMO_M = 40f
        private const val ALPHA_ELEVACION = 0.2

        // Precisión horizontal a partir de la cual se avisa de señal débil
        private const val PRECISION_GPS_DEBIL_M = 50f

        // Una lectura de red solo reemplaza al GPS si este lleva rato sin responder
        private const val VIGENCIA_GPS_MS = 30_000L

        private const val INTERVALO_UBICACION_MS = 2_000L
        private const val DISTANCIA_MINIMA_UBICACION_M = 2f
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorFusion: SensorFusion
    private lateinit var locationManager: LocationManager
    private lateinit var lugaresRepository: LugaresRepository

    private var todosLosLugares: List<LugarGeografico> = emptyList()

    // Ubicación
    private var ubicacionActual: Location? = null
    private var elevacionUsuarioM: Int = ELEVACION_POR_DEFECTO_M
    private var elevacionSuavizadaM: Double = Double.NaN
    private var momentoUltimoGpsMs: Long = 0L

    // Lugares potencialmente visibles desde la ubicación actual.
    // Se recalcula solo cuando cambia la ubicación, no en cada lectura de sensor.
    private var candidatos: List<Candidato> = emptyList()

    // Óptica de la cámara (null hasta que se lee de CameraX)
    private var focalMm: Float? = null
    private var sensorLadoLargoMm: Float? = null
    private var sensorLadoCortoMm: Float? = null

    // -------------------------------------------------------------------------
    // Permisos
    // -------------------------------------------------------------------------

    private val permisosRequeridos = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // Nota: { del lambda registerForActivityResult debe ir en la misma línea
    private val lanzadorPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        val camaraOk = permisos[Manifest.permission.CAMERA] == true
        val ubicacionOk = permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true

        if (camaraOk && ubicacionOk)
        {
            iniciarApp()
        }
        else
        {
            mostrarPantallaPermisosDenegados()
        }
    }

    // -------------------------------------------------------------------------
    // Listener de ubicación — usa object : para compatibilidad Allman
    // -------------------------------------------------------------------------

    private val locationListener = object : LocationListener
    {
        override fun onLocationChanged(location: Location)
        {
            procesarUbicacion(location)
        }

        // Necesarios en Android 9 y anteriores, donde no tienen implementación por defecto
        @Deprecated("Obsoleto desde API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

        override fun onProviderEnabled(provider: String) { }

        override fun onProviderDisabled(provider: String) { }
    }

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lugaresRepository = LugaresRepository(this)
        todosLosLugares = lugaresRepository.cargarLugares()

        // { del lambda setOnClickListener debe ir en la misma línea en Kotlin
        binding.btnInfo.setOnClickListener { escanear() }
        binding.btnEscanear.setOnClickListener { escanear() }
        binding.btnCerrarResultado.setOnClickListener { binding.panelResultado.visibility = View.GONE }
        binding.arOverlay.onEtiquetaTocada = { candidato -> mostrarResultado(candidato, false) }

        if (tienePermisos())
        {
            iniciarApp()
        }
        else
        {
            lanzadorPermisos.launch(permisosRequeridos)
        }
    }

    override fun onResume()
    {
        super.onResume()
        if (tienePermisos() && ::sensorFusion.isInitialized)
        {
            sensorFusion.iniciar()
            iniciarActualizacionUbicacion()
        }
    }

    override fun onPause()
    {
        super.onPause()

        if (::sensorFusion.isInitialized)
        {
            sensorFusion.detener()
        }

        if (::locationManager.isInitialized)
        {
            try
            {
                locationManager.removeUpdates(locationListener)
            }
            catch (e: Exception)
            {
                Log.e(TAG, "No se pudieron detener las actualizaciones de ubicación", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inicialización
    // -------------------------------------------------------------------------

    private fun tienePermisos(): Boolean
    {
        return permisosRequeridos.all { permiso ->
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun iniciarApp()
    {
        binding.pantallaPermisos.visibility = View.GONE
        binding.contenidoPrincipal.visibility = View.VISIBLE

        iniciarCamara()
        iniciarSensores()
        iniciarUbicacion()
    }

    private fun iniciarCamara()
    {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try
            {
                val cameraProvider = providerFuture.get()

                // Preview en 4:3 (valor por defecto de CameraX): usa el sensor completo,
                // que es lo que asume el cálculo del campo de visión.
                val preview = Preview.Builder().build().also { p ->
                    p.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

                cameraProvider.unbindAll()
                val camara = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )

                leerOpticaDeCamara(camara)
            }
            catch (e: Exception)
            {
                Toast.makeText(
                    this,
                    getString(R.string.error_camara, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Lee la distancia focal y el tamaño físico del sensor para conocer el campo
     * de visión real. Sin estos datos las etiquetas no caerían sobre el lugar correcto.
     * Si falla, ArProyeccion usa un campo de visión típico.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun leerOpticaDeCamara(camara: Camera)
    {
        try
        {
            val info = Camera2CameraInfo.from(camara.cameraInfo)

            val focales = info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )
            val tamanoSensor = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )

            if (focales != null && focales.isNotEmpty() && tamanoSensor != null &&
                focales[0] > 0f && tamanoSensor.width > 0f && tamanoSensor.height > 0f)
            {
                focalMm = focales[0]
                sensorLadoLargoMm = max(tamanoSensor.width, tamanoSensor.height)
                sensorLadoCortoMm = min(tamanoSensor.width, tamanoSensor.height)
            }
        }
        catch (e: Exception)
        {
            Log.w(TAG, "No se pudo leer la óptica de la cámara; se usará un campo de visión típico", e)
        }
    }

    private fun iniciarSensores()
    {
        sensorFusion = SensorFusion(this)

        if (!sensorFusion.tieneSensoresDisponibles())
        {
            Toast.makeText(this, getString(R.string.sin_sensores), Toast.LENGTH_LONG).show()
        }

        sensorFusion.onOrientacionCambiada = { actualizarOverlay() }
        sensorFusion.iniciar()
    }

    private fun iniciarUbicacion()
    {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        iniciarActualizacionUbicacion()
    }

    private fun iniciarActualizacionUbicacion()
    {
        try
        {
            // Se escuchan GPS y red a la vez: la red da una primera posición rápida
            // y el GPS la refina (y es el único que funciona sin internet).
            val proveedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

            for (proveedor in proveedores)
            {
                if (!locationManager.allProviders.contains(proveedor)) continue
                if (!locationManager.isProviderEnabled(proveedor)) continue

                locationManager.requestLocationUpdates(
                    proveedor,
                    INTERVALO_UBICACION_MS,
                    DISTANCIA_MINIMA_UBICACION_M,
                    locationListener
                )

                if (ubicacionActual == null)
                {
                    locationManager.getLastKnownLocation(proveedor)?.let { location ->
                        procesarUbicacion(location)
                    }
                }
            }
        }
        catch (e: SecurityException)
        {
            Toast.makeText(this, getString(R.string.error_ubicacion), Toast.LENGTH_LONG).show()
        }
        catch (e: Exception)
        {
            Log.e(TAG, "No se pudieron iniciar las actualizaciones de ubicación", e)
        }
    }

    // -------------------------------------------------------------------------
    // Ubicación
    // -------------------------------------------------------------------------

    private fun procesarUbicacion(location: Location)
    {
        try
        {
            val esGps = location.provider == LocationManager.GPS_PROVIDER
            val ahora = System.currentTimeMillis()

            // No dejar que una lectura de red (menos precisa) pise un GPS reciente
            if (!esGps && ahora - momentoUltimoGpsMs < VIGENCIA_GPS_MS) return
            if (esGps) momentoUltimoGpsMs = ahora

            ubicacionActual = location
            actualizarElevacion(location)

            // Los sensores apuntan al norte magnético; se corrige al norte verdadero
            val campo = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                elevacionUsuarioM.toFloat(),
                ahora
            )
            sensorFusion.declinacionGrados = campo.declination

            // En modo ACCEL_ONLY (sin brújula), el azimuth viene del rumbo del GPS
            if (location.hasBearing() && location.speed > 0.5f)
            {
                sensorFusion.actualizarAzimuthGps(location.bearing)
            }

            candidatos = GeoCalculator.calcularCandidatos(
                latUsuario = location.latitude,
                lonUsuario = location.longitude,
                elevacionUsuarioM = elevacionUsuarioM,
                todos = todosLosLugares
            )

            actualizarOverlay()
        }
        catch (e: Exception)
        {
            Log.e(TAG, "Error al procesar la ubicación", e)
        }
    }

    /**
     * La altitud del GPS salta bastante entre lecturas; se filtra y se ignora
     * cuando su error vertical es grande.
     */
    private fun actualizarElevacion(location: Location)
    {
        if (!location.hasAltitude()) return
        if (location.hasVerticalAccuracy() &&
            location.verticalAccuracyMeters > ERROR_VERTICAL_MAXIMO_M)
        {
            return
        }

        val altitud = location.altitude
        if (altitud < -100.0 || altitud > 5000.0) return

        elevacionSuavizadaM = if (elevacionSuavizadaM.isNaN())
        {
            altitud
        }
        else
        {
            elevacionSuavizadaM + ALPHA_ELEVACION * (altitud - elevacionSuavizadaM)
        }

        elevacionUsuarioM = elevacionSuavizadaM.toInt()
    }

    // -------------------------------------------------------------------------
    // Overlay AR
    // -------------------------------------------------------------------------

    private fun calcularFocalPx(ancho: Int, alto: Int): Float
    {
        val focal = focalMm
        val ladoLargo = sensorLadoLargoMm
        val ladoCorto = sensorLadoCortoMm

        return if (focal != null && ladoLargo != null && ladoCorto != null)
        {
            ArProyeccion.calcularFocalPx(focal, ladoLargo, ladoCorto, ancho, alto)
        }
        else
        {
            ArProyeccion.calcularFocalPxPorDefecto(ancho, alto)
        }
    }

    /**
     * Proyecta todos los candidatos con la orientación actual.
     * Devuelve la lista ordenada por cercanía a la mira central, incluyendo
     * los que quedan fuera de la pantalla (pero delante de la cámara).
     */
    private fun proyectarCandidatos(): List<EtiquetaAR>
    {
        val ancho = binding.arOverlay.width
        val alto = binding.arOverlay.height
        if (ancho == 0 || alto == 0) return emptyList()
        if (!sensorFusion.orientacionValida || sensorFusion.esperandoRumboGps) return emptyList()

        val focalPx = calcularFocalPx(ancho, alto)
        val proyectados = mutableListOf<EtiquetaAR>()

        for (candidato in candidatos)
        {
            val proyeccion = ArProyeccion.proyectar(
                sensorFusion.matrizRotacion,
                candidato.bearing,
                candidato.anguloElevacion,
                focalPx,
                ancho,
                alto
            ) ?: continue

            proyectados.add(
                EtiquetaAR(
                    candidato = candidato,
                    x = proyeccion.x,
                    y = proyeccion.y,
                    anguloAlCentro = proyeccion.anguloAlCentro,
                    enfocada = false
                )
            )
        }

        proyectados.sortBy { it.anguloAlCentro }

        // El más cercano a la mira queda marcado como enfocado si está dentro del umbral
        if (proyectados.isNotEmpty() && proyectados[0].anguloAlCentro <= UMBRAL_ENFOQUE_GRADOS)
        {
            proyectados[0] = proyectados[0].copy(enfocada = true)
        }

        return proyectados
    }

    private fun actualizarOverlay()
    {
        if (!::sensorFusion.isInitialized) return

        try
        {
            val ancho = binding.arOverlay.width
            val alto = binding.arOverlay.height

            val enPantalla = proyectarCandidatos().filter { etiqueta ->
                etiqueta.x >= 0f && etiqueta.x <= ancho && etiqueta.y >= 0f && etiqueta.y <= alto
            }

            binding.arOverlay.actualizar(enPantalla, construirTextoEstado(), construirAviso())
        }
        catch (e: Exception)
        {
            // Ignorar errores de cálculo individuales
        }
    }

    private fun construirTextoEstado(): String
    {
        val partes = mutableListOf<String>()

        if (sensorFusion.orientacionValida && !sensorFusion.esperandoRumboGps)
        {
            val azimuth = sensorFusion.azimuth.toDouble()
            partes.add("${GeoCalculator.puntoCardinal(azimuth)} ${azimuth.toInt()}°")
        }

        val ubicacion = ubicacionActual
        if (ubicacion != null)
        {
            if (ubicacion.hasAccuracy())
            {
                partes.add("GPS ±${ubicacion.accuracy.toInt()} m")
            }
            partes.add("$elevacionUsuarioM msnm")
        }

        return partes.joinToString("  ·  ")
    }

    private fun construirAviso(): String?
    {
        val ubicacion = ubicacionActual

        return when
        {
            ubicacion == null ->
                getString(R.string.sin_gps)
            sensorFusion.esperandoRumboGps ->
                getString(R.string.aviso_sin_brujula)
            sensorFusion.brujulaDescalibrada ->
                getString(R.string.aviso_calibrar_brujula)
            ubicacion.hasAccuracy() && ubicacion.accuracy > PRECISION_GPS_DEBIL_M ->
                getString(R.string.aviso_gps_debil)
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Escaneo tipo Google Lens: identifica lo que está en la mira central
    // -------------------------------------------------------------------------

    private fun escanear()
    {
        if (ubicacionActual == null)
        {
            Toast.makeText(this, getString(R.string.sin_gps), Toast.LENGTH_SHORT).show()
            return
        }

        if (!::sensorFusion.isInitialized || sensorFusion.esperandoRumboGps)
        {
            Toast.makeText(this, getString(R.string.aviso_sin_brujula), Toast.LENGTH_LONG).show()
            return
        }

        val mejor = proyectarCandidatos().firstOrNull()

        if (mejor == null || mejor.anguloAlCentro > UMBRAL_PROBABLE_GRADOS)
        {
            // Mejor decir "no sé" que inventar un lugar que no está en esa dirección
            Toast.makeText(this, getString(R.string.sin_resultados_escaneo), Toast.LENGTH_LONG).show()
            return
        }

        mostrarResultado(mejor.candidato, mejor.anguloAlCentro > UMBRAL_ENFOQUE_GRADOS)
    }

    private fun mostrarResultado(candidato: Candidato, esProbable: Boolean)
    {
        val lugar = candidato.lugar

        val tipo = nombreTipo(lugar)
        val distanciaTexto = if (candidato.distanciaKm < 10.0)
        {
            String.format("%.1f km", candidato.distanciaKm)
        }
        else
        {
            "${candidato.distanciaKm.toInt()} km"
        }
        val direccion = GeoCalculator.puntoCardinal(candidato.bearing)

        binding.resultadoNombre.text = lugar.nombre
        binding.resultadoTipoDistancia.text =
            "$tipo  ·  $distanciaTexto al $direccion  ·  ${lugar.elevacionMsnm} msnm"
        binding.resultadoDescripcion.text = lugar.descripcion
        binding.resultadoProbable.visibility = if (esProbable) View.VISIBLE else View.GONE
        binding.panelResultado.visibility = View.VISIBLE

        binding.btnVerEnMaps.setOnClickListener { abrirEnMaps(lugar) }
    }

    private fun nombreTipo(lugar: LugarGeografico): String
    {
        return when (lugar.tipo)
        {
            "volcan"  -> if (lugar.activo) "Volcán activo" else "Volcán"
            "cerro"   -> "Cerro"
            "montana" -> "Montaña"
            "sierra"  -> "Sierra"
            "lago"    -> "Lago"
            "laguna"  -> "Laguna"
            else      -> lugar.tipo.replaceFirstChar { it.uppercase() }
        }
    }

    private fun abrirEnMaps(lugar: LugarGeografico)
    {
        try
        {
            val nombreCodificado = Uri.encode(lugar.nombre)
            val uri = Uri.parse(
                "geo:${lugar.latitud},${lugar.longitud}?q=${lugar.latitud},${lugar.longitud}($nombreCodificado)"
            )
            val intent = Intent(Intent.ACTION_VIEW, uri)

            if (intent.resolveActivity(packageManager) != null)
            {
                startActivity(intent)
            }
            else
            {
                // Fallback: abrir en navegador si no hay app de mapas
                val webUri = Uri.parse("https://maps.google.com/?q=${lugar.latitud},${lugar.longitud}")
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        }
        catch (e: Exception)
        {
            Toast.makeText(this, getString(R.string.error_maps), Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Permisos denegados
    // -------------------------------------------------------------------------

    private fun mostrarPantallaPermisosDenegados()
    {
        binding.pantallaPermisos.visibility = View.VISIBLE
        binding.contenidoPrincipal.visibility = View.GONE

        binding.btnReintentarPermisos.setOnClickListener {
            lanzadorPermisos.launch(permisosRequeridos)
        }
    }
}
