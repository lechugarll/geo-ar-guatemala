package com.geoar.guatemala

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.geoar.guatemala.models.LugarGeografico

/**
 * Estructura del JSON raíz de lugares.json
 */
data class LugaresJson(
    val version: String,
    val fuente: String,
    val lugares: List<LugarGeografico>
)

/**
 * Repositorio responsable de cargar y parsear los accidentes geográficos
 * desde el archivo assets/lugares.json.
 */
class LugaresRepository(private val context: Context)
{
    private val gson = Gson()

    /**
     * Carga la lista completa de lugares geográficos desde assets.
     * Devuelve lista vacía en caso de error.
     */
    fun cargarLugares(): List<LugarGeografico>
    {
        return try
        {
            val json = context.assets
                .open("lugares.json")
                .bufferedReader()
                .use { it.readText() }

            val datos = gson.fromJson(json, LugaresJson::class.java)
            datos.lugares
        }
        catch (e: Exception)
        {
            // Loguear error en debug, devolver lista vacía para no crashear
            e.printStackTrace()
            emptyList()
        }
    }
}
