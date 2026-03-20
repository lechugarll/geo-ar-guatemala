package com.geoar.guatemala

/**
 * Registro de un accidente geográfico escaneado por el usuario.
 * Se guarda en SharedPreferences como JSON via Gson.
 */
data class HistorialEntry(
    val id: String,
    val nombre: String,
    val tipo: String,
    val latitud: Double,
    val longitud: Double,
    val elevacionMsnm: Int,
    val descripcion: String,
    val fechaEscaneo: Long,
    val distanciaKm: Double,
    val activo: Boolean = false
)
