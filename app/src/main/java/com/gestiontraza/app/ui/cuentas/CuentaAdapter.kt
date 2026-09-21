package com.gestiontraza.app.ui.cuentas

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gestiontraza.app.R
import com.gestiontraza.app.data.SessionManager

class CuentaAdapter(
    private val onSeleccionar: (id: String) -> Unit,
    private val onEliminar: (id: String, nombre: String) -> Unit
) : ListAdapter<SessionManager.CuentaResumen, RecyclerView.ViewHolder>(
    object : DiffUtil.ItemCallback<SessionManager.CuentaResumen>() {
        override fun areItemsTheSame(a: SessionManager.CuentaResumen, b: SessionManager.CuentaResumen) = a.id == b.id
        override fun areContentsTheSame(a: SessionManager.CuentaResumen, b: SessionManager.CuentaResumen) = a == b
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cuenta, parent, false)
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val tvEstado = holder.itemView.findViewById<TextView>(R.id.tvEstadoCuenta)
        val btnEliminar = holder.itemView.findViewById<TextView>(R.id.btnEliminar)

        holder.itemView.findViewById<TextView>(R.id.tvNombre).text = item.nombre

        if (item.activa) {
            holder.itemView.setBackgroundResource(R.drawable.bg_caravana_ok)
            tvEstado.text = "✓ Cuenta activa"
            tvEstado.setTextColor(ctx.getColor(R.color.verde_ok))
            btnEliminar.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_cuenta_normal)
            tvEstado.text = "Tocá para usar esta cuenta"
            tvEstado.setTextColor(ctx.getColor(R.color.gris_texto))
            btnEliminar.visibility = View.VISIBLE
            btnEliminar.setOnClickListener { onEliminar(item.id, item.nombre) }
            holder.itemView.setOnClickListener { onSeleccionar(item.id) }
        }
    }
}
