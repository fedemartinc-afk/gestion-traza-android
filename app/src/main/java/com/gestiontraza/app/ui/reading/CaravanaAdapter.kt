package com.gestiontraza.app.ui.reading

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gestiontraza.app.R

class CaravanaAdapter(
    private val onEliminar: (CaravanaItem) -> Unit = {}
) : ListAdapter<CaravanaItem, CaravanaAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val container: ViewGroup = view.findViewById(R.id.cardContainer)
        val tvTag: TextView = view.findViewById(R.id.tvTag)
        val dot: View = view.findViewById(R.id.dot)
        val tvCodigo: TextView = view.findViewById(R.id.tvCodigo)
        val tvSexo: TextView = view.findViewById(R.id.tvSexo)
        val btnEliminar: ImageButton = view.findViewById(R.id.btnEliminar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_caravana, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.tvCodigo.text = item.codigo
        holder.btnEliminar.setOnClickListener { onEliminar(item) }

        if (item.sexo.isNotEmpty()) {
            holder.tvSexo.visibility = View.VISIBLE
            holder.tvSexo.text = item.sexo
        } else {
            holder.tvSexo.visibility = View.GONE
        }

        // Rojo si está duplicada o si se leyó más de una vez con sexos distintos
        // (en ese caso se marcan todas las lecturas de esa caravana).
        if (item.esDuplicado || item.sexoConflicto) {
            holder.container.setBackgroundResource(R.drawable.bg_caravana_dup)
            holder.dot.setBackgroundResource(R.drawable.circle_red)
            holder.tvCodigo.setTextColor(ctx.getColor(R.color.rojo_error))
            holder.tvSexo.setTextColor(ctx.getColor(R.color.rojo_error))
            holder.tvTag.visibility = View.VISIBLE
            holder.tvTag.text = when {
                item.sexoConflicto && item.esDuplicado -> "DUPLICADA · SEXO DISTINTO"
                item.sexoConflicto -> "SEXO DISTINTO EN OTRA LECTURA"
                else -> "DUPLICADO"
            }
        } else {
            holder.container.setBackgroundResource(R.drawable.bg_caravana_ok)
            holder.dot.setBackgroundResource(R.drawable.circle_green)
            holder.tvCodigo.setTextColor(ctx.getColor(R.color.verde_ok))
            holder.tvSexo.setTextColor(ctx.getColor(R.color.gris_texto))
            holder.tvTag.visibility = View.GONE
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<CaravanaItem>() {
            override fun areItemsTheSame(a: CaravanaItem, b: CaravanaItem) = a.id == b.id
            override fun areContentsTheSame(a: CaravanaItem, b: CaravanaItem) = a == b
        }
    }
}
