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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.geoar.guatemala.databinding.ActivityMainBinding
import com.geoar.guatemala.models.LugarGeografico

/**
 * Actividad principal de GeoAR Guatemala.
 *
 * Pantallas (tabs):
 *   Explorer — cámara en vivo + coordenadas GPS
 *   Scan     — visor con corchetes + animación de análisis + resultado
 *   Archive  — historial de escaneos (RecyclerView)
 *
 * Nota: los lambdas de Kotlin ({}) requieren { en la misma línea que la función.
 * Las llaves de clases, funciones, if/when usan estilo Allman.
 */
class MainActivity : AppCompatActivity()
{
    // -------------------------------------------------------------------------
    // Enum de tabs
    // -------------------------------------------------------------------------

    private enum class Tab { EXPLORER, SCAN, ARCHIVE }

    private var tabActual: Tab = Tab.EXPLORER

    // -------------------------------------------------------------------------
    // ViewBinding y dependencias
    // -------------------------------------------------------------------------

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorFusion: SensorFusion
    private lateinit var locationManager: LocationManager
    private lateinit var lugaresRepository: LugaresRepository
    private lateinit var historialManager: HistorialManager
    private lateinit var historialAdapter: HistorialAdapter

    private var todosLosLugares: List<LugarGeografico> = emptyList()
    private var ubicacionActual: Location? = null
    private var elevacionUsuarioM: Int = 1500

    private val handler = Handler(Looper.getMainLooper())
    private var scanEnProgreso = false

    // -------------------------------------------------------------------------
    // Permisos
    // -------------------------------------------------------------------------

    private val permisosRequeridos = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

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
    // Listener de ubicación
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

            if (location.hasBearing() && location.speed > 0.5f)
            {
                sensorFusion.actualizarAzimuthGps(location.bearing)
            }

            actualizarExplorer()
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
        historialManager = HistorialManager(this)

        configurarNavegacion()

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
        handler.removeCallbacksAndMessages(null)

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
    // Navegación entre tabs
    // -------------------------------------------------------------------------

    private fun configurarNavegacion()
    {
        binding.tabExplorer.setOnClickListener { mostrarPantalla(Tab.EXPLORER) }
        binding.tabScan.setOnClickListener { mostrarPantalla(Tab.SCAN) }
        binding.tabArchive.setOnClickListener { mostrarPantalla(Tab.ARCHIVE) }
    }

