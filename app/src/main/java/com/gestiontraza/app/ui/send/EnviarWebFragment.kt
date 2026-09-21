package com.gestiontraza.app.ui.send

import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.PendingQueue
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentEnviarWebBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Segunda pantalla de "Enviar a usuario web": uno o varios DTe (se puede enviar sin
 * ninguno, con advertencia) y un mensaje opcional de hasta 30 caracteres.
 */
class EnviarWebFragment : Fragment() {

    private var _binding: FragmentEnviarWebBinding? = null
    private val binding get() = _binding!!

    private val args: EnviarWebFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>
    private var campoEscaneo: TextInputEditText? = null

    private val barcodeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        campoEscaneo?.setText(dteDesdeCodigo(code))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentEnviarWebBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        val n = caravanas.size
        binding.tvResumen.text = "$n caravana${if (n != 1) "s" else ""}"

        agregarFilaDte(borrable = false)
        binding.btnAgregarDte.setOnClickListener { agregarFilaDte(borrable = true) }

        binding.swMensaje.setOnCheckedChangeListener { _, marcado ->
            binding.tilMensaje.visibility = if (marcado) View.VISIBLE else View.GONE
            if (!marcado) binding.etMensaje.setText("")
        }

        binding.btnEnviar.setOnClickListener {
            val dtes = valoresDte()
            if (dtes.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Sin número de DTe")
                    .setMessage("Está por enviar una lectura de caravanas sin informar DTe. ¿Desea continuar?")
                    .setPositiveButton("Sí, continuar") { _, _ -> enviar(dtes) }
                    .setNegativeButton("No, volver", null)
                    .show()
            } else {
                enviar(dtes)
            }
        }
    }

    private fun agregarFilaDte(borrable: Boolean) {
        val fila = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_dte_row, binding.llDtes, false)
        val et = fila.findViewById<TextInputEditText>(R.id.etDteExtra)
        val toggle = fila.findViewById<MaterialButton>(R.id.btnToggleTecladoDteExtra)
        val escanear = fila.findViewById<MaterialButton>(R.id.btnScanDteExtra)
        val borrar = fila.findViewById<MaterialButton>(R.id.btnDeleteDteExtra)

        if (!session.manualDte) {
            et.keyListener = null
            et.hint = "Escanear  📷"
        }
        configurarTecladoDte(et, toggle)
        escanear.setOnClickListener {
            campoEscaneo = et
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }
        if (borrable) borrar.setOnClickListener { binding.llDtes.removeView(fila) }
        else borrar.visibility = View.GONE
        binding.llDtes.addView(fila)
    }

    private fun valoresDte(): List<String> =
        (0 until binding.llDtes.childCount).mapNotNull { i ->
            binding.llDtes.getChildAt(i).findViewById<TextInputEditText>(R.id.etDteExtra)
                ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun enviar(dtes: List<String>) {
        val mensaje = binding.etMensaje.text?.toString()?.trim().orEmpty()

        if (!isOnline()) {
            PendingQueue(requireContext()).add(
                PendingQueue.PendingItem(
                    tipo = "web",
                    dtes = dtes,
                    caravanas = caravanas,
                    lat = null,
                    lon = null,
                    cuentaId = session.cuentaActivaId,
                    titulo = mensaje
                )
            )
            mostrarPendiente("Sin conexión — ${caravanas.size} caravanas guardadas como pendiente")
            return
        }

        cargando(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                ApiClient.enviarAWeb(
                    baseUrl = session.baseUrl(),
                    token = session.token,
                    dte = dtes.joinToString("|"),
                    caravanas = caravanas,
                    tipo = "web",
                    extra = mapOf("titulo" to mensaje)
                )
            }
            cargando(false)
            if (resultado.ok) {
                val detalle = "DTe: ${dtes.joinToString(", ").ifEmpty { "sin DTe" }}" +
                    (if (mensaje.isNotEmpty()) " · \"$mensaje\"" else "") + " · ${caravanas.size} caravanas"
                mostrarResultado("✓ ${resultado.message}\n$detalle", exito = true)
                binding.btnEnviar.isEnabled = false
                binding.root.postDelayed({
                    if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
                }, 2500)
            } else {
                mostrarResultado("✗ ${resultado.message}", exito = false)
            }
        }
    }

    private fun mostrarPendiente(texto: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "⏳ $texto"
        binding.tvResultado.setTextColor(0xFF996600.toInt())
        binding.tvResultado.setBackgroundColor(0x22FFA000)
        binding.btnEnviar.isEnabled = false
        binding.root.postDelayed({
            if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
        }, 2500)
    }

    private fun mostrarResultado(texto: String, exito: Boolean) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = texto
        binding.tvResultado.setTextColor(resources.getColor(if (exito) R.color.verde_ok else R.color.rojo_error, null))
        binding.tvResultado.setBackgroundColor(resources.getColor(if (exito) R.color.verde_ok_bg else R.color.rojo_error_bg, null))
        binding.scrollEnviarWeb.post { binding.scrollEnviarWeb.smoothScrollTo(0, binding.tvResultado.top) }
    }

    private fun cargando(si: Boolean) {
        binding.progressBar.visibility = if (si) View.VISIBLE else View.GONE
        binding.btnEnviar.isEnabled = !si
        if (si) binding.tvResultado.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        val cm = requireContext().getSystemService(ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
