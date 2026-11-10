# GeoAR Guatemala — App Android

## Concepto

App de realidad aumentada para Android que identifica accidentes geográficos
(volcanes, cerros, montañas, lagos, lagunas) usando la cámara del teléfono,
geolocalización y sensores de orientación del dispositivo.

**No requiere IA** — usa trigonometría esférica + sensores nativos del teléfono.

## Cómo funciona (lógica core)

Funciona igual que las apps de astronomía que dibujan los astros sobre el cielo:
no analiza la imagen, sino que sabe dónde está el usuario y hacia dónde apunta la cámara.

1. **CameraX** muestra el preview de cámara fullscreen y entrega la distancia focal
   y el tamaño del sensor (campo de visión real de la cámara)
2. **SensorFusion.kt** entrega la matriz de rotación completa del teléfono, suavizada
   y corregida al norte verdadero
3. **GPS** obtiene la posición y elevación del usuario
4. **GeoCalculator.kt** calcula bearing, distancia y ángulo de elevación (con curvatura
   terrestre) de cada punto en `lugares.json`, y descarta los que no pueden verse.
   Solo se recalcula cuando cambia la ubicación
5. **ArProyeccion.kt** proyecta cada lugar al píxel exacto de la pantalla (modelo de
   cámara estenopeica) en cada lectura de sensor
6. **CameraOverlayView.kt** dibuja las etiquetas con Canvas sin que se encimen
7. El lugar a menos de 6° de la mira central queda "enfocado"; el botón de escanear
   muestra su ficha. Entre 6° y 15° se muestra como "coincidencia probable"; más
   lejos se responde que no se reconoce nada (nunca inventar un lugar)

## Stack técnico

- Lenguaje: Kotlin
- minSdk: 26 | targetSdk: 34
- CameraX (`androidx.camera`) para preview
- `SensorManager` + `TYPE_ROTATION_VECTOR` para orientación fusionada
- `LocationManager` nativo (GPS + red) para ubicación — pendiente migrar a `FusedLocationProviderClient`
- Canvas personalizado para overlay AR
- Google AdMob — banner en la parte inferior (pendiente: aún no está integrado en el código)
- JSON local (`assets/lugares.json`) como base de datos de accidentes geográficos
- Gradle con Kotlin DSL (`build.gradle.kts`)

## Estructura de archivos

```
app/
└── src/main/
    ├── java/com/geoar/guatemala/
    │   ├── MainActivity.kt          ← Actividad principal, permisos, setup
    │   ├── CameraOverlayView.kt     ← View personalizado que dibuja el AR overlay
    │   ├── GeoCalculator.kt         ← Bearing, distancia, elevación, candidatos visibles (puro, sin Android)
    │   ├── ArProyeccion.kt          ← Proyección mundo → pantalla, campo de visión, suavizado (puro, sin Android)
    │   ├── SensorFusion.kt          ← Lee sensores, devuelve matriz de rotación + azimuth/pitch
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
- `calcularAnguloElevacion(distanciaKm, elevacionUsuario, elevacionPunto): Double` — incluye curvatura y refracción
- `estaOcultoPorCurvatura(distanciaKm, elevacionUsuario, elevacionPunto): Boolean`
- `esPotencialmenteVisible(tipo, distanciaKm, elevacionUsuario, elevacionPunto): Boolean`
- `calcularCandidatos(lat, lon, elevacionUsuario, todos): List<Candidato>`
- `puntoCardinal(azimuth): String`

No hay modelo de terreno (DEM): la visibilidad es una aproximación. Se ocultan los lagos
que no se miran desde arriba y los lugares a más de 40 km que quedan bajo la horizontal.

## Lógica de ArProyeccion.kt

- `proyectar(matrizRotacion, bearing, elevacion, focalPx, ancho, alto): Proyeccion?` — null si queda detrás de la cámara
- `calcularFocalPx(focalMm, sensorLargoMm, sensorCortoMm, anchoVista, altoVista): Float`
- `azimuthCamara(matriz)`, `pitchCamara(matriz)`
- `matrizDesdeGravedadYAzimuth(...)` — para teléfonos sin brújula
- `aplicarDeclinacion(matriz, grados)`, `suavizarMatriz(estado, nueva, alpha)`

Convención: mundo = R · dispositivo; filas de R = Este, Norte, Arriba; la cámara mira hacia -Z.

## CameraOverlayView.kt — diseño del overlay AR

Cada etiqueta visible muestra:
- Rectángulo redondeado semitransparente (fondo negro 70% opacidad)
- Nombre del lugar en blanco, bold, 16sp
- Ícono según tipo (🌋 volcán activo, ⛰️ cerro/montaña, 💧 lago/laguna)
- Distancia en km desde el usuario
- Elevación en msnm
- Línea vertical desde la etiqueta hacia abajo indicando dirección
- Las etiquetas no se enciman (la menos prioritaria sube hasta hallar espacio); máximo 8
- El lugar en la mira se resalta; tocar una etiqueta abre su ficha
- Línea de estado arriba (rumbo, precisión GPS, elevación) y avisos (calibrar brújula, GPS débil)

Colores: volcanes activos en rojo `#F44336`, volcanes inactivos en naranja `#FF6600`,
cerros en blanco, lagos en celeste `#4FC3F7`.

## SensorFusion.kt

- Usar `TYPE_ROTATION_VECTOR` (fusión del sistema, más estable que magnetómetro solo),
  con fallbacks: geomagnetic rotation → acelerómetro + magnetómetro → acelerómetro + rumbo GPS
- Expone la matriz de rotación completa (no solo azimuth/pitch) para compensar también
  la inclinación lateral del teléfono
- Low-pass filter (alpha = 0.1) aplicado sobre la matriz, con re-ortonormalización;
  así no hay problemas de wrap-around 0°/360°
- `TYPE_ROTATION_VECTOR` apunta al norte **magnético**: la declinación se corrige con
  `GeomagneticField` (MainActivity la actualiza al conocer la ubicación)
- Avisa cuando la brújula necesita calibración

## MainActivity.kt

- Pedir permisos: `CAMERA`, `ACCESS_FINE_LOCATION` al inicio con `ActivityResultContracts`
- Si no hay permisos, mostrar pantalla explicativa amigable
- Setup de CameraX con Preview use case
- Setup de SensorFusion y LocationManager
- Pasar datos al CameraOverlayView via `invalidate()` en cada cambio de sensor
- Incluir AdView de AdMob en la parte inferior (banner 320x50)
- Botón flotante de info (ⓘ) y botón de escanear: muestran la ficha del lugar en la mira

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
- [x] Base de datos local con 50 lugares de Guatemala
- [x] Etiquetas ubicadas con el campo de visión real de la cámara y sin encimarse
- [x] Identificación del lugar en la mira (estilo Lens)
- [ ] Ocultar lugares tapados por el relieve (requiere modelo de elevación del terreno)
- [ ] Filtro por tipo (volcanes, cerros, lagos) con toggles en UI
- [ ] Banner AdMob inferior
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

## Verificación

- Los tests de `GeoCalculator` y `ArProyeccion` son JVM puros: `./gradlew test`
- La precisión real del AR solo puede validarse en un teléfono, al aire libre

## Configuración necesaria antes de publicar

1. Reemplazar los IDs de AdMob de prueba con los IDs reales:
   - Application ID en `AndroidManifest.xml`
   - Banner Ad Unit ID en `strings.xml` (`admob_banner_id`)
2. Obtener `gradle-wrapper.jar` ejecutando `gradle wrapper --gradle-version 8.6`
   en la raíz del proyecto (requiere Gradle instalado localmente).
