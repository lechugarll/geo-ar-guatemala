# Reglas ProGuard para GeoAR Guatemala

# Conservar modelos de datos usados con Gson
-keep class com.geoar.guatemala.models.** { *; }
-keep class com.geoar.guatemala.LugaresRepository$LugaresJson { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# CameraX
-keep class androidx.camera.** { *; }

# AdMob
-keep public class com.google.android.gms.ads.** { public *; }
