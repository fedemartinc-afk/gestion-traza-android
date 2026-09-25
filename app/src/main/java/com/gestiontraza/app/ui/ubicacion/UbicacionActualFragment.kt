package com.gestiontraza.app.ui.ubicacion

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import com.gestiontraza.app.data.ConsultaConReintentos
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentUbicacionActualBinding
import com.gestiontraza.app.ui.estado.compartirXlsx
import com.gestiontraza.app.ui.estado.mostrarDialogoAgregarCaravanas
import com.gestiontraza.app.ui.estado.mostrarSelectorValidez
import com.gestiontraza.app.ui.estado.textoBotonFiltroValidez
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
    private var caravanas: MutableList<String> = mutableListOf()

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

    /** Filtro múltiple por entidad (RENSPA/feria): null = sin filtrar, se ve todo. */
    private var filtroRenspa: Set<String>? = null

    /** null = todas, true = solo con datos, false = solo sin datos. */
    private var filtroValidez: Boolean? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentUbicacionActualBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        binding.tvContadorBar.text = "${caravanas.size} caravanas"

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnReintentar.setOnClickListener { reintentarFallidas() }
        binding.tvEntidadesCount.setOnClickListener { mostrarSelectorEntidades() }
        binding.btnCompartirEstado.setOnClickListener { compartirResultado() }
        binding.btnFiltroValidez.setOnClickListener {
            val validas = resultados.count { it.ok }
            mostrarSelectorValidez(filtroValidez, validas, resultados.size - validas) {
                filtroValidez = it
                mostrarResultados()
            }
        }
        binding.btnAgregarCaravana.setOnClickListener {
            mostrarDialogoAgregarCaravanas({ caravanas }) { nuevas ->
                caravanas.addAll(nuevas)
                binding.tvContadorBar.text = "${caravanas.size} caravanas"
                consultar(nuevas)
            }
        }

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
            ConsultaConReintentos.consultar(
                pendientes,
                onProgreso = { actual, total, pasada ->
                    binding.tvProgreso.text = if (pasada == 1)
                        "Consultando $actual de $total…"
                    else
                        "Consultando $actual de $total… (reintento ${pasada - 1} de ${ConsultaConReintentos.MAX_REINTENTOS})"
                },
                // Una respuesta "ok" sin RENSPA también se reintenta: SENASA a veces la
                // devuelve incompleta.
                esValido = { it.ok && it.renspaActual.isNotBlank() }
            ) { caravana ->
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
                estado
            }
            val exitosas = pendientes.filter { cod -> resultados.firstOrNull { it.codigo == cod }?.ok == true }
            withContext(Dispatchers.IO) {
                ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
            }

            setCargando(false, 0)
            actualizarResumen()
        }
    }

    private fun eliminar(codigo: String) {
        caravanas.remove(codigo)
        resultados.removeAll { it.codigo == codigo }
        binding.tvContadorBar.text = "${caravanas.size} caravanas"
        // Si la entidad filtrada se quedó sin caravanas, se saca del filtro para no dejar la lista vacía.
        val entidades = resultados.filter { it.ok }.map { it.renspa.trim() }.toSet()
        filtroRenspa = filtroRenspa?.intersect(entidades)?.takeIf { it.isNotEmpty() }
        mostrarResultados()
        if (resultados.isNotEmpty()) actualizarResumen() else binding.tvBanner.visibility = View.GONE
    }

    private fun reintentarFallidas() {
        val fallidas = resultados.filter { !it.ok || it.renspa.isBlank() }.map { it.codigo }
        if (fallidas.isNotEmpty()) consultar(fallidas)
    }

    private fun actualizarResumen() {
        val ok      = resultados.count { it.ok && it.renspa.isNotBlank() }
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
        // El filtro por entidad solo afecta lo que se ve en la lista: los contadores
        // y el resumen siguen contando sobre el total, sin filtrar.
        val filtro = filtroRenspa
        val validez = filtroValidez
        // Las que no tienen datos van primero; entre sí conservan su orden.
        val visibles = resultados
            .filter { (filtro == null || (it.ok && filtro.contains(it.renspa.trim()))) &&
                (validez == null || it.ok == validez) }
            .sortedBy { if (it.ok) 1 else 0 }

        @Suppress("UNCHECKED_CAST")
        (binding.recyclerResultados.adapter as ListAdapter<ResultItem, RecyclerView.ViewHolder>)
            .submitList(visibles)

        actualizarContadores()
    }

    /** Entidades (RENSPA/feria) distintas entre las caravanas ya ubicadas, con
     *  cuántas caravanas tiene cada una — para el selector múltiple. */
    private fun entidadesDisponibles(): List<Triple<String, String, Int>> {
        val porRenspa = resultados.filter { it.ok }
            .groupBy { it.renspa.trim() }
            .filterKeys { it.isNotEmpty() }
        return porRenspa.entries
            .map { (renspa, items) -> Triple(renspa, items.first().establecimiento.ifBlank { "(sin nombre)" }, items.size) }
            .sortedBy { it.second.lowercase() }
    }

    /** Filtro múltiple: se puede elegir una o varias entidades para que la lista
     *  muestre solo las caravanas de esas entidades. */
    private fun mostrarSelectorEntidades() {
        val entidades = entidadesDisponibles()
        if (entidades.isEmpty()) {
            showToast("Todavía no hay entidades para filtrar")
            return
        }
        val etiquetas = entidades.map { (renspa, nombre, cantidad) -> "$nombre · $renspa ($cantidad)" }.toTypedArray()
        val seleccion = BooleanArray(entidades.size) { i ->
            filtroRenspa?.contains(entidades[i].first) ?: true
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Filtrar por RENSPA/Remate feria")
            .setMultiChoiceItems(etiquetas, seleccion) { _, which, checked -> seleccion[which] = checked }
            .setPositiveButton("Aplicar") { _, _ ->
                val elegidas = entidades.filterIndexed { i, _ -> seleccion[i] }.map { it.first }.toSet()
                // Si quedaron todas marcadas, es lo mismo que no filtrar.
                filtroRenspa = if (elegidas.size == entidades.size) null else elegidas
                mostrarResultados()
            }
            .setNeutralButton("Ver todas") { _, _ ->
                filtroRenspa = null
                mostrarResultados()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Comparte el resultado como .xlsx. Si hay un filtro activo, pregunta si se
     *  comparte todo o solo lo que está filtrado; si no, comparte todo directo. */
    private fun compartirResultado() {
        if (resultados.isEmpty()) {
            showToast("No hay resultados para compartir")
            return
        }
        val filtro = filtroRenspa
        if (filtro == null) {
            exportarXlsx(resultados)
            return
        }
        val filtrados = resultados.filter { it.ok && filtro.contains(it.renspa.trim()) }
        AlertDialog.Builder(requireContext())
            .setTitle("¿Qué querés compartir?")
            .setItems(arrayOf("Todas (${resultados.size})", "Solo filtradas (${filtrados.size})")) { _, which ->
                exportarXlsx(if (which == 1) filtrados else resultados)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportarXlsx(items: List<ResultItem>) {
        val encabezados = listOf("Caravana", "RENSPA", "Establecimiento", "Titular", "Sexo", "Raza", "Estado")
        val filas = items.map { item ->
            listOf(
                item.codigo,
                if (item.ok) item.renspa else "",
                if (item.ok) item.establecimiento else "",
                if (item.ok) item.titular else "",
                if (item.ok) item.sexo else "",
                if (item.ok) item.raza else "",
                if (item.ok) "OK" else "SIN DATOS" + if (item.error.isNotBlank()) ": ${item.error}" else ""
            )
        }
        compartirXlsx("Ubicación actual", encabezados, filas)
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

        val filtro = filtroRenspa
        binding.tvEntidadesCount.text = if (filtro == null)
            "RENSPA/REMATE FERIA ▾\n${entidades.size}"
        else
            "RENSPA/REMATE FERIA ▾\n${filtro.size} de ${entidades.size}"
        binding.tvSexoCount.text = "Macho $machos   Hembra $hembras   s/sexo $sinSexo"
        binding.panelContadores.visibility = if (resultados.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnCompartirEstado.visibility = if (resultados.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnAgregarCaravana.visibility = if (resultados.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnFiltroValidez.visibility = if (resultados.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnFiltroValidez.text = textoBotonFiltroValidez(filtroValidez)
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
                findViewById<TextView>(R.id.btnEliminar).setOnClickListener { eliminar(item.codigo) }
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

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
