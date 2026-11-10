# GeoAR Guatemala — App Android

## Concepto

App de realidad aumentada para Android que identifica accidentes geográficos
(volcanes, cerros, montañas, lagos, lagunas) usando la cámara del teléfono,
geolocalización y sensores de orientación del dispositivo.

**No requiere IA** — usa trigonometría esférica + sensores nativos del teléfono.

## Cómo funciona (lógica core)

1. **CameraX** muestra el preview de cámara fullscreen en tiempo real
2. **SensorManager** lee azimuth (brújula) y pitch (ángulo vertical) continuamente
3. **GPS** obtiene la posición actual del usuario
4. **GeoCalculator.kt** calcula el bearing esférico entre usuario y cada punto en `lugares.json`
5. Se filtran los puntos cuyo bearing coincide con la dirección actual (±12° horizontal, ±20° vertical)
6. También se filtra por distancia máxima configurable (default: 150 km)
7. **CameraOverlayView.kt** dibuja etiquetas AR sobre los puntos visibles usando Canvas

## Stack técnico

- Lenguaje: Kotlin
- minSdk: 26 | targetSdk: 34
- CameraX (`androidx.camera`) para preview
- `SensorManager` + `TYPE_ROTATION_VECTOR` para orientación fusionada
- `FusedLocationProviderClient` para GPS
- Canvas personalizado para overlay AR
- Google AdMob — banner en la parte inferior
- JSON local (`assets/lugares.json`) como base de datos de accidentes geográficos
- Gradle con Kotlin DSL (`build.gradle.kts`)

## Estructura de archivos

```
app/
└── src/main/
    ├── java/com/geoar/guatemala/
    │   ├── MainActivity.kt          ← Actividad principal, permisos, setup
    │   ├── CameraOverlayView.kt     ← View personalizado que dibuja el AR overlay
    │   ├── GeoCalculator.kt         ← Bearing, distancia, filtrado de puntos visibles
    │   ├── SensorFusion.kt          ← Lee TYPE_ROTATION_VECTOR, devuelve azimuth/pitch
    │   ├── LugaresRepository.kt     ← Carga y parsea lugares.json
    │   └── models/
    │       └── LugarGeografico.kt   ← Data class del modelo
    ├── res/
    │   ├── layout/
    │   │   └── activity_main.xml    ← FrameLayout: CameraPreview + CameraOverlayView + AdView
    │   ├── values/
    │   │   ├── strings.xml
    │   │   └── colors.xml           ← Colores del overlay AR
    │   └── drawable/
    │       └── ic_marker.xml        ← Ícono vectorial para los marcadores
    └── assets/
        └── lugares.json             ← Base de datos de accidentes geográficos
```

## Modelo de datos — LugarGeografico.kt

```kotlin
data class LugarGeografico(
    val id: Int,
    val nombre: String,
    val tipo: String,         // "volcan", "cerro", "montana", "lago", "laguna", "sierra"
    val latitud: Double,
    val longitud: Double,
    val elevacionMsnm: Int,
    val descripcion: String,
    val activo: Boolean = false   // solo para volcanes
)
```

## Lógica de GeoCalculator.kt

- `calcularBearing(lat1, lon1, lat2, lon2): Double` — bearing esférico en grados 0-360
- `calcularDistanciaKm(lat1, lon1, lat2, lon2): Double` — Haversine
- `calcularAnguloElevacion(distanciaKm, elevacionUsuario, elevacionPunto): Double`
- `filtrarPuntosVisibles(posUsuario, azimuth, pitch, todos): List<Pair<LugarGeografico, PuntoVisible>>`
- `calcularPosicionEnPantalla(difHorizontal, difVertical, screenWidth, screenHeight): PointF`

## CameraOverlayView.kt — diseño del overlay AR

Cada etiqueta visible muestra:
- Rectángulo redondeado semitransparente (fondo negro 70% opacidad)
- Nombre del lugar en blanco, bold, 16sp
- Ícono según tipo (🌋 volcán activo, ⛰️ cerro/montaña, 💧 lago/laguna)
- Distancia en km desde el usuario
- Elevación en msnm
- Línea vertical desde la etiqueta hacia abajo indicando dirección

