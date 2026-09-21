package com.gestiontraza.app.ui.send

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.PendingQueue
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.databinding.DialogDteUnicoBinding
import com.gestiontraza.app.databinding.FragmentSendBinding
import com.gestiontraza.app.ui.importar.guardarSesionImportada
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class SendFragment : Fragment() {

    private var _binding: FragmentSendBinding? = null
    private val binding get() = _binding!!

    private val args: SendFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>
    // Mismo listado en JSON, para pasarlo a las otras pantallas (cambia si se eliminan inválidas).
    private lateinit var caravanasJson: String

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var currentScanField: TextInputEditText? = null
    private var verificacionCompleta = false
    // Resultado de la última verificación de origen: DTe usado y cuántas no coincidieron.
    private var dteVerificado: String? = null
    private var sinCoincidirVerif = 0

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) fetchLocation() }

    private val barcodeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        currentScanField?.setText(dteDesdeCodigo(code))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        caravanasJson = args.caravanas
        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        val n = caravanas.size
        binding.tvResumen.text = "$n caravana${if (n != 1) "s" else ""} válida${if (n != 1) "s" else ""}"

        fetchLocation()

        // Enviar a usuario web: DTe(s) y mensaje opcional en la pantalla siguiente.
        binding.btnEnviarWeb.setOnClickListener {
            findNavController().navigate(SendFragmentDirections.actionSendToEnviarWeb(caravanasJson))
        }

        binding.btnCompararTri.setOnClickListener {
            findNavController().navigate(SendFragmentDirections.actionSendToCompararTri(caravanasJson, ""))
        }

        binding.btnOrdenarOrigen.setOnClickListener {
            findNavController().navigate(SendFragmentDirections.actionSendToOrdenarOrigen(caravanasJson, ""))
        }

        binding.btnVerificarOrigen.setOnClickListener { pedirDte(ModoDte.VERIFICAR) }
        binding.btnEnviarCierre.setOnClickListener { pedirDte(ModoDte.CIERRE) }

        // Guarda la lectura en "Sesiones guardadas en dispositivo", con el nombre que se elija.
        binding.btnCrearSesion.setOnClickListener {
            guardarSesionImportada(SessionFileStore(requireContext()), "", "Lectura", caravanas, "caravanas") { nombre ->
                android.widget.Toast.makeText(
                    requireContext(), "Sesión \"$nombre\" guardada en el dispositivo", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private enum class ModoDte { VERIFICAR, CIERRE }

    /**
     * Pide un único DTe (escaneado o escrito). En VERIFICAR ofrece dos caminos con ese
     * DTe: verificar el origen o enviar a usuario web. En CIERRE, enviar el cierre a SENASA.
     */
    private fun pedirDte(modo: ModoDte) {
        val d = DialogDteUnicoBinding.inflate(layoutInflater)
        if (!session.manualDte) {
            d.etDteUnico.keyListener = null
            d.etDteUnico.hint = "Escanear código de barras del DTe  📷"
        }
        configurarTecladoDte(d.etDteUnico, d.btnToggleTecladoUnico)
        d.btnEscanearUnico.setOnClickListener {
            currentScanField = d.etDteUnico
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }
        if (modo == ModoDte.CIERRE) {
            d.tvDialogTitulo.text = "DTe para el cierre SENASA"
            d.tvDialogAyuda.text = "Ingresá o escaneá el DTe al que se enviarán las caravanas."
            d.btnDialogVerificar.visibility = View.GONE
            d.btnDialogEnviarWeb.visibility = View.GONE
            d.btnDialogCierre.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(requireContext()).setView(d.root).create()
        fun dteIngresado(): String? {
            val v = d.etDteUnico.text?.toString()?.trim().orEmpty()
            if (v.isEmpty()) d.etDteUnico.error = "Ingresá el número de DTe"
            return v.ifEmpty { null }
        }
        d.btnDialogCancelar.setOnClickListener { dialog.dismiss() }
        d.btnDialogVerificar.setOnClickListener {
            val dte = dteIngresado() ?: return@setOnClickListener
            dialog.dismiss()
            if (session.wsUsername.isEmpty()) {
                showError("Configurá tus credenciales SENASA antes de verificar")
            } else {
                doVerificarOrigen(dte)
            }
        }
        d.btnDialogEnviarWeb.setOnClickListener {
            val dte = dteIngresado() ?: return@setOnClickListener
            dialog.dismiss()
            doEnviar(listOf(dte), cierre = false)
        }
        d.btnDialogCierre.setOnClickListener {
            val dte = dteIngresado() ?: return@setOnClickListener
            dialog.dismiss()
            confirmarCierre(dte)
        }
        dialog.show()
    }

    /** Saca de la lista las caravanas con formato inválido; después hay que volver a enviar. */
    private fun eliminarInvalidas() {
        val antes = caravanas.size
        caravanas = caravanas.filter { com.gestiontraza.app.data.FormatoCaravana.esValida(it) }
        val quitadas = antes - caravanas.size
        if (caravanas.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No quedaron caravanas válidas", android.widget.Toast.LENGTH_LONG).show()
            findNavController().navigateUp()
            return
        }
        caravanasJson = JSONArray(caravanas).toString()
        val n = caravanas.size
        binding.tvResumen.text = "$n caravana${if (n != 1) "s" else ""} válida${if (n != 1) "s" else ""}"
        android.widget.Toast.makeText(
            requireContext(), "Se eliminaron $quitadas caravana${if (quitadas != 1) "s" else ""}. Volvé a enviar el cierre.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun confirmarCierre(dte: String) {
        val invalidas = com.gestiontraza.app.data.FormatoCaravana.invalidas(caravanas)
        if (invalidas.isNotEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Caravana con formato inválido")
                .setMessage(com.gestiontraza.app.data.FormatoCaravana.mensaje(invalidas))
                .setPositiveButton("Eliminar de la lista") { _, _ -> eliminarInvalidas() }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        if (session.wsUsername.isEmpty()) {
            showError("Tu usuario web no tiene credenciales SENASA configuradas")
            return
        }
        if (lastLat == null || lastLon == null) {
            showError("Falta la ubicación — esperá a que se obtenga el GPS antes de enviar")
            return
        }
        val continuar = {
            AlertDialog.Builder(requireContext())
                .setTitle("Confirmar cierre SENASA")
                .setMessage("Se enviarán ${caravanas.size} caravanas al DTe $dte en SENASA.\n\nEsta operación es definitiva. ¿Continuás?")
                .setPositiveButton("Sí, enviar cierre") { _, _ -> doEnviar(listOf(dte), cierre = true) }
                .setNegativeButton("Cancelar", null)
                .show()
            Unit
        }
        val aviso = when {
            !verificacionCompleta || dteVerificado != dte.split("-")[0].trim() && dteVerificado != dte ->
                "Las ${caravanas.size} caravanas no fueron verificadas contra el origen del DTe $dte."
            sinCoincidirVerif > 0 ->
                "$sinCoincidirVerif caravana(s) no coinciden con el origen del DTe $dte o no se pudieron verificar."
            else -> null
        }
        if (aviso != null) confirmarEnvioSinVerificar(aviso) { continuar() } else continuar()
    }

    private fun doVerificarOrigen(dte: String) {
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
            if (!dteOk || dteInfo == null) {
                setLoading(false)
                showError("No se pudo consultar el DTe en SENASA")
                return@launch
            }

            val estados = withContext(Dispatchers.IO) {
                caravanas.map { cod ->
                    SenasaClient.consultarCaravana(
                        SenasaClient.senasaBase(session.senasaEnv),
                        session.wsUsername,
                        session.wsToken,
                        cod
                    )
                }
            }

            val exitosas = estados.filter { it.ok }.map { it.codigo }
            withContext(Dispatchers.IO) {
                ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
            }

            setLoading(false)
            dteVerificado = dte
            mostrarResultadosVerificacion(dteInfo, estados)
        }
    }

    private fun mostrarResultadosVerificacion(
        dteInfo: SenasaClient.DteInfo,
        estados: List<SenasaClient.EstadoCaravana>
    ) {
        val origenCodigo = dteInfo.origenCodigo
        val origenLabel = if (dteInfo.origenNombre.isNotBlank())
            "${dteInfo.origenNombre} ($origenCodigo)" else origenCodigo

        val coinciden   = estados.count { it.ok && it.renspaActual == origenCodigo }
        val noCoinciden = estados.count { it.ok && it.renspaActual != origenCodigo }
        val errores     = estados.count { !it.ok }

        binding.tvRenspaOrigen.text = "Origen DTe: $origenLabel"
        binding.tvVerifBanner.text  = "✓ $coinciden coinciden  ✗ $noCoinciden no coinciden  ⚠ $errores errores"

        binding.llResultadosVerif.removeAllViews()
        val ctx = requireContext()
        estados.forEach { estado ->
            val coincide = estado.ok && estado.renspaActual == origenCodigo
            val (emoji, detalle) = when {
                !estado.ok -> Pair("⚠", estado.error)
                coincide   -> Pair("✓", estado.renspaActual)
                else       -> Pair("✗", "${estado.renspaActual} ≠ $origenCodigo")
            }
            val tv = TextView(ctx).apply {
                text = "$emoji  ${estado.codigo}   $detalle"
                textSize = 12f
                setPadding(20, 10, 20, 10)
                setBackgroundResource(when {
                    !estado.ok -> R.drawable.bg_caravana_dup
                    coincide   -> R.drawable.bg_caravana_ok
                    else       -> R.drawable.bg_caravana_no_match
                })
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
            }
            binding.llResultadosVerif.addView(tv)
        }

        binding.cardVerificacion.visibility = View.VISIBLE
        verificacionCompleta = true
        sinCoincidirVerif = noCoinciden + errores
        scrollToView(binding.cardVerificacion)

        actualizarContadoresVerif(estados)
    }

    /** Entidades (RENSPA/feria) distintas y desglose de sexo, sobre las caravanas con datos de SENASA. */
    @SuppressLint("SetTextI18n")
    private fun actualizarContadoresVerif(estados: List<SenasaClient.EstadoCaravana>) {
        val conDatos = estados.filter { it.ok }
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

        binding.tvEntidadesCountVerif.text = "Entidades (RENSPA/feria)\n${entidades.size}"
        binding.tvSexoCountVerif.text = "Macho $machos   Hembra $hembras   s/sexo $sinSexo"
        binding.panelContadoresVerif.visibility = if (estados.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun doEnviar(dtes: List<String>, cierre: Boolean) {
        if (!isOnline()) {
            PendingQueue(requireContext()).add(
                PendingQueue.PendingItem(
                    tipo = if (cierre) "cierre" else "web",
                    dtes = dtes,
                    caravanas = caravanas,
                    lat = lastLat,
                    lon = lastLon,
                    cuentaId = session.cuentaActivaId
                )
            )
            showPending("Sin conexión — ${caravanas.size} caravanas guardadas como pendiente")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (cierre) {
                    ApiClient.enviarCierre(
                        baseUrl    = session.baseUrl(),
                        token      = session.token,
                        wsUsername = session.wsUsername,
                        wsToken    = session.wsToken,
                        dte        = dtes.first(),
                        caravanas  = caravanas,
                        lat        = lastLat,
                        lon        = lastLon,
                        senasaEnv  = session.senasaEnv
                    )
                } else {
                    ApiClient.enviarCaravanas(
                        baseUrl   = session.baseUrl(),
                        token     = session.token,
                        dte       = dtes.joinToString("|"),
                        caravanas = caravanas
                    )
                }
            }
            setLoading(false)
            if (result.ok) {
                val dtesStr = dtes.joinToString(", ").ifEmpty { "sin DTe" }
                showSuccess(result.message, dtesStr)
            } else {
                showError(result.message)
            }
        }
    }

    /** El resultado de cada acción (Verificar Origen, Enviar, etc.) queda al
     *  final de un formulario largo — sin este scroll, el mensaje queda fuera
     *  de la vista y parece que el botón no hizo nada. */
    private fun scrollToView(target: View) {
        target.post {
            binding.scrollSend.smoothScrollTo(0, target.top)
        }
    }

    private fun showSuccess(msg: String, dtesStr: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "✓ $msg\nDTe: $dtesStr · ${caravanas.size} caravanas"
        binding.tvResultado.setTextColor(resources.getColor(R.color.verde_ok, null))
        binding.tvResultado.setBackgroundColor(resources.getColor(R.color.verde_ok_bg, null))
        binding.btnEnviarWeb.isEnabled    = false
        binding.btnEnviarCierre.isEnabled = false
        scrollToView(binding.tvResultado)
        binding.root.postDelayed({
            if (isAdded) findNavController().navigate(R.id.action_send_to_home)
        }, 3000)
    }

    private fun showError(msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "✗ $msg"
        binding.tvResultado.setTextColor(resources.getColor(R.color.rojo_error, null))
        binding.tvResultado.setBackgroundColor(resources.getColor(R.color.rojo_error_bg, null))
        scrollToView(binding.tvResultado)
    }

    private fun showPending(msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "⏳ $msg"
        binding.tvResultado.setTextColor(0xFF996600.toInt())
        binding.tvResultado.setBackgroundColor(0x22FFA000)
        binding.btnEnviarWeb.isEnabled    = false
        binding.btnEnviarCierre.isEnabled = false
        scrollToView(binding.tvResultado)
        binding.root.postDelayed({
            if (isAdded) findNavController().navigate(R.id.action_send_to_home)
        }, 2500)
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility        = if (loading) View.VISIBLE else View.GONE
        binding.btnEnviarWeb.isEnabled        = !loading
        binding.btnVerificarOrigen.isEnabled  = !loading
        binding.btnEnviarCierre.isEnabled     = !loading
        binding.btnCrearSesion.isEnabled      = !loading
        if (loading) binding.tvResultado.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        val cm = requireContext().getSystemService(ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }

    @SuppressLint("MissingPermission")
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
