package com.geoar.guatemala

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * Resultado del análisis de escena de un frame de cámara.
 */
data class ResultadoEscena(
    val esPaisaje: Boolean,      // true si la cámara ve un paisaje exterior
    val esNocturno: Boolean,     // true si el frame es muy oscuro (noche)
    val veRelieve: Boolean,      // true si la cámara distingue montaña/volcán/cerro
    val veAgua: Boolean,         // true si la cámara distingue lago/río/agua
    val etiquetas: List<String>  // etiquetas detectadas (para debug)
)

/**
 * Analiza frames de cámara con ML Kit Image Labeling (modelo preentrenado,
 * offline, sin necesidad de entrenamiento propio) para determinar si el
 * usuario realmente está apuntando a un paisaje exterior.
 *
 * Esto da la sensación "Google Lens": si la cámara ve una laptop, una pared
 * o un interior, el escaneo se rechaza en lugar de identificar a ciegas el
 * accidente geográfico que esté en esa dirección de brújula.
 *
 * Caso nocturno: de noche la cámara ve casi negro y ML Kit no detecta nada.
 * En ese caso se permite el escaneo (la identificación por sensores funciona
 * de noche, que es justamente la ventaja sobre un Lens basado en imagen),
 * marcando el resultado como nocturno para avisar al usuario.
 */
class AnalizadorEscena
{
    companion object
    {
        private const val TAG = "AnalizadorEscena"

        // Confianza mínima para aceptar una etiqueta de ML Kit
        private const val CONFIANZA_MINIMA = 0.55f

        // Luminancia promedio (0-255) bajo la cual el frame se considera nocturno
        private const val UMBRAL_NOCTURNO = 35.0

        // Etiquetas del modelo base de ML Kit que indican paisaje exterior.
        // Referencia: https://developers.google.com/ml-kit/vision/image-labeling/label-map
        private val ETIQUETAS_PAISAJE = setOf(
            "Sky", "Mountain", "Cloud", "Volcano", "Hill", "Valley",
            "Lake", "River", "Water", "Waterfall", "Beach", "Ocean", "Sea",
            "Sunset", "Sunrise", "Horizon", "Landscape", "Nature",
            "Tree", "Forest", "Jungle", "Grass", "Field", "Plain",
            "Fog", "Desert", "Cliff", "Rock", "Canyon", "Glacier", "Plant"
        )

        // Etiquetas que confirman que la cámara DISTINGUE relieve montañoso.
        // Si solo se ven nubes/neblina/cielo, el relieve NO está confirmado
        // y no se debe identificar un volcán que el usuario no puede ver.
        private val ETIQUETAS_RELIEVE = setOf(
            "Mountain", "Volcano", "Hill", "Valley", "Cliff",
            "Canyon", "Rock", "Glacier", "Ridge", "Summit"
        )

        // Etiquetas que confirman que la cámara ve un cuerpo de agua
        private val ETIQUETAS_AGUA = setOf(
            "Lake", "River", "Water", "Waterfall", "Beach", "Ocean", "Sea", "Lagoon"
        )
    }

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(CONFIANZA_MINIMA)
            .build()
    )

    /**
     * Analiza un frame y reporta por callback si es un paisaje exterior.
     * Cierra el ImageProxy al terminar (requisito de CameraX).
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun analizar(imageProxy: ImageProxy, callback: (ResultadoEscena) -> Unit)
    {
        val mediaImage = imageProxy.image
        if (mediaImage == null)
        {
            imageProxy.close()
            callback(ResultadoEscena(
                esPaisaje = false, esNocturno = false,
                veRelieve = false, veAgua = false, etiquetas = emptyList()
            ))
            return
        }

        // Luminancia promedio del plano Y (rápido, sin convertir el frame completo)
        val luminancia = calcularLuminanciaPromedio(imageProxy)

        if (luminancia < UMBRAL_NOCTURNO)
        {
            // Escena nocturna: ML Kit no es confiable a oscuras.
            // Se permite el escaneo por sensores con aviso de modo nocturno
            // (veRelieve/veAgua en true para no bloquear la confirmación visual).
            imageProxy.close()
            callback(ResultadoEscena(
                esPaisaje = true, esNocturno = true,
                veRelieve = true, veAgua = true, etiquetas = emptyList()
            ))
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        labeler.process(inputImage)
            .addOnSuccessListener { labels ->
                val nombres = labels.map { it.text }
                val esPaisaje = nombres.any { it in ETIQUETAS_PAISAJE }
                val veRelieve = nombres.any { it in ETIQUETAS_RELIEVE }
                val veAgua    = nombres.any { it in ETIQUETAS_AGUA }
                Log.d(TAG, "Etiquetas: $nombres → paisaje=$esPaisaje relieve=$veRelieve agua=$veAgua (lum=$luminancia)")
                callback(ResultadoEscena(esPaisaje, esNocturno = false, veRelieve, veAgua, nombres))
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Fallo en ML Kit, se permite el escaneo por sensores", e)
                // Ante un fallo del modelo no bloqueamos la función principal
                callback(ResultadoEscena(
                    esPaisaje = true, esNocturno = false,
                    veRelieve = true, veAgua = true, etiquetas = emptyList()
                ))
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * Calcula la luminancia promedio muestreando el plano Y del frame YUV.
     * Muestrea 1 de cada 32 píxeles para no bloquear el hilo de análisis.
     */
    private fun calcularLuminanciaPromedio(imageProxy: ImageProxy): Double
    {
        return try
        {
            val buffer = imageProxy.planes[0].buffer
            buffer.rewind()
            var suma = 0L
            var conteo = 0
            var i = 0
            while (i < buffer.remaining())
            {
                suma += (buffer.get(i).toInt() and 0xFF)
                conteo++
                i += 32
            }
            if (conteo == 0) 255.0 else suma.toDouble() / conteo
        }
        catch (e: Exception)
        {
            255.0 // ante un error, asumir luz normal para no activar modo nocturno por accidente
        }
    }

    fun liberar()
    {
        labeler.close()
    }
}
