package com.geoar.guatemala

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var lugaresRepository: LugaresRepository
    private lateinit var historialManager: HistorialManager
    private lateinit var historialAdapter: HistorialAdapter

    private var todosLosLugares: List<LugarGeografico> = emptyList()
    private var ubicacionActual: Location? = null
    private var elevacionUsuarioM: Int = 1500

    // Modelo digital de elevación (oclusión por terreno real). Si el asset
    // no está empaquetado, la app cae a la oclusión por catálogo.
    private val demRepository = DemRepository()

    // Throttle del overlay AR (los sensores reportan a ~50 Hz)
    private var ultimoOverlayMs = 0L

    // -------------------------------------------------------------------------
    // Auto-detección estilo Google Lens
    // -------------------------------------------------------------------------

    // Tiempo mínimo que el usuario debe mantener la cámara apuntando al mismo
    // accidente para que la app lo identifique automáticamente (en ms).
    private val TIEMPO_LOCK_MS = 2000L

    // Margen angular máximo para considerar un accidente "centrado" en el visor.
    private val MARGEN_AUTO_H_GRADOS = 5.0
    private val MARGEN_AUTO_V_GRADOS = 8.0

    // Estado actual del lock-on
    private var tiempoInicioApuntandoMs = 0L
    private var lugarApuntado: Pair<LugarGeografico, PuntoVisible>? = null
    private var idLugarEnCard: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scanEnProgreso = false

    // Análisis de escena con ML Kit (filtro tipo Google Lens)
    private lateinit var analizadorEscena: AnalizadorEscena
    private lateinit var executorAnalisis: ExecutorService
    @Volatile private var analisisSolicitado = false
    @Volatile private var resultadoEscena: ResultadoEscena? = null

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
    // Callback de ubicación (FusedLocationProviderClient)
    // -------------------------------------------------------------------------

    private fun crearLocationCallback(): LocationCallback
    {
        return object : LocationCallback()
        {
            override fun onLocationResult(result: LocationResult)
            {
                val location = result.lastLocation ?: return
                ubicacionActual = location

                // Elevación del usuario: el DEM es más consistente con las
                // elevaciones msnm del catálogo que la altitud GPS (elipsoidal
                // y ruidosa). GPS solo como fallback.
                val elevacionTerreno = if (demRepository.cargado)
                {
                    demRepository.elevacionEn(location.latitude, location.longitude)
                }
                else
                {
                    null
                }

                if (elevacionTerreno != null)
                {
                    elevacionUsuarioM = elevacionTerreno.toInt()
                }
                else if (location.hasAltitude() && location.altitude > 0)
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
    }

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?)
    {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lugaresRepository = LugaresRepository(this)
        todosLosLugares = lugaresRepository.cargarLugares()
        historialManager = HistorialManager(this)

        // Cargar el DEM en segundo plano (~450 KB, no bloquear el arranque)
        Thread { demRepository.cargar(this) }.start()

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

        if (::fusedLocationClient.isInitialized)
        {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onDestroy()
    {
        super.onDestroy()

        if (::analizadorEscena.isInitialized)
        {
            analizadorEscena.liberar()
        }

        if (::executorAnalisis.isInitialized)
        {
            executorAnalisis.shutdown()
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
        binding.btnMenu.setOnClickListener { mostrarMenuFiltros() }
        binding.btnAvatar.setOnClickListener { mostrarPerfil() }
    }

    // -------------------------------------------------------------------------
    // Menú de filtros (hamburguesa) y perfil (avatar)
    // -------------------------------------------------------------------------

    /** Tipos de accidente geográfico actualmente activos en el escaneo */
    private val tiposActivos = mutableSetOf("volcan", "cerro", "montana", "sierra", "lago", "laguna")

    private fun lugaresFiltrados(): List<LugarGeografico>
    {
        return todosLosLugares.filter { it.tipo in tiposActivos }
    }

    private fun mostrarMenuFiltros()
    {
        val sheet = BottomSheetDialog(this)
        val vista = layoutInflater.inflate(R.layout.bottom_sheet_filtros, null)
        sheet.setContentView(vista)

        val switchVolcanes = vista.findViewById<MaterialSwitch>(R.id.switch_volcanes)
        val switchCerros   = vista.findViewById<MaterialSwitch>(R.id.switch_cerros)
        val switchLagos    = vista.findViewById<MaterialSwitch>(R.id.switch_lagos)

        // Reflejar estado actual de los filtros
        switchVolcanes.isChecked = "volcan" in tiposActivos
        switchCerros.isChecked   = "cerro" in tiposActivos
        switchLagos.isChecked    = "lago" in tiposActivos

        // Actualizar tiposActivos en tiempo real al mover cada switch
        switchVolcanes.setOnCheckedChangeListener { _, activo ->
            if (activo) tiposActivos.add("volcan") else tiposActivos.remove("volcan")
        }
        switchCerros.setOnCheckedChangeListener { _, activo ->
            if (activo)
            {
                tiposActivos.addAll(listOf("cerro", "montana", "sierra"))
            }
            else
            {
                tiposActivos.removeAll(setOf("cerro", "montana", "sierra"))
            }
        }
        switchLagos.setOnCheckedChangeListener { _, activo ->
            if (activo)
            {
                tiposActivos.addAll(listOf("lago", "laguna"))
            }
            else
            {
                tiposActivos.removeAll(setOf("lago", "laguna"))
            }
        }

        vista.findViewById<android.widget.Button>(R.id.btn_filtros_listo).setOnClickListener {
            sheet.dismiss()
        }

        sheet.show()
    }

    private fun mostrarAcercaDe()
    {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.acerca_mensaje))
            .setPositiveButton(getString(R.string.btn_cerrar), null)
            .show()
    }

    private fun mostrarPerfil()
    {
        val escaneos = historialManager.cargarTodos().size
        val total = todosLosLugares.size
        val porcentaje = if (total > 0) (escaneos.coerceAtMost(total) * 100) / total else 0
        val modoSensor = if (::sensorFusion.isInitialized) sensorFusion.descripcionModo() else "—"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.perfil_titulo))
            .setMessage(getString(R.string.perfil_mensaje, escaneos, porcentaje, modoSensor))
            .setPositiveButton(getString(R.string.btn_cerrar), null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Auto-detección estilo Google Lens
    // -------------------------------------------------------------------------

    /**
     * Comprueba si algún accidente geográfico está centrado en el visor.
     * Si el mismo accidente permanece centrado TIEMPO_LOCK_MS ms consecutivos,
     * activa el estado BLOQUEADO y muestra la tarjeta de resultado.
     */
    private fun manejarAutoDeteccion(puntos: List<Pair<LugarGeografico, PuntoVisible>>)
    {
        if (tabActual != Tab.EXPLORER) return

        val overlay = binding.screenExplorer.arOverlay

        // Si ya hay una tarjeta visible, solo la oculta si el accidente
        // identificado desaparece completamente de la lista de visibles.
        val idEnCard = idLugarEnCard
        if (idEnCard != null)
        {
            val aun = puntos.any { (l, _) -> l.id == idEnCard }
            if (!aun)
            {
                idLugarEnCard = null
                lugarApuntado = null
                tiempoInicioApuntandoMs = 0L
                overlay.estadoReticulo = CameraOverlayView.EstadoReticulo.LIBRE
                ocultarCardAutoDetectado()
            }
            return
        }

        // Buscar el accidente más centrado dentro del margen de auto-detección
        val centrado = puntos
            .filter { (_, p) ->
                kotlin.math.abs(p.difHorizontal) <= MARGEN_AUTO_H_GRADOS &&
                kotlin.math.abs(p.difVertical) <= MARGEN_AUTO_V_GRADOS
            }
            .minByOrNull { (_, p) ->
                p.difHorizontal * p.difHorizontal + p.difVertical * p.difVertical
            }

        if (centrado == null)
        {
            // Nada centrado — resetear
            if (lugarApuntado != null ||
                overlay.estadoReticulo == CameraOverlayView.EstadoReticulo.APUNTANDO)
            {
                lugarApuntado = null
                tiempoInicioApuntandoMs = 0L
                overlay.estadoReticulo = CameraOverlayView.EstadoReticulo.LIBRE
            }
            return
        }

        if (lugarApuntado?.first?.id != centrado.first.id)
        {
            // Nuevo objetivo: iniciar el temporizador desde cero
            lugarApuntado = centrado
            tiempoInicioApuntandoMs = System.currentTimeMillis()
            overlay.estadoReticulo = CameraOverlayView.EstadoReticulo.APUNTANDO
            return
        }

        // Mismo objetivo — comprobar si se cumplió el tiempo de lock
        val elapsed = System.currentTimeMillis() - tiempoInicioApuntandoMs
        if (elapsed >= TIEMPO_LOCK_MS)
        {
            overlay.estadoReticulo = CameraOverlayView.EstadoReticulo.BLOQUEADO
            idLugarEnCard = centrado.first.id
            mostrarCardAutoDetectado(centrado.first, centrado.second)
        }
    }

    /** Muestra la tarjeta de resultado en la pantalla Explorer. */
    private fun mostrarCardAutoDetectado(lugar: LugarGeografico, punto: PuntoVisible)
    {
        val ex = binding.screenExplorer

        ex.autoNombre.text = lugar.nombre

        val tipo = lugar.tipo.replaceFirstChar { it.uppercase() }
        val activo = if (lugar.tipo == "volcan" && lugar.activo) " · Activo" else ""
        val distancia = if (punto.distanciaKm < 10.0)
            String.format("%.1f km", punto.distanciaKm)
        else
            "${punto.distanciaKm.toInt()} km"
        ex.autoInfo.text = "$tipo$activo · $distancia · ${lugar.elevacionMsnm} msnm"

        ex.btnAutoCerrar.setOnClickListener { cerrarCardAutoDetectado() }

        ex.btnAutoMaps.setOnClickListener { abrirEnMaps(lugar) }

        ex.btnAutoDetalles.setOnClickListener {
            // Guardar en historial y abrir la vista de detalle completa
            val entrada = HistorialManager.crearEntrada(
                nombre        = lugar.nombre,
                tipo          = lugar.tipo,
                latitud       = lugar.latitud,
                longitud      = lugar.longitud,
                elevacionMsnm = lugar.elevacionMsnm,
                descripcion   = lugar.descripcion,
                distanciaKm   = punto.distanciaKm,
                activo        = lugar.activo
            )
            historialManager.guardar(entrada)
            mostrarDetalleEnScan(lugar, punto)
        }

        if (ex.cardAutoDetectado.visibility != View.VISIBLE)
        {
            ex.cardAutoDetectado.alpha = 0f
            ex.cardAutoDetectado.visibility = View.VISIBLE
            ex.cardAutoDetectado.animate().alpha(1f).setDuration(300L).start()
        }
    }

    /** Cierra la tarjeta y resetea el lock-on sin salir del Explorer. */
    private fun cerrarCardAutoDetectado()
    {
        idLugarEnCard = null
        lugarApuntado = null
        tiempoInicioApuntandoMs = 0L
        binding.screenExplorer.arOverlay.estadoReticulo = CameraOverlayView.EstadoReticulo.LIBRE
        ocultarCardAutoDetectado()
    }

    /** Oculta la tarjeta con una animación suave de fade-out. */
    private fun ocultarCardAutoDetectado()
    {
        val card = binding.screenExplorer.cardAutoDetectado
        if (card.visibility == View.VISIBLE)
        {
            card.animate().alpha(0f).setDuration(200L).withEndAction {
                card.visibility = View.GONE
            }.start()
        }
    }

    /**
     * Cambia a la pestaña Scan y pre-carga el resultado del accidente
     * identificado, sin volver a ejecutar la animación de escaneo.
     */
    private fun mostrarDetalleEnScan(lugar: LugarGeografico, punto: PuntoVisible)
    {
        mostrarPantalla(Tab.SCAN)

        val sc = binding.screenScan
        val sinBrujula = sensorFusion.modo == SensorFusion.Modo.ACCEL_ONLY ||
                         sensorFusion.modo == SensorFusion.Modo.NINGUNO

        sc.resultadoNombre.text = lugar.nombre
        sc.resultadoElevacion.text = "${lugar.elevacionMsnm}m"

        val tipo = lugar.tipo.replaceFirstChar { it.uppercase() }
        val activo = if (lugar.tipo == "volcan" && lugar.activo) " · Activo" else ""
        val modoBadge = if (sinBrujula) " · Más cercano" else ""
        sc.resultadoBadge.text = "$tipo$activo$modoBadge"

        val distanciaTexto = if (punto.distanciaKm < 10.0)
            String.format("%.1f km", punto.distanciaKm)
        else
            "${punto.distanciaKm.toInt()} km"

        sc.resultadoDescripcion.text =
            "${lugar.descripcion}\n\n$distanciaTexto · ${lugar.elevacionMsnm} msnm"

        sc.btnCerrarResultado.setOnClickListener {
            sc.panelResultadoScan.visibility = View.GONE
            sc.panelEstadoScan.visibility    = View.GONE
            sc.viewfinder.visibility         = View.VISIBLE
        }
        sc.btnVerEnMaps.setOnClickListener { abrirEnMaps(lugar) }

        sc.viewfinder.visibility         = View.GONE
        sc.panelEstadoScan.visibility    = View.GONE
        sc.panelResultadoScan.visibility = View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Navegación entre tabs
    // -------------------------------------------------------------------------

    private fun mostrarPantalla(tab: Tab)
    {
        // Al salir del Explorer, limpiar el estado de auto-detección
        if (tab != Tab.EXPLORER && tabActual == Tab.EXPLORER)
        {
            idLugarEnCard = null
            lugarApuntado = null
            tiempoInicioApuntandoMs = 0L
            ocultarCardAutoDetectado()
            binding.screenExplorer.arOverlay.estadoReticulo =
                CameraOverlayView.EstadoReticulo.LIBRE
        }

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
        analizadorEscena = AnalizadorEscena()
        executorAnalisis = Executors.newSingleThreadExecutor()

        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try
            {
                val cameraProvider = providerFuture.get()

                val preview = Preview.Builder().build().also { p ->
                    p.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

                // Use case de análisis: procesa un frame solo cuando el usuario
                // presiona Scan (analisisSolicitado). El resto se descarta de
                // inmediato para no gastar batería ni CPU.
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executorAnalisis) { imageProxy ->
                    if (analisisSolicitado)
                    {
                        analisisSolicitado = false
                        analizadorEscena.analizar(imageProxy) { resultado ->
                            resultadoEscena = resultado
                        }
                    }
                    else
                    {
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
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
                actualizarOverlayAr()
            }
        }
        sensorFusion.iniciar()
    }

    private fun iniciarUbicacion()
    {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback    = crearLocationCallback()
        iniciarActualizacionUbicacion()
    }

    private fun iniciarActualizacionUbicacion()
    {
        try
        {
            // Obtener la última ubicación conocida de inmediato
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null)
                {
                    ubicacionActual = location
                    actualizarExplorer()
                }
            }

            // Solicitar actualizaciones periódicas con alta precisión
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
                .setMinUpdateDistanceMeters(5f)
                .build()

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
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
        actualizarOverlayAr()
    }

    /** Devuelve el DEM si ya terminó de cargar, o null para usar el fallback. */
    private fun demActivo(): ModeloElevacion?
    {
        return if (demRepository.cargado) demRepository else null
    }

    /**
     * Núcleo AR del modo Explorer: ancla las etiquetas de los lugares visibles
     * a la imagen de la cámara según brújula + pitch en tiempo real.
     * Throttle a 10 Hz para no recalcular geometría a la frecuencia del sensor.
     */
    private fun actualizarOverlayAr()
    {
        if (!::sensorFusion.isInitialized) return
        val ubicacion = ubicacionActual ?: return

        // Sin brújula no hay dirección confiable: no se dibujan etiquetas ancladas
        if (sensorFusion.modo == SensorFusion.Modo.ACCEL_ONLY ||
            sensorFusion.modo == SensorFusion.Modo.NINGUNO)
        {
            return
        }

        val ahora = System.currentTimeMillis()
        if (ahora - ultimoOverlayMs < 100L) return
        ultimoOverlayMs = ahora

        val puntos = GeoCalculator.filtrarPuntosVisibles(
            latUsuario        = ubicacion.latitude,
            lonUsuario        = ubicacion.longitude,
            elevacionUsuarioM = elevacionUsuarioM,
            azimuth           = sensorFusion.azimuth,
            pitch             = sensorFusion.pitch,
            todos             = lugaresFiltrados(),
            dem               = demActivo()
        )
        binding.screenExplorer.arOverlay.actualizarPuntos(puntos)
        manejarAutoDeteccion(puntos)
    }

    private fun actualizarBrujula()
    {
        if (!::sensorFusion.isInitialized) return

        val ex = binding.screenExplorer

        // Indicador de diagnóstico: modo de sensor + azimuth/pitch en vivo.
        // Si el azimuth NO cambia al girar el teléfono, el dispositivo
        // no tiene magnetómetro (modo ACCEL_ONLY) y el escaneo cae a
        // proximidad GPS sin dirección.
        val azimuthTexto = String.format("%03.0f°%s", sensorFusion.azimuth, puntoCardinal(sensorFusion.azimuth))
        val pitchTexto   = String.format("%+.0f°", sensorFusion.pitch)

        ex.estadoBrujula.text = when (sensorFusion.modo)
        {
            SensorFusion.Modo.ACCEL_ONLY -> "Sin brújula (GPS) · $azimuthTexto"
            SensorFusion.Modo.NINGUNO    -> "Sin sensores"
            else                         -> "${sensorFusion.descripcionModo()} · $azimuthTexto · $pitchTexto"
        }
    }

    /** Convierte un azimuth en grados a punto cardinal abreviado. */
    private fun puntoCardinal(azimuth: Float): String
    {
        val direcciones = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val indice = (((azimuth + 22.5f) % 360f) / 45f).toInt()
        return direcciones[indice.coerceIn(0, 7)]
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
        sc.viewfinder.visibility         = View.VISIBLE
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

        // Solicitar análisis de escena con ML Kit (corre en paralelo a la animación)
        resultadoEscena = null
        analisisSolicitado = true

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

        val sc = binding.screenScan

        // Filtro de escena: si ML Kit determinó que la cámara NO ve un paisaje
        // (ej: interior, laptop, pared), se rechaza la identificación.
        // Si el análisis no terminó a tiempo o falló, se permite continuar.
        val escena = resultadoEscena
        if (escena != null && !escena.esPaisaje)
        {
            sc.scanStep3.text = getString(R.string.scan_no_paisaje)
            return
        }

        // Seleccionar modo de búsqueda según hardware disponible
        val sinBrujula = sensorFusion.modo == SensorFusion.Modo.ACCEL_ONLY ||
                         sensorFusion.modo == SensorFusion.Modo.NINGUNO

        val candidatos = if (sinBrujula)
        {
            // Sin magnetómetro: mostrar los más cercanos por GPS, sin filtrar por dirección
            GeoCalculator.buscarCercanos(
                latUsuario        = ubicacion.latitude,
                lonUsuario        = ubicacion.longitude,
                elevacionUsuarioM = elevacionUsuarioM,
                todos             = lugaresFiltrados()
            )
        }
        else
        {
            GeoCalculator.escanear(
                latUsuario        = ubicacion.latitude,
                lonUsuario        = ubicacion.longitude,
                elevacionUsuarioM = elevacionUsuarioM,
                azimuth           = sensorFusion.azimuth,
                pitch             = sensorFusion.pitch,
                todos             = lugaresFiltrados(),
                dem               = demActivo()
            )
        }

        if (candidatos.isEmpty())
        {
            sc.scanStep3.text = getString(R.string.scan_no_result)
            return
        }

        // Confirmación visual: la cámara debe ver el TIPO de relieve del candidato.
        // Geometría dice "ahí hay un volcán", pero si ML Kit solo vio nubes/neblina,
        // el usuario no puede verlo y la app tampoco debe anunciarlo.
        // De noche o si el análisis falló, se omite esta confirmación.
        val candidatosConfirmados = if (escena == null || escena.esNocturno)
        {
            candidatos
        }
        else
        {
            candidatos.filter { (lugar, _) ->
                when (lugar.tipo)
                {
                    "lago", "laguna" -> escena.veAgua
                    else             -> escena.veRelieve
                }
            }
        }

        if (candidatosConfirmados.isEmpty())
        {
            // Hay algo en esa dirección, pero la cámara no lo distingue (nubes/neblina)
            val oculto = candidatos.first()
            sc.scanStep3.text = getString(
                R.string.scan_oculto,
                oculto.first.nombre,
                oculto.second.distanciaKm.toInt()
            )
            return
        }

        val (lugar, punto) = candidatosConfirmados.first()

        // Guardar en historial
        val entrada = HistorialManager.crearEntrada(
            nombre        = lugar.nombre,
            tipo          = lugar.tipo,
            latitud       = lugar.latitud,
            longitud      = lugar.longitud,
            elevacionMsnm = lugar.elevacionMsnm,
            descripcion   = lugar.descripcion,
            distanciaKm   = punto.distanciaKm,
            activo        = lugar.activo
        )
        historialManager.guardar(entrada)

        // Poblar resultado
        sc.resultadoNombre.text = lugar.nombre
        sc.resultadoElevacion.text = "${lugar.elevacionMsnm}m"

        val tipo = lugar.tipo.replaceFirstChar { it.uppercase() }
        val activo = if (lugar.tipo == "volcan" && lugar.activo) " · Activo" else ""
        val modoBadge = if (sinBrujula) " · Más cercano" else ""
        sc.resultadoBadge.text = "$tipo$activo$modoBadge"

        val distanciaTexto = if (punto.distanciaKm < 10.0)
        {
            String.format("%.1f km", punto.distanciaKm)
        }
        else
        {
            "${punto.distanciaKm.toInt()} km"
        }

        val notaBrujula  = if (sinBrujula) "\n\n⚠️ Sin brújula — resultado por proximidad GPS" else ""
        val notaNocturna = if (escena?.esNocturno == true) "\n\n🌙 Modo nocturno — identificado por GPS y brújula" else ""
        sc.resultadoDescripcion.text = "${lugar.descripcion}\n\n$distanciaTexto · ${lugar.elevacionMsnm} msnm$notaBrujula$notaNocturna"

        sc.btnCerrarResultado.setOnClickListener {
            sc.panelResultadoScan.visibility = View.GONE
            sc.panelEstadoScan.visibility    = View.GONE
            sc.viewfinder.visibility         = View.VISIBLE
        }

        sc.btnVerEnMaps.setOnClickListener { abrirEnMaps(lugar) }

        // Ocultar el visor al mostrar el resultado (no dejar el marco verde encima)
        sc.viewfinder.visibility         = View.GONE
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
