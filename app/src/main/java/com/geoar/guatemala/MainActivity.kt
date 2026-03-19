package com.geoar.guatemala

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.geoar.guatemala.databinding.ActivityMainBinding
import com.geoar.guatemala.models.LugarGeografico

/**
 * Actividad principal de GeoAR Guatemala.
 *
 * Responsabilidades:
 *   1. Solicitar permisos de cámara y ubicación
 *   2. Iniciar el preview de CameraX
 *   3. Iniciar SensorFusion para leer orientación del dispositivo
 *   4. Recibir actualizaciones de GPS via LocationManager nativo de Android
 *   5. Calcular qué accidentes geográficos son visibles y pasarlos al overlay AR
 *
 * Nota: los lambdas de Kotlin ({}) requieren { en la misma línea que la función.
 * Las llaves de clases, funciones, if/when usan estilo Allman.
 */
class MainActivity : AppCompatActivity()
{
    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorFusion: SensorFusion
    private lateinit var locationManager: LocationManager
    private lateinit var lugaresRepository: LugaresRepository

    private var todosLosLugares: List<LugarGeografico> = emptyList()
    private var ubicacionActual: Location? = null
    private var elevacionUsuarioM: Int = 1500

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
            ubicacionActual = location

            if (location.hasAltitude() && location.altitude > 0)
            {
                elevacionUsuarioM = location.altitude.toInt()
            }

            // En modo ACCEL_ONLY (sin brújula), el azimuth viene del bearing del GPS
            if (location.hasBearing() && location.speed > 0.5f)
            {
                sensorFusion.actualizarAzimuthGps(location.bearing)
            }

