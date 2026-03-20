package com.geoar.guatemala

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialAdapter(
    private var entries: List<HistorialEntry>,
    private val onClick: (HistorialEntry) -> Unit
) : RecyclerView.Adapter<HistorialAdapter.ViewHolder>()
{
    private val sdf = SimpleDateFormat("MMM dd, yyyy", Locale("es", "GT"))

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    {
        val thumbnail: TextView = view.findViewById(R.id.item_thumbnail)
        val nombre: TextView = view.findViewById(R.id.item_nombre)
        val badge: TextView = view.findViewById(R.id.item_badge)
        val fecha: TextView = view.findViewById(R.id.item_fecha)
        val distancia: TextView = view.findViewById(R.id.item_distancia)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder
    {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_historial, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int)
    {
        val entry = entries[position]

        // Thumbnail: letra inicial sobre fondo de color según tipo
        holder.thumbnail.text = entry.nombre.first().uppercase()
        val thumbColor = thumbColorForTipo(entry.tipo, entry.activo)
        holder.thumbnail.setBackgroundColor(thumbColor)

        // Nombre
        holder.nombre.text = entry.nombre

        // Badge de tipo
        val (badgeText, badgeBg, badgeText2) = badgeForTipo(entry.tipo, entry.activo)
        holder.badge.text = badgeText
        holder.badge.setBackgroundColor(badgeBg)
        holder.badge.setTextColor(badgeText2)

        // Fecha
        holder.fecha.text = sdf.format(Date(entry.fechaEscaneo))

        // Distancia + elevación
        val dist = if (entry.distanciaKm < 10.0)
            String.format("%.1f km", entry.distanciaKm)
        else
            "${entry.distanciaKm.toInt()} km"
        holder.distancia.text = "$dist  ↑ ${entry.elevacionMsnm}m"

        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount(): Int = entries.size

    fun actualizarLista(nuevas: List<HistorialEntry>)
    {
        entries = nuevas
        notifyDataSetChanged()
    }

    // -------------------------------------------------------------------------

    private fun thumbColorForTipo(tipo: String, activo: Boolean): Int = when
    {
        tipo == "volcan" && activo -> Color.parseColor("#CCFF4444")
        tipo == "volcan"           -> Color.parseColor("#CCFF8C00")
        tipo == "lago"             -> Color.parseColor("#CC4FC3F7")
        tipo == "laguna"           -> Color.parseColor("#CC26C6DA")
        tipo == "sierra"           -> Color.parseColor("#CC8D6E63")
        else                       -> Color.parseColor("#CC78909C")
    }

    private fun badgeForTipo(tipo: String, activo: Boolean): Triple<String, Int, Int> = when
    {
        tipo == "volcan" && activo ->
            Triple("VOLCÁN ACTIVO", Color.parseColor("#33FF4444"), Color.parseColor("#FFFF6B6B"))
        tipo == "volcan" ->
            Triple("VOLCÁN", Color.parseColor("#33FFBA38"), Color.parseColor("#FFFFBA38"))
        tipo == "lago" ->
            Triple("LAGO", Color.parseColor("#334FC3F7"), Color.parseColor("#FF4FC3F7"))
        tipo == "laguna" ->
            Triple("LAGUNA", Color.parseColor("#3326C6DA"), Color.parseColor("#FF26C6DA"))
        tipo == "sierra" ->
            Triple("SIERRA", Color.parseColor("#33A1887F"), Color.parseColor("#FFA1887F"))
        tipo == "montana" ->
            Triple("MONTAÑA", Color.parseColor("#33AAAAAA"), Color.parseColor("#FFAAAAAA"))
        else ->
            Triple("CERRO", Color.parseColor("#33AAAAAA"), Color.parseColor("#FFAAAAAA"))
    }
}
