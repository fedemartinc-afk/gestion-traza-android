package com.gestiontraza.app.ui.registrar

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gestiontraza.app.databinding.ItemDispositivoBinding

data class DispositivoData(
    val codigo: String,
    val especie: String,
    var sexo: String,
    var razaNombre: String,
    var razaCodigo: String,
    var nacimiento: String
)

class DispositivoAdapter(
    private val onEditar: (DispositivoData) -> Unit,
    private val onEliminar: (DispositivoData) -> Unit
) : ListAdapter<DispositivoData, DispositivoAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemDispositivoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDispositivoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.tvCodigo.text = item.codigo
        holder.binding.tvMetadata.text = buildString {
            append("Sexo: ${item.sexo}  |  Raza: ${item.razaNombre}  |  Nac: ${item.nacimiento}")
        }
        holder.binding.btnEditar.setOnClickListener { onEditar(item) }
        holder.binding.btnEliminar.setOnClickListener { onEliminar(item) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DispositivoData>() {
            override fun areItemsTheSame(a: DispositivoData, b: DispositivoData) = a.codigo == b.codigo
            override fun areContentsTheSame(a: DispositivoData, b: DispositivoData) = a == b
        }
    }
}
