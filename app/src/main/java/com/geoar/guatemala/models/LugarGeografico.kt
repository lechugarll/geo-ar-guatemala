package com.geoar.guatemala.models

/**
 * Modelo de datos para un accidente geográfico de Guatemala.
 * Se carga desde assets/lugares.json mediante Gson.
 */
data class LugarGeografico(
    val id: Int,
    val nombre: String,
    val tipo: String,           // "volcan", "cerro", "montana", "lago", "laguna", "sierra"
    val latitud: Double,
    val longitud: Double,
    val elevacionMsnm: Int,
    val descripcion: String,
    val activo: Boolean = false  // Solo relevante para volcanes
)