            actualizarOverlay()
        }
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
        binding.btnInfo.setOnClickListener { mostrarInfoLugarCercano() }
        binding.btnEscanear.setOnClickListener { escanear() }
        binding.btnCerrarResultado.setOnClickListener { binding.panelResultado.visibility = View.GONE }

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
            locationManager.removeUpdates(locationListener)
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

                val preview = Preview.Builder().build().also { p ->
                    p.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            }
            catch (e: Exception)
            {
                Toast.makeText(
                    this,
                    "Error al iniciar la cámara: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun iniciarSensores()
    {
        sensorFusion = SensorFusion(this)

        Toast.makeText(this, "Sensor: ${sensorFusion.descripcionModo()}", Toast.LENGTH_SHORT).show()

        if (sensorFusion.modo == SensorFusion.Modo.ACCEL_ONLY)
        {
            Toast.makeText(
                this,
                "Sin brújula: dirección se toma del GPS al caminar",
                Toast.LENGTH_LONG
            ).show()
        }
        else if (!sensorFusion.tieneSensoresDisponibles())
        {
            Toast.makeText(
                this,
                "Este dispositivo no tiene sensores de orientación compatibles.",
                Toast.LENGTH_LONG
            ).show()
        }

        sensorFusion.onOrientacionCambiada = { _, _, _ -> actualizarOverlay() }
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
            val proveedor = when
            {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (proveedor != null)
            {
                locationManager.requestLocationUpdates(
                    proveedor,
                    5_000L,
                    10f,
                    locationListener
                )

                locationManager.getLastKnownLocation(proveedor)?.let { location ->
                    ubicacionActual = location
                    actualizarOverlay()
                }
            }
        }
        catch (e: SecurityException)
        {
            Toast.makeText(
                this,
                "No se pudo obtener la ubicación: permiso denegado",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------------------------
    // Overlay AR
    // -------------------------------------------------------------------------

    private fun actualizarOverlay()
    {
        val ubicacion = ubicacionActual ?: return

        try
        {
            val puntosVisibles = GeoCalculator.filtrarPuntosVisibles(
                latUsuario = ubicacion.latitude,
                lonUsuario = ubicacion.longitude,
                elevacionUsuarioM = elevacionUsuarioM,
                azimuth = sensorFusion.azimuth,
                pitch = sensorFusion.pitch,
                todos = todosLosLugares
            )

            runOnUiThread { binding.arOverlay.actualizarPuntos(puntosVisibles) }
        }
        catch (e: Exception)
        {
            // Ignorar errores de cálculo individuales
        }
    }

    // -------------------------------------------------------------------------
    // Escaneo tipo Google Lens
    // -------------------------------------------------------------------------

    private fun escanear()
    {
        val ubicacion = ubicacionActual
        if (ubicacion == null)
        {
            Toast.makeText(this, getString(R.string.sin_gps), Toast.LENGTH_SHORT).show()
            return
        }

        val candidatos = GeoCalculator.escanear(
            latUsuario = ubicacion.latitude,
            lonUsuario = ubicacion.longitude,
            elevacionUsuarioM = elevacionUsuarioM,
            azimuth = sensorFusion.azimuth,
            pitch = sensorFusion.pitch,
            todos = todosLosLugares
        )

        if (candidatos.isEmpty())
        {
            Toast.makeText(this, getString(R.string.sin_resultados_escaneo), Toast.LENGTH_SHORT).show()
            return
        }

        mostrarResultadoEscaneo(candidatos.first())
    }

    private fun mostrarResultadoEscaneo(resultado: Pair<LugarGeografico, PuntoVisible>)
    {
        val (lugar, punto) = resultado

        val tipo = lugar.tipo.replaceFirstChar { it.uppercase() }
        val estadoVolcan = if (lugar.tipo == "volcan" && lugar.activo) " · Activo" else ""
        val distanciaTexto = if (punto.distanciaKm < 10.0)
        {
            String.format("%.1f km", punto.distanciaKm)
        }
        else
        {
            "${punto.distanciaKm.toInt()} km"
        }

        binding.resultadoNombre.text = lugar.nombre
        binding.resultadoTipoDistancia.text = "$tipo$estadoVolcan  ·  $distanciaTexto  ·  ${lugar.elevacionMsnm} msnm"
        binding.resultadoDescripcion.text = lugar.descripcion
        binding.panelResultado.visibility = View.VISIBLE

        binding.btnVerEnMaps.setOnClickListener { abrirEnMaps(lugar) }
    }

    private fun abrirEnMaps(lugar: LugarGeografico)
    {
        val nombreCodificado = Uri.encode(lugar.nombre)
        val uri = Uri.parse("geo:${lugar.latitud},${lugar.longitud}?q=${lugar.latitud},${lugar.longitud}($nombreCodificado)")
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

    // -------------------------------------------------------------------------
    // Info botón (top-right FAB, muestra el más cercano visible)
    // -------------------------------------------------------------------------

    private fun mostrarInfoLugarCercano()
    {
        val ubicacion = ubicacionActual
        if (ubicacion == null)
        {
            Toast.makeText(this, getString(R.string.sin_gps), Toast.LENGTH_SHORT).show()
            return
        }

        val puntosVisibles = GeoCalculator.filtrarPuntosVisibles(
            latUsuario = ubicacion.latitude,
            lonUsuario = ubicacion.longitude,
            elevacionUsuarioM = elevacionUsuarioM,
            azimuth = sensorFusion.azimuth,
            pitch = sensorFusion.pitch,
            todos = todosLosLugares
        )

        val resultado = if (puntosVisibles.isNotEmpty())
        {
            puntosVisibles.first()
        }
        else
        {
            // Ninguno en el margen estricto — usar escaneo para el más cercano en dirección
            GeoCalculator.escanear(
                latUsuario = ubicacion.latitude,
                lonUsuario = ubicacion.longitude,
                elevacionUsuarioM = elevacionUsuarioM,
                azimuth = sensorFusion.azimuth,
                pitch = sensorFusion.pitch,
                todos = todosLosLugares,
                maxResultados = 1
            ).firstOrNull()
        }

        if (resultado == null)
        {
            Toast.makeText(this, getString(R.string.sin_resultados_escaneo), Toast.LENGTH_SHORT).show()
            return
        }

        mostrarResultadoEscaneo(resultado)
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
