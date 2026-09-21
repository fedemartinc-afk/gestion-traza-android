package com.gestiontraza.app.ui.verificar_origen

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.os.Bundle
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentVerificarOrigenBinding
import com.gestiontraza.app.ui.send.BarcodeScanActivity
import com.gestiontraza.app.ui.send.confirmarEnvioSinVerificar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class VerificarOrigenFragment : Fragment() {

    private var _binding: FragmentVerificarOrigenBinding? = null
    private val binding get() = _binding!!
    private val args: VerificarOrigenFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>
    // Cuántas caravanas no coincidieron en la última verificación (para avisar al enviar).
    private var sinCoincidirUltima = 0

    private data class ResultItem(
        val codigo: String,
        val coincide: Boolean,
        val renspaActual: String,
        val renspaOrigen: String,
        val sexo: String,
        /** Si SENASA devolvió datos para esta caravana (independiente de si coincide el origen) */
        val datosOk: Boolean
    )

    private val barcodeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        val soloDigitos = code.filter { it.isDigit() }.take(9)
        binding.etDte.setText(if (soloDigitos.isNotEmpty()) soloDigitos else code)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentVerificarOrigenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: android.view.View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())
        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        binding.tvContadorBar.text = "${caravanas.size} caravanas"
        binding.btnVolver.setOnClickListener { findNavController().navigateUp() }

        binding.btnEscanearDte.setOnClickListener {
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }

        setupTecladoToggleDte()

        binding.btnEnviarWeb.setOnClickListener {
            val dte = binding.etDte.text?.toString()?.trim() ?: ""
            confirmarYEnviarAWeb(dte)
        }

        binding.btnVerificar.setOnClickListener {
            val dte = binding.etDte.text?.toString()?.trim() ?: ""
            if (dte.isEmpty()) { showToast("Ingresá el número de DT-e"); return@setOnClickListener }
            verificarOrigen(dte)
        }

        binding.btnEnviarCierre.setOnClickListener {
            val dte = binding.etDte.text?.toString()?.trim() ?: ""
            if (dte.isEmpty()) { showToast("DT-e requerido para cierre"); return@setOnClickListener }
            if (sinCoincidirUltima > 0) {
                confirmarEnvioSinVerificar(
                    "$sinCoincidirUltima caravana(s) no coinciden con el origen del DT-e o no se pudieron verificar."
                ) { enviarCierre(dte) }
            } else {
                enviarCierre(dte)
            }
        }

        binding.btnEnviarWebPost.setOnClickListener {
            val dte = binding.etDte.text?.toString()?.trim() ?: ""
            confirmarYEnviarAWeb(dte)
        }
    }

    /** Si no se cargó el DTe, se avisa antes de mandar el mensaje a la web —
     *  igual criterio que Comparar vs TRI y Ordenar por Origen. */
    private fun confirmarYEnviarAWeb(dte: String) {
        if (dte.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sin número de DTe")
                .setMessage("No completaste el DTe. ¿Continuar de todas formas?")
                .setPositiveButton("Sí, continuar") { _, _ -> enviarAWeb(dte, caravanas, "Verificar Origen") }
                .setNegativeButton("No, volver", null)
                .show()
        } else {
            enviarAWeb(dte, caravanas, "Verificar Origen")
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

    private fun verificarOrigen(dte: String) {
        setVerifLoading(true)
        lifecycleScope.launch {
            // 1. Consultar DT-e para obtener RENSPA origen
            val (dteOk, dteInfo) = withContext(Dispatchers.IO) {
                SenasaClient.consultarDte(SenasaClient.senasaBase(session.senasaEnv), session.wsUsername, session.wsToken, dte)
            }
            if (!dteOk || dteInfo == null) {
                setVerifLoading(false)
                showToast("No se pudo consultar el DT-e")
                return@launch
            }
            val origenCodigo = dteInfo.origenCodigo

            // 2. Consultar cada caravana y comparar RENSPA actual con origen del DT-e
            val resultados = mutableListOf<ResultItem>()
            caravanas.forEachIndexed { idx, caravana ->
                withContext(Dispatchers.Main) {
                    binding.tvProgreso.text = "Verificando ${idx + 1} de ${caravanas.size}..."
                }
                val estado = withContext(Dispatchers.IO) {
                    SenasaClient.consultarCaravana(SenasaClient.senasaBase(session.senasaEnv), session.wsUsername, session.wsToken, caravana)
                }
                val coincide = estado.ok && estado.renspaActual == origenCodigo
                resultados.add(ResultItem(caravana, coincide, estado.renspaActual, origenCodigo, estado.sexo, estado.ok))
            }

            val exitosas = resultados.filter { it.datosOk }.map { it.codigo }
            withContext(Dispatchers.IO) {
                ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
            }

            setVerifLoading(false)
            val todasCoinciden = resultados.all { it.coincide }
            sinCoincidirUltima = resultados.count { !it.coincide }

            binding.tvBannerOrigen.visibility = View.VISIBLE
            if (todasCoinciden) {
                binding.tvBannerOrigen.text = "✓  Todas las caravanas son del origen correcto"
                binding.tvBannerOrigen.setBackgroundResource(R.drawable.bg_resultado_ok)
                binding.tvBannerOrigen.setTextColor(requireContext().getColor(R.color.verde_ok))
            } else {
                val sinCoincidir = resultados.count { !it.coincide }
                binding.tvBannerOrigen.text = "✗  $sinCoincidir caravana(s) no coinciden con el origen del DT-e\nOrigen DT-e: $origenCodigo"
                binding.tvBannerOrigen.setBackgroundResource(R.drawable.bg_resultado_error)
                binding.tvBannerOrigen.setTextColor(requireContext().getColor(R.color.rojo_error))
            }

            mostrarResultados(resultados)
            binding.panelCierre.visibility = View.VISIBLE
        }
    }

    private fun mostrarResultados(resultados: List<ResultItem>) {
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
                    setBackgroundResource(if (item.coincide) R.drawable.bg_caravana_ok else R.drawable.bg_caravana_dup)
                    findViewById<TextView>(R.id.tvIcono).text = if (item.coincide) "✓" else "✗"
                    findViewById<TextView>(R.id.tvIcono).setTextColor(
                        ctx.getColor(if (item.coincide) R.color.verde_ok else R.color.rojo_error)
                    )
                    findViewById<TextView>(R.id.tvCodigo).text = item.codigo
                    val tvDetalle = findViewById<TextView>(R.id.tvDetalle)
                    if (!item.coincide) {
                        tvDetalle.visibility = View.VISIBLE
                        tvDetalle.text = "RENSPA actual: ${item.renspaActual.ifBlank { "sin datos" }}"
                    } else {
                        tvDetalle.visibility = View.GONE
                    }
                }
            }
        }
        binding.recyclerResultados.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerResultados.adapter = adapter
        adapter.submitList(resultados)

        actualizarContadores(resultados)
    }

    /** Entidades (RENSPA/feria) distintas y desglose de sexo, sobre las caravanas con datos de SENASA. */
    @SuppressLint("SetTextI18n")
    private fun actualizarContadores(resultados: List<ResultItem>) {
        val conDatos = resultados.filter { it.datosOk }
        val entidades = conDatos.map { it.renspaActual.trim() }.filter { it.isNotEmpty() }.toSet()

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
        binding.panelContadores.visibility = if (resultados.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun enviarAWeb(dte: String, caravanas: List<String>, titulo: String) {
        setResultLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.enviarAWeb(session.baseUrl(), session.token, dte, caravanas, "verificacion", mapOf("titulo" to titulo))
            }
            setResultLoading(false)
            showResultado(result.ok, result.message)
        }
    }

    private fun enviarCierre(dte: String) {
        if (session.wsUsername.isEmpty()) { showToast("Sin credenciales SENASA"); return }
        setResultLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SenasaClient.enviarCierre(SenasaClient.senasaBase(session.senasaEnv), session.wsUsername, session.wsToken, dte, caravanas, null, null)
            }
            if (result.ok) {
                withContext(Dispatchers.IO) {
                    ApiClient.enviarAWeb(session.baseUrl(), session.token, dte, caravanas, "cierre")
                }
            }
            setResultLoading(false)
            showResultado(result.ok, result.message)
        }
    }

    private fun setVerifLoading(on: Boolean) {
        binding.progressVerif.visibility = if (on) View.VISIBLE else View.GONE
        binding.tvProgreso.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnVerificar.isEnabled = !on
        binding.btnEnviarWeb.isEnabled = !on
    }

    private fun setResultLoading(on: Boolean) {
        binding.progressSend.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnEnviarCierre.isEnabled = !on
        binding.btnEnviarWebPost.isEnabled = !on
    }

    /** El resultado queda al final de un formulario largo — sin este scroll el
     *  mensaje queda fuera de la vista y parece que el botón no hizo nada. */
    private fun scrollToView(target: View) {
        target.post {
            binding.scrollVerifOrigen.smoothScrollTo(0, target.top)
        }
    }

    private fun showResultado(ok: Boolean, msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = if (ok) "✓ $msg" else "✗ $msg"
        binding.tvResultado.setTextColor(requireContext().getColor(if (ok) R.color.verde_ok else R.color.rojo_error))
        binding.tvResultado.setBackgroundResource(if (ok) R.drawable.bg_resultado_ok else R.drawable.bg_resultado_error)
        scrollToView(binding.tvResultado)
        if (ok) {
            binding.root.postDelayed({
                if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
            }, 2500)
        }
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