Colores: volcanes activos en rojo `#F44336`, volcanes inactivos en naranja `#FF6600`,
cerros en blanco, lagos en celeste `#4FC3F7`.

## SensorFusion.kt

- Usar `TYPE_ROTATION_VECTOR` (fusión del sistema, más estable que magnetómetro solo)
- Remap de ejes con `AXIS_X / AXIS_MINUS_Z` para modo portrait (pitch=0 = horizonte)
- Devolver azimuth suavizado con low-pass filter (alpha = 0.1) para evitar jitter
- Manejar correctamente el wrap-around 0°/360° en el filtro del azimuth
- La declinación magnética es manejada automáticamente por `TYPE_ROTATION_VECTOR`

## MainActivity.kt

- Pedir permisos: `CAMERA`, `ACCESS_FINE_LOCATION` al inicio con `ActivityResultContracts`
- Si no hay permisos, mostrar pantalla explicativa amigable
- Setup de CameraX con Preview use case
- Setup de SensorFusion y LocationManager
- Pasar datos al CameraOverlayView via `invalidate()` en cada cambio de sensor
- Incluir AdView de AdMob en la parte inferior (banner 320x50)
- Botón flotante de info (ⓘ) que muestra descripción del lugar más cercano al centro

## AndroidManifest.xml — permisos necesarios

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.sensor.compass" android:required="true" />
```

## Features fase 1 — MVP

- [x] Preview de cámara fullscreen sin lag
- [x] Overlay AR con nombre, distancia y elevación de accidentes visibles
- [x] Base de datos local con 35 lugares de Guatemala
- [ ] Filtro por tipo (volcanes, cerros, lagos) con toggles en UI
- [x] Banner AdMob inferior
- [x] Manejo de permisos con UX clara
- [x] Funciona offline (sin internet para el core)

## Features fase 2 — para pitch a INGUAT

- [ ] Ficha detallada por lugar (foto, historia, rutas, info turística)
- [ ] Modo lista: ver todos los lugares cercanos ordenados por distancia
- [ ] Compartir captura con overlay en redes sociales
- [ ] Multilanguage (español / inglés)
- [ ] Integración con datos de INSIVUMEH para estado de volcanes en tiempo real
- [ ] Mapa 2D complementario con todos los puntos

## Preferencias de código

- Estilo Allman para llaves `{` en línea nueva
- Comentarios en español
- Código COMPLETO, nunca omitir partes con `// ... resto del código`
- Data classes en archivo separado bajo `models/`
- Sin librerías de AR externas (ARCore), todo con Canvas nativo
- Manejo de errores con try-catch en operaciones de sensor y GPS

## Instrucciones de inicio — orden de construcción

1. Estructura de proyecto y `build.gradle.kts`
2. `LugarGeografico.kt` + `LugaresRepository.kt`
3. `GeoCalculator.kt` con funciones matemáticas y tests unitarios
4. `SensorFusion.kt`
5. `CameraOverlayView.kt`
6. `MainActivity.kt` integrando todo
7. `activity_main.xml` + `strings.xml` + `colors.xml`

## Configuración necesaria antes de publicar

1. Reemplazar los IDs de AdMob de prueba con los IDs reales:
   - Application ID en `AndroidManifest.xml`
   - Banner Ad Unit ID en `strings.xml` (`admob_banner_id`)
2. Obtener `gradle-wrapper.jar` ejecutando `gradle wrapper --gradle-version 8.6`
   en la raíz del proyecto (requiere Gradle instalado localmente).
3. Generar el DEM (modelo digital de elevación) para la oclusión por terreno real:
   `pip install requests pillow numpy && python tools/generar_dem.py`
   (una sola vez, requiere internet; produce `assets/dem_guatemala.bin` ~450 KB).
   Sin este archivo la app funciona igual usando la oclusión por catálogo.
