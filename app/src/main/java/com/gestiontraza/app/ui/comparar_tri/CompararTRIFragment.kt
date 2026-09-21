package com.gestiontraza.app.ui.comparar_tri

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentCompararTriBinding
import com.gestiontraza.app.ui.send.BarcodeScanActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class CompararTRIFragment : Fragment() {

    private var _binding: FragmentCompararTriBinding? = null
    private val binding get() = _binding!!
    private val args: CompararTRIFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    enum class Tipo { VERDE, ROJO, AZUL }

    data class ResultItem(val codigo: String, val tipo: Tipo)

    private data class DatosCaravana(val renspa: String, val sexo: String, val ok: Boolean)
    private val datosParaContadores = mutableListOf<DatosCaravana>()

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) fetchLocation() }

    private val barcodeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        val soloDigitos = code.filter { it.isDigit() }.take(9)
        binding.etDte.setText(if (soloDigitos.isNotEmpty()) soloDigitos else code)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentCompararTriBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        binding.tvContadorBar.text = "${caravanas.size} leídas"

        if (args.dte.isNotBlank()) binding.etDte.setText(args.dte)

        fetchLocation()

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.btnEscanearDte.setOnClickListener {
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }

        setupTecladoToggleDte()

        binding.btnEnviarWeb.setOnClickListener { confirmarYEnviarAWeb(dteActual()) }
        binding.btnEnviarWebPost.setOnClickListener { confirmarYEnviarAWeb(dteActual()) }

        binding.btnComparar.setOnClickListener {
            val dte = dteActual()
            if (dte.isEmpty()) { showToast("Ingresá el número de DT-e"); return@setOnClickListener }
            compararConTRI(dte)
        }

        binding.btnEnviarCierre.setOnClickListener {
            val dte = dteActual()
            if (dte.isEmpty()) { showToast("DT-e requerido para cierre"); return@setOnClickListener }
            enviarCierre(dte)
        }
    }

    private fun dteActual(): String = binding.etDte.text?.toString()?.trim() ?: ""

    /** Si no se cargó el DTe, se avisa antes de mandar el mensaje a la web —
     *  igual criterio que Verificar Origen y Ordenar por Origen. */
    private fun confirmarYEnviarAWeb(dte: String) {
        if (dte.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sin número de DTe")
                .setMessage("No completaste el DTe. ¿Continuar de todas formas?")
                .setPositiveButton("Sí, continuar") { _, _ -> enviarAWeb(dte) }
                .setNegativeButton("No, volver", null)
                .show()
        } else {
            enviarAWeb(dte)
        }
    }

    /** Teclado numérico por defecto para DTe (son números), con botón para
     *  pasar a QWERTY si hace falta cargar un código alfanumérico. */
    private var tecladoDteQwerty = false
    private fun setupTecladoToggleDte() {
        val et = binding.etDte
        binding.btnToggleTecladoDte.setOnClickListener {
            tecladoDteQwerty = !tecladoDteQwerty
            et.inputType = if (tecladoDteQwerty)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            binding.btnToggleTecladoDte.text = if (tecladoDteQwerty) "123" else "ABC"
            et.setSelection(et.text?.length ?: 0)
            et.post {
                et.requestFocus()
                val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun compararConTRI(dte: String) {
        setLoading(true)
        lifecycleScope.launch {
            val (dteOk, dteInfo) = withContext(Dispatchers.IO) {
                SenasaClient.consultarDte(
                    SenasaClient.senasaBase(session.senasaEnv),
                    session.wsUsername,
                    session.wsToken,
                    dte
                )
            }
            setLoading(false)

            if (!dteOk || dteInfo == null) {
                showToast("No se pudo consultar el DT-e")
                return@launch
            }

            if (!dteInfo.tieneTRI && dteInfo.caravanas.isEmpty()) {
                showBanner(false, "Este movimiento no tiene TRI o no tiene caravanas registradas")
                return@launch
            }

            val caravanasTRI  = dteInfo.caravanas
            val escaneadasSet = caravanas.map { it.uppercase().replace(" ", "") }.toSet()
            val triSet        = caravanasTRI.map { it.uppercase().replace(" ", "") }.toSet()

            val resultados = mutableListOf<ResultItem>()

            // 1° verdes: en TRI y escaneadas
            for (cod in caravanasTRI) {
                if (escaneadasSet.contains(cod.uppercase().replace(" ", ""))) {
                    resultados.add(ResultItem(cod, Tipo.VERDE))
                }
            }

            // 2° azules: escaneadas pero no en TRI
            for (cod in caravanas) {
                if (!triSet.contains(cod.uppercase().replace(" ", ""))) {
                    resultados.add(ResultItem(cod, Tipo.AZUL))
                }
            }

            // 3° rojas: en TRI pero no escaneadas
            for (cod in caravanasTRI) {
                if (!escaneadasSet.contains(cod.uppercase().replace(" ", ""))) {
                    resultados.add(ResultItem(cod, Tipo.ROJO))
                }
            }

            val verdes = resultados.count { it.tipo == Tipo.VERDE }
            val rojos  = resultados.count { it.tipo == Tipo.ROJO  }
            val azules = resultados.count { it.tipo == Tipo.AZUL  }

            val bannerOk = rojos == 0 && azules == 0
            // El número de TRI encabeza el resultado; SENASA no siempre lo informa.
            val encabezado = if (dteInfo.nroTri.isNotBlank()) "TRI N° ${dteInfo.nroTri}\n" else ""
            val bannerMsg = encabezado + if (bannerOk)
                "✓  Todas las caravanas coinciden (${caravanasTRI.size} en TRI)"
            else
                "En TRI: ${caravanasTRI.size}  ·  Leídas: ${caravanas.size}\n" +
                "✓ $verdes coinciden   ✗ $rojos no llegaron   + $azules nuevas"

            showBanner(bannerOk, bannerMsg)
            mostrarResultados(resultados)
            binding.panelCierre.visibility = View.VISIBLE

            // Entidad y sexo no vienen en la comparación contra el TRI (esa es solo
            // local, contra la lista de códigos) — para los contadores hace falta
            // una consulta a SENASA por cada caravana escaneada, igual que en los
            // otros módulos. Se hace después de mostrar el resultado de la
            // comparación para no demorar lo principal de la pantalla.
            consultarDatosParaContadores()
        }
    }

    /** Consulta el detalle de cada caravana escaneada (no las que faltaron del TRI) para poder contar entidades y sexo. */
    private suspend fun consultarDatosParaContadores() {
        datosParaContadores.clear()
        binding.panelContadores.visibility = View.GONE
        val base = SenasaClient.senasaBase(session.senasaEnv)
        binding.tvProgreso.visibility = View.VISIBLE
        caravanas.forEachIndexed { idx, caravana ->
            binding.tvProgreso.text = "Consultando datos ${idx + 1} de ${caravanas.size}…"
            val estado = withContext(Dispatchers.IO) {
                SenasaClient.consultarCaravana(base, session.wsUsername, session.wsToken, caravana)
            }
            datosParaContadores.add(DatosCaravana(estado.renspaActual, estado.sexo, estado.ok))
        }
        binding.tvProgreso.visibility = View.GONE
        actualizarContadores()

        val exitosas = caravanas.zip(datosParaContadores).filter { it.second.ok }.map { it.first }
        withContext(Dispatchers.IO) {
            ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
        }
    }

    /** Entidades (RENSPA/feria) distintas y desglose de sexo, sobre las caravanas con datos de SENASA. */
    @SuppressLint("SetTextI18n")
    private fun actualizarContadores() {
        val conDatos = datosParaContadores.filter { it.ok }
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
        binding.panelContadores.visibility = if (datosParaContadores.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun mostrarResultados(resultados: List<ResultItem>) {
        val adapter = object : ListAdapter<ResultItem, RecyclerView.ViewHolder>(
            object : DiffUtil.ItemCallback<ResultItem>() {
                override fun areItemsTheSame(a: ResultItem, b: ResultItem) = a.codigo == b.codigo
                override fun areContentsTheSame(a: ResultItem, b: ResultItem) = a == b
            }
        ) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_comparar_resultado, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            @SuppressLint("SetTextI18n")
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = getItem(position)
                val ctx  = holder.itemView.context
                val icono = holder.itemView.findViewById<TextView>(R.id.tvIcono)
                holder.itemView.apply {
                    when (item.tipo) {
                        Tipo.VERDE -> {
                            setBackgroundResource(R.drawable.bg_caravana_ok)
                            icono.text = "✓"
                            icono.setTextColor(ctx.getColor(R.color.verde_ok))
                        }
                        Tipo.ROJO -> {
                            setBackgroundResource(R.drawable.bg_caravana_dup)
                            icono.text = "✗"
                            icono.setTextColor(ctx.getColor(R.color.rojo_error))
                        }
                        Tipo.AZUL -> {
                            setBackgroundResource(R.drawable.bg_caravana_new)
                            icono.text = "+"
                            icono.setTextColor(Color.parseColor("#2272c3"))
                        }
                    }
                    findViewById<TextView>(R.id.tvCodigo).text = item.codigo
                }
            }
        }
        binding.recyclerResultados.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerResultados.adapter = adapter
        adapter.submitList(resultados)
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

    private fun enviarAWeb(dte: String) {
        setResultLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.enviarAWeb(
                    baseUrl   = session.baseUrl(),
                    token     = session.token,
                    dte       = dte,
                    caravanas = caravanas,
                    tipo      = "verificacion",
                    extra     = mapOf("titulo" to "Comparar vs TRI")
                )
            }
            setResultLoading(false)
            showResultado(result.ok, result.message)
        }
    }

    // Envía verdes + azules = todas las caravanas escaneadas (las que físicamente llegaron)
    private fun enviarCierre(dte: String) {
        if (session.wsUsername.isEmpty()) { showToast("Sin credenciales SENASA"); return }
        if (lastLat == null || lastLon == null) { showToast("Falta la ubicación — esperá a que se obtenga el GPS antes de enviar"); return }
        setResultLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.enviarCierre(
                    baseUrl    = session.baseUrl(),
                    token      = session.token,
                    wsUsername = session.wsUsername,
                    wsToken    = session.wsToken,
                    dte        = dte,
                    caravanas  = caravanas,
                    lat        = lastLat,
                    lon        = lastLon,
                    senasaEnv  = session.senasaEnv
                )
            }
            setResultLoading(false)
            showResultado(result.ok, result.message)
        }
    }

    private fun fetchLocation() {
        val perm = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
            LocationServices.getFusedLocationProviderClient(requireActivity())
                .lastLocation
                .addOnSuccessListener { loc: Location? ->
                    lastLat = loc?.latitude
                    lastLon = loc?.longitude
                }
        } else {
            locationPermLauncher.launch(perm)
        }
    }

    private fun setLoading(on: Boolean) {
        binding.progressVerif.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnComparar.isEnabled  = !on
        binding.btnEnviarWeb.isEnabled = !on
    }

    private fun setResultLoading(on: Boolean) {
        binding.progressSend.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnEnviarCierre.isEnabled  = !on
        binding.btnEnviarWebPost.isEnabled = !on
        binding.btnEnviarWeb.isEnabled     = !on
    }

    private fun showResultado(ok: Boolean, msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = if (ok) "✓ $msg" else "✗ $msg"
        binding.tvResultado.setTextColor(
            requireContext().getColor(if (ok) R.color.verde_ok else R.color.rojo_error)
        )
        binding.tvResultado.setBackgroundResource(
            if (ok) R.drawable.bg_resultado_ok else R.drawable.bg_resultado_error
        )
        if (ok) binding.root.postDelayed({
            if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
        }, 2500)
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
