package com.gestiontraza.app.ui.estado_tri

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
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
import com.gestiontraza.app.databinding.FragmentEstadoTriBinding
import com.gestiontraza.app.ui.estado.FilaEstadoCaravana
import com.gestiontraza.app.ui.estado.aplicarMascaraRenspa
import com.gestiontraza.app.ui.estado.compartirEstadoCaravanas
import com.gestiontraza.app.ui.estado.mostrarDialogoAgregarCaravanas
import com.gestiontraza.app.ui.estado.mostrarSelectorValidez
import com.gestiontraza.app.ui.estado.textoBotonFiltroValidez
import com.gestiontraza.app.ui.estado.textoValidezColoreado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class EstadoTRIFragment : Fragment() {

    private var _binding: FragmentEstadoTriBinding? = null
    private val binding get() = _binding!!
    private val args: EstadoTRIFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private var caravanas: MutableList<String> = mutableListOf()

    data class ResultItem(
        val codigo: String,
        val ok: Boolean,
        val razon: String,
        val renspa: String = "",
        val sexo: String = "",
        /** Si SENASA devolvió datos para esta caravana (independiente de si cumple los requisitos) */
        val datosOk: Boolean = false
    )

    private val resultados = mutableListOf<ResultItem>()

    /** null = todas, true = solo válidas, false = solo inválidas. */
    private var filtroValidez: Boolean? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentEstadoTriBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())
        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        binding.tvContadorBar.text = "${caravanas.size} caravanas"

        binding.etRenspa.aplicarMascaraRenspa()
        if (session.ultimoRenspa.isNotBlank()) binding.etRenspa.setText(session.ultimoRenspa)

        mostrarResultados(emptyList())

        binding.btnVolver.setOnClickListener { findNavController().navigateUp() }
        binding.btnVerificar.setOnClickListener { verificarEstado() }
        binding.btnGps.setOnClickListener { obtenerUbicacion() }
        binding.btnEnviarTRI.setOnClickListener { enviarTRIaSenasa() }
        binding.btnEnviarWeb.setOnClickListener { confirmarYEnviarAWeb() }
        binding.tvValidasBar.setOnClickListener { compartir(soloValidas = true) }
        binding.tvInvalidasBar.setOnClickListener { compartir(soloValidas = false) }
        binding.btnCompartirEstado.setOnClickListener { compartir(soloValidas = null) }
        binding.btnFiltroValidez.setOnClickListener {
            val validas = resultados.count { it.ok }
            mostrarSelectorValidez(filtroValidez, validas, resultados.size - validas) {
                filtroValidez = it
                mostrarResultados(resultados.toList())
            }
        }
        binding.btnAgregarCaravana.setOnClickListener {
            mostrarDialogoAgregarCaravanas({ caravanas }) { agregarYVerificar(it) }
        }
    }

    private fun compartir(soloValidas: Boolean?) {
        compartirEstadoCaravanas(
            "Estado para TRI",
            resultados.map { FilaEstadoCaravana(it.codigo, it.ok, it.razon) },
            soloValidas
        )
    }

    /** Si no se cargó el RENSPA, se avisa antes de mandar el mensaje a la web —
     *  igual criterio que Estado Predespacho. */
    private fun confirmarYEnviarAWeb() {
        val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
        if (renspa.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sin RENSPA")
                .setMessage("No completaste el RENSPA. ¿Continuar de todas formas?")
                .setPositiveButton("Sí, continuar") { _, _ -> enviarAWeb() }
                .setNegativeButton("No, volver", null)
                .show()
        } else {
            enviarAWeb()
        }
    }

    private fun verificarEstado() {
        val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
        if (renspa.length < 17) { showToast("Ingresá el RENSPA completo"); return }
        session.ultimoRenspa = renspa
        setVerifLoading(true)
        binding.panelEnviar.visibility = View.GONE

        lifecycleScope.launch {
            val items = consultarItems(caravanas.toList(), renspa)
            resultados.clear()
            resultados.addAll(items)
            setVerifLoading(false)
            mostrarResultados(resultados.toList())
            // Se muestra apenas hay alguna válida para enviar, no solo cuando son
            // todas — "Enviar TRI a SENASA" ya manda únicamente las que dieron OK.
            binding.panelEnviar.visibility = if (items.any { it.ok }) View.VISIBLE else View.GONE
        }
    }

    private suspend fun consultarItems(codigos: List<String>, renspa: String): List<ResultItem> {
        val base = SenasaClient.senasaBase(session.senasaEnv)
        val estados = ConsultaConReintentos.consultar(
            codigos,
            onProgreso = { actual, total, pasada ->
                binding.tvProgreso.text = if (pasada == 1)
                    "Verificando $actual de $total..."
                else
                    "Verificando $actual de $total... (reintento ${pasada - 1} de ${ConsultaConReintentos.MAX_REINTENTOS})"
            },
            esValido = { it.ok }
        ) { cod ->
            withContext(Dispatchers.IO) {
                SenasaClient.consultarCaravana(base, session.wsUsername, session.wsToken, cod)
            }
        }

        val items = codigos.map { caravana ->
            val estado = estados.getValue(caravana)
            if (!estado.ok) {
                ResultItem(caravana, false, estado.error.ifBlank { "Sin datos" }, datosOk = false)
            } else {
                val problemas = buildList {
                    if (estado.bloqueada)   add("Bloqueada")
                    if (estado.deBaja)      add("De baja")
                    if (estado.reemplazada) add("Reemplazada")
                    if (estado.renspaActual.isNotBlank() &&
                        normalizarRenspa(estado.renspaActual) != normalizarRenspa(renspa)) {
                        add("RENSPA no coincide (es ${estado.renspaActual})")
                    }
                }
                ResultItem(
                    codigo  = caravana,
                    ok      = problemas.isEmpty(),
                    razon   = problemas.joinToString(", "),
                    renspa  = estado.renspaActual,
                    sexo    = estado.sexo,
                    datosOk = true
                )
            }
        }
        val exitosas = items.filter { it.datosOk }.map { it.codigo }
        withContext(Dispatchers.IO) {
            ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
        }
        return items
    }

    /** Suma caravanas nuevas a la lista ya verificada y consulta solo esas. */
    private fun agregarYVerificar(nuevas: List<String>) {
        val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
        if (renspa.length < 17) { showToast("Ingresá el RENSPA completo"); return }
        setVerifLoading(true)
        lifecycleScope.launch {
            val items = consultarItems(nuevas, renspa)
            caravanas.addAll(nuevas)
            resultados.addAll(items)
            setVerifLoading(false)
            mostrarResultados(resultados.toList())
            actualizarPanelEnviar()
        }
    }

    private fun actualizarPanelEnviar() {
        binding.panelEnviar.visibility = if (resultados.any { it.ok }) View.VISIBLE else View.GONE
    }

    private fun normalizarRenspa(r: String) = r.filter { it.isLetterOrDigit() }.uppercase()

    private fun enviarTRIaSenasa() {
        val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
        val numero = "LOTE : " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        val lat = binding.etLat.text?.toString()?.trim()?.toDoubleOrNull()
        val lon = binding.etLon.text?.toString()?.trim()?.toDoubleOrNull()
        val especie = caravanas.firstOrNull()?.let {
            if (it.startsWith("032") && it.length >= 5) it.substring(3, 5) else "01"
        } ?: "01"
        val caravanasSoloOk = resultados.filter { it.ok }.map { it.codigo }
        val invalidas = resultados.size - caravanasSoloOk.size
        if (caravanasSoloOk.isEmpty()) { showToast("Sin caravanas válidas para enviar"); return }
        if (session.wsUsername.isEmpty()) { showToast("Sin credenciales SENASA"); return }
        if (lat == null || lon == null) { showToast("Falta la ubicación — presioná GPS antes de enviar"); return }

        if (invalidas > 0) {
            AlertDialog.Builder(requireContext())
                .setTitle("Caravanas inválidas")
                .setMessage("Hay $invalidas caravana(s) inválida(s). ¿Querés enviar el lote completo de todas formas?")
                .setPositiveButton("Sí, enviar todo") { _, _ ->
                    hacerEnvioTRI(renspa, numero, especie, resultados.map { it.codigo }, lat, lon)
                }
                .setNegativeButton("No, solo válidas") { _, _ ->
                    showToast("Se enviarán solo las ${caravanasSoloOk.size} caravana(s) válida(s)")
                    hacerEnvioTRI(renspa, numero, especie, caravanasSoloOk, lat, lon)
                }
                .show()
        } else {
            hacerEnvioTRI(renspa, numero, especie, caravanasSoloOk, lat, lon)
        }
    }

    private fun hacerEnvioTRI(renspa: String, numero: String, especie: String, codigos: List<String>, lat: Double, lon: Double) {
        setResultLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SenasaClient.enviarTRI(
                    SenasaClient.senasaBase(session.senasaEnv), session.wsUsername, session.wsToken,
                    renspa, especie, numero, codigos, lat, lon
                )
            }
            setResultLoading(false)
            showResultado(result.ok, result.message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun obtenerUbicacion() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }
        val lm = requireContext().getSystemService(LocationManager::class.java)
        val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            binding.etLat.setText(loc.latitude.toString())
            binding.etLon.setText(loc.longitude.toString())
            binding.btnGps.text = "📍  GPS ✓"
        } else {
            showToast("No se pudo obtener la ubicación")
        }
    }

    private fun mostrarResultados(itemsSinOrdenar: List<ResultItem>) {
        // Las que no cumplen los requisitos van primero, para que se vean sin
        // tener que desplazarse; entre sí conservan el orden en que llegaron.
        val items = itemsSinOrdenar.sortedBy { if (it.ok) 1 else 0 }
        val visibles = when (filtroValidez) {
            null -> items
            true -> items.filter { it.ok }
            false -> items.filter { !it.ok }
        }
        val adapter = object : ListAdapter<ResultItem, RecyclerView.ViewHolder>(
            object : DiffUtil.ItemCallback<ResultItem>() {
                override fun areItemsTheSame(a: ResultItem, b: ResultItem) = a.codigo == b.codigo
                override fun areContentsTheSame(a: ResultItem, b: ResultItem) = a == b
            }
        ) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_caravana_estado, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            @SuppressLint("SetTextI18n")
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = getItem(position)
                val ctx = holder.itemView.context
                holder.itemView.apply {
                    setBackgroundResource(if (item.ok) R.drawable.bg_caravana_ok else R.drawable.bg_caravana_dup)
                    findViewById<TextView>(R.id.tvIcono).text = if (item.ok) "✓" else "✗"
                    findViewById<TextView>(R.id.tvIcono).setTextColor(
                        ctx.getColor(if (item.ok) R.color.verde_ok else R.color.rojo_error)
                    )
                    findViewById<TextView>(R.id.tvCodigo).text = item.codigo
                    val tvDetalle = findViewById<TextView>(R.id.tvDetalle)
                    if (!item.ok && item.razon.isNotBlank()) {
                        tvDetalle.visibility = View.VISIBLE
                        tvDetalle.text = item.razon
                        tvDetalle.setTextColor(ctx.getColor(R.color.rojo_error))
                    } else {
                        tvDetalle.visibility = View.GONE
                    }
                    val btnEliminar = findViewById<TextView>(R.id.btnEliminar)
                    btnEliminar.visibility = View.VISIBLE
                    btnEliminar.setOnClickListener {
                        caravanas.remove(item.codigo)
                        resultados.removeAll { it.codigo == item.codigo }
                        mostrarResultados(resultados.toList())
                        actualizarPanelEnviar()
                    }
                }
            }
        }
        binding.recyclerResultados.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerResultados.adapter = adapter
        adapter.submitList(visibles)

        actualizarContadores(items)
        actualizarContadoresValidez(items)
    }

    /** Contadores de válidas/inválidas en la barra superior, en lugar del total simple.
     *  Tocar cada uno comparte directamente ese grupo. */
    @SuppressLint("SetTextI18n")
    private fun actualizarContadoresValidez(items: List<ResultItem>) {
        if (items.isEmpty()) {
            binding.llContadoresBar.visibility = View.GONE
            binding.tvContadorBar.visibility = View.VISIBLE
            binding.btnCompartirEstado.visibility = View.GONE
            binding.btnAgregarCaravana.visibility = View.GONE
            binding.btnFiltroValidez.visibility = View.GONE
            binding.tvContadorBar.text = "${caravanas.size} caravanas"
            return
        }
        val validas = items.count { it.ok }
        val invalidas = items.size - validas
        binding.tvContadorBar.visibility = View.GONE
        binding.llContadoresBar.visibility = View.VISIBLE
        binding.tvValidasBar.text = "✓ $validas"
        binding.tvInvalidasBar.text = "✗ $invalidas"
        binding.btnCompartirEstado.visibility = View.VISIBLE
        binding.btnAgregarCaravana.visibility = View.VISIBLE
        binding.btnFiltroValidez.visibility = View.VISIBLE
        binding.btnFiltroValidez.text = textoBotonFiltroValidez(filtroValidez)
        binding.tvValidezCount.text = textoValidezColoreado(requireContext(), validas, invalidas)
    }

    /** Entidades (RENSPA/feria) distintas y desglose de sexo, sobre las caravanas con datos de SENASA. */
    @SuppressLint("SetTextI18n")
    private fun actualizarContadores(items: List<ResultItem>) {
        val conDatos = items.filter { it.datosOk }
        val entidades = conDatos.map { it.renspa.trim() }.filter { it.isNotEmpty() }.toSet()

        var machos = 0; var hembras = 0; var sinSexo = 0
        conDatos.forEach {
            val s = it.sexo.trim().uppercase()
            when {
                s == "M" || s == "MACHO"  -> machos++
                s == "H" || s == "HEMBRA" -> hembras++
                else                      -> sinSexo++
            }
        }

        binding.tvEntidadesCount.text = "Entidades (RENSPA/feria)\n${entidades.size}"
        binding.tvSexoCount.text = "Macho $machos   Hembra $hembras   s/sexo $sinSexo"
        binding.panelContadores.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun enviarAWeb() {
        setResultLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.enviarAWeb(
                    session.baseUrl(), session.token, "", caravanas,
                    "verificacion", mapOf("titulo" to "Verificar estado para TRI")
                )
            }
            setResultLoading(false)
            showResultado(result.ok, result.message)
        }
    }

    private fun setVerifLoading(on: Boolean) {
        binding.progressVerif.visibility = if (on) View.VISIBLE else View.GONE
        binding.tvProgreso.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnVerificar.isEnabled = !on
    }

    private fun setResultLoading(on: Boolean) {
        binding.progressSend.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnEnviarTRI.isEnabled = !on
        binding.btnEnviarWeb.isEnabled = !on
    }

    private fun showResultado(ok: Boolean, msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = if (ok) "✓ $msg" else "✗ $msg"
        binding.tvResultado.setTextColor(requireContext().getColor(if (ok) R.color.verde_ok else R.color.rojo_error))
        binding.tvResultado.setBackgroundResource(if (ok) R.drawable.bg_resultado_ok else R.drawable.bg_resultado_error)
        if (ok) binding.root.postDelayed({
            if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
        }, 2500)
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
