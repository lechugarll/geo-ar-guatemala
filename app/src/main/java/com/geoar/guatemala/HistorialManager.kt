package com.geoar.guatemala

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Gestiona la persistencia del historial de escaneos.
 * Almacena los registros en SharedPreferences como JSON.
 */
class HistorialManager(context: Context)
{
    private val prefs = context.getSharedPreferences("geoar_historial", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val KEY = "entries"

    fun guardar(entry: HistorialEntry)
    {
        val lista = cargarTodos().toMutableList()
        // Evitar duplicados del mismo lugar en el mismo día
        val hoy = entry.fechaEscaneo / 86_400_000L
        val yaTiene = lista.any { it.nombre == entry.nombre && it.fechaEscaneo / 86_400_000L == hoy }
        if (!yaTiene)
        {
            lista.add(0, entry)
            // Limitar el historial a 500 entradas para no saturar SharedPreferences
            if (lista.size > 500) lista.removeAt(lista.lastIndex)
        }
        prefs.edit().putString(KEY, gson.toJson(lista)).apply()
    }

    fun cargarTodos(): List<HistorialEntry>
    {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try
        {
            val tipo = object : TypeToken<List<HistorialEntry>>() {}.type
            gson.fromJson(json, tipo)
        }
        catch (e: Exception)
        {
            emptyList()
        }
    }

    fun limpiar()
    {
        prefs.edit().remove(KEY).apply()
    }

    companion object
    {
        fun crearEntrada(
            nombre: String,
            tipo: String,
            latitud: Double,
            longitud: Double,
            elevacionMsnm: Int,
            descripcion: String,
            distanciaKm: Double,
            activo: Boolean = false
        ): HistorialEntry
        {
            return HistorialEntry(
                id = UUID.randomUUID().toString(),
                nombre = nombre,
                tipo = tipo,
                latitud = latitud,
                longitud = longitud,
                elevacionMsnm = elevacionMsnm,
                descripcion = descripcion,
                fechaEscaneo = System.currentTimeMillis(),
                distanciaKm = distanciaKm,
                activo = activo
            )
        }
    }
}
