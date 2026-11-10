package com.geoar.guatemala

import android.content.Context
import java.io.DataInputStream

/**
 * Fuente de elevación del terreno. Abstracción para que GeoCalculator pueda
 * consultar la altura del relieve en cualquier coordenada sin depender de Android
 * (los tests unitarios pueden pasar un modelo sintético).
 */
fun interface ModeloElevacion
{
    /**
     * Elevación del terreno en metros sobre el nivel del mar, o null si la
     * coordenada está fuera de la cobertura del modelo.
     */
    fun elevacionEn(lat: Double, lon: Double): Double?
}

/**
 * Modelo digital de elevación (DEM) de Guatemala cargado desde assets.
 *
 * Formato binario de assets/dem_guatemala.bin (big-endian, generado por
 * tools/generar_dem.py a partir de datos SRTM):
 *
 *   bytes 0-3   magic "DEM1"
 *   double      latNorte   — latitud del borde norte de la grilla
 *   double      lonOeste   — longitud del borde oeste de la grilla
 *   double      pasoGrados — tamaño de celda en grados (~0.01° ≈ 1.1 km)
 *   int32       filas      — número de filas (norte → sur)
 *   int32       columnas   — número de columnas (oeste → este)
 *   int16[]     elevaciones en metros, fila por fila desde el norte
 *
 * Con paso de 0.01° el archivo pesa ~450 KB para todo el país: viable
 * para empaquetar en el APK y consultar offline sin latencia.
 */
class DemRepository : ModeloElevacion
{
    @Volatile private var datos: ShortArray = ShortArray(0)
    private var latNorte = 0.0
    private var lonOeste = 0.0
    private var pasoGrados = 0.0
    private var filas = 0
    private var columnas = 0

    /** true si el DEM se cargó correctamente y puede consultarse */
    @Volatile var cargado: Boolean = false
        private set

    /**
     * Carga el DEM desde assets. Devuelve false si el archivo no existe o está
     * corrupto — en ese caso la app sigue funcionando con la oclusión por catálogo.
     */
    fun cargar(context: Context): Boolean
    {
        return try
        {
            DataInputStream(context.assets.open("dem_guatemala.bin").buffered()).use { entrada ->
                val magic = ByteArray(4)
                entrada.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "DEM1")
                {
                    return false
                }

                latNorte   = entrada.readDouble()
                lonOeste   = entrada.readDouble()
                pasoGrados = entrada.readDouble()
                filas      = entrada.readInt()
                columnas   = entrada.readInt()

                if (filas <= 1 || columnas <= 1 || pasoGrados <= 0.0)
                {
                    return false
                }

                val grilla = ShortArray(filas * columnas)
                for (i in grilla.indices)
                {
                    grilla[i] = entrada.readShort()
                }
                datos = grilla
            }
            cargado = true
            true
        }
        catch (e: Exception)
        {
            // Sin DEM empaquetado: fallback silencioso a oclusión por catálogo
            cargado = false
            false
        }
    }

    /**
     * Elevación del terreno con interpolación bilineal entre las 4 celdas vecinas.
     */
    override fun elevacionEn(lat: Double, lon: Double): Double?
    {
        if (!cargado) return null

        // Posición fraccional dentro de la grilla (fila 0 = norte)
        val fila = (latNorte - lat) / pasoGrados
        val col  = (lon - lonOeste) / pasoGrados

        if (fila < 0.0 || col < 0.0 || fila > (filas - 1).toDouble() || col > (columnas - 1).toDouble())
        {
            return null
        }

        val f0 = fila.toInt().coerceAtMost(filas - 2)
        val c0 = col.toInt().coerceAtMost(columnas - 2)
        val df = fila - f0
        val dc = col - c0

        val e00 = datos[f0 * columnas + c0].toDouble()
        val e01 = datos[f0 * columnas + c0 + 1].toDouble()
        val e10 = datos[(f0 + 1) * columnas + c0].toDouble()
        val e11 = datos[(f0 + 1) * columnas + c0 + 1].toDouble()

        return e00 * (1 - df) * (1 - dc) +
               e01 * (1 - df) * dc +
               e10 * df * (1 - dc) +
               e11 * df * dc
    }
}
