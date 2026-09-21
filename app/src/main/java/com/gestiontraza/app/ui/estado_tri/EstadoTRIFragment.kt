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
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentEstadoTriBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class EstadoTRIFragment : Fragment() {

    private var _binding: FragmentEstadoTriBinding? = null
    private val binding get() = _binding!!
    private val args: EstadoTRIFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>

    data class ResultItem(
        val codigo: String,
        val ok: Boolean,
        val razon: String,
        val renspa: String = "",
        val sexo: String = "",
        /** Si SENASA devolvió datos para esta caravana (independiente de si cumple los requisitos) */
        val datosOk: Boolean = false
    )

    private var resultados: List<ResultItem> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentEstadoTriBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())
        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        binding.tvContadorBar.text = "${caravanas.size} caravanas"

        if (session.ultimoRenspa.isNotBlank()) binding.etRenspa.setText(session.ultimoRenspa)

        mostrarResultados(emptyList())

        binding.btnVolver.setOnClickListener { findNavController().navigateUp() }
        binding.btnVerificar.setOnClickListener { verificarEstado() }
        binding.btnGps.setOnClickListener { obtenerUbicacion() }
        binding.btnEnviarTRI.setOnClickListener { enviarTRIaSenasa() }
        binding.btnEnviarWeb.setOnClickListener { confirmarYEnviarAWeb() }
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
            val items = mutableListOf<ResultItem>()
            caravanas.forEachIndexed { idx, caravana ->
                withContext(Dispatchers.Main) {
                    binding.tvProgreso.text = "Verificando ${idx + 1} de ${caravanas.size}..."
                }
                val estado = withContext(Dispatchers.IO) {
                    SenasaClient.consultarCaravana(SenasaClient.senasaBase(session.senasaEnv), session.wsUsername, session.wsToken, caravana)
                }
                if (!estado.ok) {
                    items.add(ResultItem(caravana, false, estado.error.ifBlank { "Sin datos" }, datosOk = false))
                    return@forEachIndexed
                }
                val problemas = buildList {
                    if (estado.bloqueada)   add("Bloqueada")
                    if (estado.deBaja)      add("De baja")
                    if (estado.reemplazada) add("Reemplazada")
                    if (estado.renspaActual.isNotBlank() &&
                        normalizarRenspa(estado.renspaActual) != normalizarRenspa(renspa)) {
                        add("RENSPA no coincide (es ${estado.renspaActual})")
                    }
                }
                items.add(ResultItem(
                    codigo  = caravana,
                    ok      = problemas.isEmpty(),
                    razon   = problemas.joinToString(", "),
                    renspa  = estado.renspaActual,
                    sexo    = estado.sexo,
                    datosOk = true
                ))
            }
            val exitosas = items.filter { it.datosOk }.map { it.codigo }
            withContext(Dispatchers.IO) {
                ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
            }

            resultados = items
            setVerifLoading(false)
            mostrarResultados(items)
            if (items.isNotEmpty() && items.all { it.ok }) {
                binding.panelEnviar.visibility = View.VISIBLE
            }
        }
    }

    private fun normalizarRenspa(r: String) = r.filter { it.isLetterOrDigit() }.uppercase()

    private fun enviarTRIaSenasa() {
        val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
        val numero = binding.etNumeroLote.text?.toString()?.trim()?.ifBlank { "LOTE :" } ?: "LOTE :"
        val lat = binding.etLat.text?.toString()?.trim()?.toDoubleOrNull()
        val lon = binding.etLon.text?.toString()?.trim()?.toDoubleOrNull()
        val especie = caravanas.firstOrNull()?.let {
            if (it.startsWith("032") && it.length >= 5) it.substring(3, 5) else "01"
        } ?: "01"
        val caravanasSoloOk = resultados.filter { it.ok }.map { it.codigo }
        if (caravanasSoloOk.isEmpty()) { showToast("Sin caravanas válidas para enviar"); return }
        if (session.wsUsername.isEmpty()) { showToast("Sin credenciales SENASA"); return }
        if (lat == null || lon == null) { showToast("Falta la ubicación — presioná GPS antes de enviar"); return }
        setResultLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SenasaClient.enviarTRI(
                    SenasaClient.senasaBase(session.senasaEnv), session.wsUsername, session.wsToken,
                    renspa, especie, numero, caravanasSoloOk, lat, lon
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
        } else {
            showToast("No se pudo obtener la ubicación")
        }
    }

    private fun mostrarResultados(items: List<ResultItem>) {
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
                }
            }
        }
        binding.recyclerResultados.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerResultados.adapter = adapter
        adapter.submitList(items)

        actualizarContadores(items)
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
