package com.gestiontraza.app.ui.importar.sesiones

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gestiontraza.app.data.SesionGuardada
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.databinding.ItemImportarSesionBinding

class ImportarSesionAdapter(
    private val onAbrir: (SesionGuardada) -> Unit,
    private val onEliminar: (SesionGuardada) -> Unit,
    private val onCompartir: (SesionGuardada) -> Unit,
    private val onEditar: (SesionGuardada) -> Unit,
    private val onRenombrar: (SesionGuardada) -> Unit,
    /** Se llama al tocar una sesión mientras se eligen las que se van a unir. */
    private val onAlternar: (SesionGuardada) -> Unit = {}
) : ListAdapter<SesionGuardada, ImportarSesionAdapter.VH>(DIFF) {

    /** true: se eligen sesiones para unir (casillas visibles, sin los botones de cada sesión). */
    var modoUnir: Boolean = false
        set(valor) { field = valor; notifyDataSetChanged() }

    /** Rutas de los archivos elegidos para unir. */
    var seleccionadas: Set<String> = emptySet()
        set(valor) { field = valor; notifyDataSetChanged() }

    inner class VH(val binding: ItemImportarSesionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemImportarSesionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.tvNombre.text = item.nombre
        holder.binding.tvDetalle.text =
            "${item.origen} · ${SessionFileStore.FORMATO_FECHA.format(item.fecha)} · ${item.lineas} línea${if (item.lineas != 1) "s" else ""}"
        holder.binding.cbSeleccion.visibility = if (modoUnir) View.VISIBLE else View.GONE
        holder.binding.cbSeleccion.isChecked = item.archivo.absolutePath in seleccionadas
        holder.binding.llAcciones.visibility = if (modoUnir) View.GONE else View.VISIBLE
        holder.binding.root.setOnClickListener { if (modoUnir) onAlternar(item) else onAbrir(item) }
        holder.binding.btnEliminar.setOnClickListener { onEliminar(item) }
        holder.binding.btnCompartir.setOnClickListener { onCompartir(item) }
        holder.binding.btnEditar.setOnClickListener { onEditar(item) }
        holder.binding.btnRenombrar.setOnClickListener { onRenombrar(item) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SesionGuardada>() {
            override fun areItemsTheSame(a: SesionGuardada, b: SesionGuardada) = a.archivo.path == b.archivo.path
            override fun areContentsTheSame(a: SesionGuardada, b: SesionGuardada) = a == b
        }
    }
}