    private fun mostrarPantalla(tab: Tab)
    {
        tabActual = tab

        // Mostrar/ocultar pantallas
        binding.screenExplorer.root.visibility = if (tab == Tab.EXPLORER) View.VISIBLE else View.GONE
        binding.screenScan.root.visibility     = if (tab == Tab.SCAN)     View.VISIBLE else View.GONE
        binding.screenHistory.root.visibility  = if (tab == Tab.ARCHIVE)  View.VISIBLE else View.GONE

        // Actualizar estado en la barra superior
        binding.labelTabStatus.visibility = if (tab == Tab.SCAN) View.VISIBLE else View.GONE
        binding.labelTabStatus.text = getString(R.string.active_scan)

        // Actualizar iconos de nav (active = primary, inactive = on_surface_variant)
        val colorActivo    = getColor(R.color.primary)
        val colorInactivo  = getColor(R.color.on_surface_variant)

        binding.iconExplorer.setColorFilter(if (tab == Tab.EXPLORER) colorActivo else colorInactivo)
        binding.iconArchive.setColorFilter(if (tab == Tab.ARCHIVE)  colorActivo else colorInactivo)

        when (tab)
        {
            Tab.EXPLORER -> { /* actualizarExplorer() se llama via locationListener */ }
            Tab.SCAN     -> iniciarEscaneo()
            Tab.ARCHIVE  -> cargarHistorial()
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
        binding.topBar.visibility           = View.VISIBLE
        binding.bottomNav.visibility        = View.VISIBLE

        iniciarCamara()
        iniciarSensores()
        iniciarUbicacion()
        configurarHistorial()
        mostrarPantalla(Tab.EXPLORER)
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

        sensorFusion.onOrientacionCambiada = { _, _, _ ->
            if (tabActual == Tab.EXPLORER)
            {
                actualizarBrujula()
            }
        }
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
                    actualizarExplorer()
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

    private fun configurarHistorial()
    {
        historialAdapter = HistorialAdapter(emptyList()) { /* item click no-op */ }

        binding.screenHistory.recyclerHistorial.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historialAdapter
        }

        binding.screenHistory.chipReciente.setOnClickListener { cargarHistorial(filtro = "reciente") }
        binding.screenHistory.chipVolcanes.setOnClickListener { cargarHistorial(filtro = "volcan") }
        binding.screenHistory.chipLagos.setOnClickListener    { cargarHistorial(filtro = "lago") }
    }

    // -------------------------------------------------------------------------
    // Explorer tab
    // -------------------------------------------------------------------------

    private fun actualizarExplorer()
    {
        val ubicacion = ubicacionActual ?: return
        val ex = binding.screenExplorer

        val latDir = if (ubicacion.latitude >= 0) "N" else "S"
        val lonDir = if (ubicacion.longitude >= 0) "E" else "W"
        ex.coordLat.text = String.format("%.2f°%s", Math.abs(ubicacion.latitude), latDir)
        ex.coordLon.text = String.format("%.2f°%s", Math.abs(ubicacion.longitude), lonDir)

        val tieneAlta = ubicacion.hasAccuracy() && ubicacion.accuracy <= 20f
        ex.estadoGps.text = if (tieneAlta)
        {
            getString(R.string.gps_precision_alta)
        }
        else
        {
            getString(R.string.gps_precision_baja)
        }

        actualizarBrujula()
    }

    private fun actualizarBrujula()
    {
        if (!::sensorFusion.isInitialized) return

        val ex = binding.screenExplorer
        ex.estadoBrujula.text = when (sensorFusion.modo)
        {
            SensorFusion.Modo.ACCEL_ONLY -> getString(R.string.compass_gps)
            SensorFusion.Modo.NINGUNO    -> "BRÚJULA: —"
            else                         -> getString(R.string.compass_activo)
        }
    }

    // -------------------------------------------------------------------------
    // Scan tab — animación y resultado
    // -------------------------------------------------------------------------

    private fun iniciarEscaneo()
    {
        if (scanEnProgreso) return

        val sc = binding.screenScan
        sc.panelEstadoScan.visibility   = View.VISIBLE
        sc.panelResultadoScan.visibility = View.GONE
        sc.scanStep1.text = ""
        sc.scanStep2.text = ""
        sc.scanStep3.text = ""

        val ubicacion = ubicacionActual
        if (ubicacion == null)
        {
            sc.scanStep1.text = getString(R.string.scan_sin_gps)
            return
        }

        scanEnProgreso = true

        handler.postDelayed({
            sc.scanStep1.text = getString(R.string.scan_analyzing)
        }, 300L)

        handler.postDelayed({
            sc.scanStep2.text = getString(R.string.scan_triangulating)
        }, 1000L)

        handler.postDelayed({
            sc.scanStep3.text = getString(R.string.scan_matching)
        }, 1700L)

        handler.postDelayed({
            finalizarEscaneo(ubicacion)
        }, 2400L)
    }

    private fun finalizarEscaneo(ubicacion: Location)
    {
        scanEnProgreso = false

        val candidatos = GeoCalculator.escanear(
            latUsuario         = ubicacion.latitude,
            lonUsuario         = ubicacion.longitude,
            elevacionUsuarioM  = elevacionUsuarioM,
            azimuth            = sensorFusion.azimuth,
            pitch              = sensorFusion.pitch,
            todos              = todosLosLugares
        )

        val sc = binding.screenScan

        if (candidatos.isEmpty())
        {
            sc.scanStep3.text = getString(R.string.scan_no_result)
            return
        }

        val (lugar, punto) = candidatos.first()

        // Guardar en historial
        val entrada = HistorialManager.crearEntrada(
            nombre       = lugar.nombre,
            tipo         = lugar.tipo,
            latitud      = lugar.latitud,
            longitud     = lugar.longitud,
            elevacionMsnm = lugar.elevacionMsnm,
            descripcion  = lugar.descripcion,
            distanciaKm  = punto.distanciaKm,
            activo       = lugar.activo
        )
        historialManager.guardar(entrada)

        // Poblar resultado
        sc.resultadoNombre.text = lugar.nombre
        sc.resultadoElevacion.text = "${lugar.elevacionMsnm}m"

        val tipo = lugar.tipo.replaceFirstChar { it.uppercase() }
        val activo = if (lugar.tipo == "volcan" && lugar.activo) " · Activo" else ""
        sc.resultadoBadge.text = "$tipo$activo"

        val distanciaTexto = if (punto.distanciaKm < 10.0)
        {
            String.format("%.1f km", punto.distanciaKm)
        }
        else
        {
            "${punto.distanciaKm.toInt()} km"
        }
        sc.resultadoDescripcion.text = "${lugar.descripcion}\n\n$distanciaTexto · ${lugar.elevacionMsnm} msnm"

        sc.btnCerrarResultado.setOnClickListener {
            sc.panelResultadoScan.visibility = View.GONE
            sc.panelEstadoScan.visibility    = View.GONE
        }

        sc.btnVerEnMaps.setOnClickListener { abrirEnMaps(lugar) }

        sc.panelEstadoScan.visibility    = View.GONE
        sc.panelResultadoScan.visibility = View.VISIBLE
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
            val webUri = Uri.parse("https://maps.google.com/?q=${lugar.latitud},${lugar.longitud}")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    // -------------------------------------------------------------------------
    // Archive tab
    // -------------------------------------------------------------------------

    private fun cargarHistorial(filtro: String = "reciente")
    {
        val todos = historialManager.cargarTodos()

        val lista = when (filtro)
        {
            "volcan" -> todos.filter { it.tipo == "volcan" }
            "lago"   -> todos.filter { it.tipo == "lago" }
            else     -> todos
        }

        val sc = binding.screenHistory

        sc.labelTotalSites.text = getString(R.string.sites_recorded, todos.size)

        if (lista.isEmpty())
        {
            sc.recyclerHistorial.visibility   = View.GONE
            sc.labelHistorialVacio.visibility = View.VISIBLE
        }
        else
        {
            sc.recyclerHistorial.visibility   = View.VISIBLE
            sc.labelHistorialVacio.visibility = View.GONE
            historialAdapter.actualizarLista(lista)
        }

        // Cobertura regional
        val total = todosLosLugares.size
        if (total > 0 && todos.isNotEmpty())
        {
            val porcentaje = (todos.size * 100) / total
            sc.cardCoverage.visibility = View.VISIBLE
            sc.labelCoverageDesc.text  = getString(R.string.coverage_desc, porcentaje)
            sc.progressCoverage.progress = porcentaje
        }
        else
        {
            sc.cardCoverage.visibility = View.GONE
        }
    }

    // -------------------------------------------------------------------------
    // Permisos denegados
    // -------------------------------------------------------------------------

    private fun mostrarPantallaPermisosDenegados()
    {
        binding.pantallaPermisos.visibility = View.VISIBLE
        binding.topBar.visibility           = View.GONE
        binding.bottomNav.visibility        = View.GONE

        binding.btnReintentarPermisos.setOnClickListener {
            lanzadorPermisos.launch(permisosRequeridos)
        }
    }
}
