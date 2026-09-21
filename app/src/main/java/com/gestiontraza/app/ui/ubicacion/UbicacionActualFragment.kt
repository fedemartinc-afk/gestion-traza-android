package com.gestiontraza.app.ui.ubicacion

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentUbicacionActualBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Consulta masiva de caravanas que muestra unicamente donde esta cada una hoy:
 * RENSPA actual, establecimiento, titular, sexo y raza.
 */
class UbicacionActualFragment : Fragment() {

    private var _binding: FragmentUbicacionActualBinding? = null
    private val binding get() = _binding!!
    private val args: UbicacionActualFragmentArgs by navArgs()

    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>

    private data class ResultItem(
        val codigo: String,
        val ok: Boolean,
        val renspa: String,
        val establecimiento: String,
        val titular: String,
        val sexo: String,
        val raza: String,
        val error: String
    )

    private val resultados = mutableListOf<ResultItem>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentUbicacionActualBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        binding.tvContadorBar.text = "${caravanas.size} caravanas"

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnReintentar.setOnClickListener { reintentarFallidas() }

        mostrarResultados()
        consultar(caravanas)
    }

    private fun consultar(pendientes: List<String>) {
        if (session.wsUsername.isEmpty()) {
            showBanner(false, "Sin credenciales SENASA en la sesión")
            return
        }
        setCargando(true, pendientes.size)
        lifecycleScope.launch {
            val base = SenasaClient.senasaBase(session.senasaEnv)
            pendientes.forEachIndexed { idx, caravana ->
                binding.tvProgreso.text = "Consultando ${idx + 1} de ${pendientes.size}…"
                val estado = withContext(Dispatchers.IO) {
                    SenasaClient.consultarCaravana(base, session.wsUsername, session.wsToken, caravana)
                }
                val item = ResultItem(
                    codigo          = caravana,
                    ok              = estado.ok,
                    renspa          = estado.renspaActual,
                    establecimiento = estado.establecimiento,
                    titular         = estado.titular,
                    sexo            = estado.sexo,
                    raza            = estado.raza,
                    error           = estado.error
                )
                // Reemplaza el resultado previo si esta caravana ya se habia consultado
                val i = resultados.indexOfFirst { it.codigo == caravana }
                if (i >= 0) resultados[i] = item else resultados.add(item)
                mostrarResultados()
            }
            val exitosas = pendientes.filter { cod -> resultados.firstOrNull { it.codigo == cod }?.ok == true }
            withContext(Dispatchers.IO) {
                ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
            }

            setCargando(false, 0)
            actualizarResumen()
        }
    }

    private fun reintentarFallidas() {
        val fallidas = resultados.filter { !it.ok }.map { it.codigo }
        if (fallidas.isNotEmpty()) consultar(fallidas)
    }

    private fun actualizarResumen() {
        val ok      = resultados.count { it.ok }
        val fallidas = resultados.size - ok
        if (fallidas == 0) {
            showBanner(true, "✓  $ok caravana(s) ubicada(s)")
        } else {
            showBanner(false, "$ok ubicada(s)  ·  $fallidas sin datos")
        }
        binding.btnReintentar.visibility = if (fallidas > 0) View.VISIBLE else View.GONE
    }

    private fun showBanner(ok: Boolean, msg: String) {
        binding.tvBanner.visibility = View.VISIBLE
        binding.tvBanner.text = msg
        binding.tvBanner.setBackgroundResource(
            if (ok) R.drawable.bg_resultado_ok else R.drawable.bg_resultado_error
        )
        binding.tvBanner.setTextColor(
            requireContext().getColor(if (ok) R.color.verde_ok else R.color.rojo_error)
        )
    }

    private fun setCargando(on: Boolean, total: Int) {
        binding.panelProgreso.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            binding.btnReintentar.visibility = View.GONE
            binding.tvProgreso.text = "Consultando 1 de $total…"
        }
    }

    private fun mostrarResultados() {
        if (binding.recyclerResultados.adapter == null) {
            binding.recyclerResultados.layoutManager = LinearLayoutManager(requireContext())
            binding.recyclerResultados.adapter = crearAdapter()
        }
        @Suppress("UNCHECKED_CAST")
        (binding.recyclerResultados.adapter as ListAdapter<ResultItem, RecyclerView.ViewHolder>)
            .submitList(resultados.toList())

        actualizarContadores()
    }

    /**
     * Entidades (RENSPA/feria) distintas y desglose de sexo entre las caravanas
     * ya consultadas con éxito. Se recalcula con cada resultado que llega, igual
     * que en la consulta masiva de la web.
     */
    @SuppressLint("SetTextI18n")
    private fun actualizarContadores() {
        val exitosas = resultados.filter { it.ok }

        val entidades = exitosas.map { it.renspa.trim() }.filter { it.isNotEmpty() }.toSet()

        var machos = 0; var hembras = 0; var sinSexo = 0
        exitosas.forEach {
            val s = it.sexo.trim().uppercase()
            when {
                s == "M" || s == "MACHO"  -> machos++
                s == "H" || s == "HEMBRA" -> hembras++
                else                      -> sinSexo++
            }
        }

        binding.tvEntidadesCount.text = "Entidades (RENSPA/feria)\n${entidades.size}"
        binding.tvSexoCount.text = "Macho $machos   Hembra $hembras   s/sexo $sinSexo"
        binding.panelContadores.visibility = if (resultados.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun crearAdapter() = object : ListAdapter<ResultItem, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<ResultItem>() {
            override fun areItemsTheSame(a: ResultItem, b: ResultItem) = a.codigo == b.codigo
            override fun areContentsTheSame(a: ResultItem, b: ResultItem) = a == b
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ubicacion_caravana, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            holder.itemView.apply {
                setBackgroundResource(
                    if (item.ok) R.drawable.bg_caravana_ok else R.drawable.bg_caravana_dup
                )
                findViewById<TextView>(R.id.tvCodigo).text = item.codigo
                findViewById<TextView>(R.id.tvRenspa).text =
                    if (item.ok) item.renspa.ifBlank { "sin datos" }
                    else item.error.ifBlank { "sin datos" }
                findViewById<TextView>(R.id.tvEstablecimiento).text =
                    if (item.ok) item.establecimiento.ifBlank { "sin datos" } else "—"
                findViewById<TextView>(R.id.tvTitular).text =
                    if (item.ok) item.titular.ifBlank { "sin datos" } else "—"
                findViewById<TextView>(R.id.tvSexo).text = if (item.ok) item.sexo.ifBlank { "-" } else "-"
                findViewById<TextView>(R.id.tvRaza).text = if (item.ok) item.raza.ifBlank { "-" } else "-"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
