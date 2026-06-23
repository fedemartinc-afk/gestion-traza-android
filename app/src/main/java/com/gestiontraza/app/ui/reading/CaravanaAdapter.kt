package com.gestiontraza.app.ui.reading

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gestiontraza.app.R

class CaravanaAdapter : ListAdapter<CaravanaItem, CaravanaAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val container: ViewGroup = view.findViewById(R.id.cardContainer)
        val tvTag: TextView = view.findViewById(R.id.tvTag)
        val dot: View = view.findViewById(R.id.dot)
        val tvCodigo: TextView = view.findViewById(R.id.tvCodigo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_caravana, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.tvCodigo.text = item.codigo

        if (item.esDuplicado) {
            holder.container.setBackgroundResource(R.drawable.bg_caravana_dup)
            holder.dot.setBackgroundResource(R.drawable.circle_red)
            holder.tvCodigo.setTextColor(ctx.getColor(R.color.rojo_error))
            holder.tvTag.visibility = View.VISIBLE
            holder.tvTag.text = "DUPLICADO"
        } else {
            holder.container.setBackgroundResource(R.drawable.bg_caravana_ok)
            holder.dot.setBackgroundResource(R.drawable.circle_green)
            holder.tvCodigo.setTextColor(ctx.getColor(R.color.verde_ok))
            holder.tvTag.visibility = View.GONE
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<CaravanaItem>() {
            override fun areItemsTheSame(a: CaravanaItem, b: CaravanaItem) = a.codigo == b.codigo && a.esDuplicado == b.esDuplicado
            override fun areContentsTheSame(a: CaravanaItem, b: CaravanaItem) = a == b
        }
    }
}
