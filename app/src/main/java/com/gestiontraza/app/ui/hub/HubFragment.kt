package com.gestiontraza.app.ui.hub

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.PendingQueue
import com.gestiontraza.app.data.SessionFileStore
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.data.FormatoCaravana
import com.gestiontraza.app.databinding.DialogDteUnicoBinding
import com.gestiontraza.app.databinding.FragmentHubBinding
import com.gestiontraza.app.ui.importar.guardarSesionImportada
import com.gestiontraza.app.ui.send.BarcodeScanActivity
import com.gestiontraza.app.ui.send.configurarTecladoDte
import com.gestiontraza.app.ui.send.confirmarEnvioSinVerificar
import com.gestiontraza.app.ui.send.dteDesdeCodigo
import com.google.android.gms.location.LocationServices
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Elegir qué hacer con una tanda de caravanas — llega tanto desde una lectura
 * recién hecha (perfil productor, sin módulo elegido de antemano) como desde
 * "Importar sesiones" (ambos perfiles). Estado TRI y Estado Predespacho son
 * módulos exclusivos del productor y se ocultan para consignatario.
 *
 * También ofrece enviar directo a la web sin verificar nada contra SENASA,
 * para quien no necesita pasar por ninguno de los otros módulos.
 */
class HubFragment : Fragment() {

    private var _binding: FragmentHubBinding? = null
    private val binding get() = _binding!!
    private val args: HubFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>
    private lateinit var caravanasJson: String

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var campoEscaneo: TextInputEditText? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) fetchLocation() }

    private val barcodeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        campoEscaneo?.setText(dteDesdeCodigo(code))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        caravanasJson = args.caravanas
        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        binding.tvResumen.text = "${caravanas.size} caravana${if (caravanas.size != 1) "s" else ""} válida${if (caravanas.size != 1) "s" else ""}"

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        // Estado TRI / Predespacho son módulos exclusivos del perfil productor.
        val esProductor = session.tipoSesionActual == "productor"
        binding.btnEstadoTri.visibility = if (esProductor) View.VISIBLE else View.GONE
        binding.btnEstadoPredespacho.visibility = if (esProductor) View.VISIBLE else View.GONE
        // Enviar a DT-e (cierre): igual que en el perfil consignatario, solo para productor.
        binding.btnEnviarCierre.visibility = if (esProductor) View.VISIBLE else View.GONE
        binding.tvCierreNota.visibility = if (esProductor) View.VISIBLE else View.GONE
        if (esProductor) fetchLocation()

        binding.btnVerificarOrigen.setOnClickListener {
            findNavController().navigate(HubFragmentDirections.actionHubToVerificarOrigen(caravanasJson))
        }
        binding.btnCompararTri.setOnClickListener {
            findNavController().navigate(HubFragmentDirections.actionHubToCompararTri(caravanasJson, ""))
        }
        binding.btnOrdenarOrigen.setOnClickListener {
            findNavController().navigate(HubFragmentDirections.actionHubToOrdenarOrigen(caravanasJson))
        }
        binding.btnUbicacionActual.setOnClickListener {
            findNavController().navigate(HubFragmentDirections.actionHubToUbicacionActual(caravanasJson))
        }
        binding.btnEstadoTri.setOnClickListener {
            findNavController().navigate(HubFragmentDirections.actionHubToEstadoTri(caravanasJson))
        }
        binding.btnEstadoPredespacho.setOnClickListener {
            findNavController().navigate(HubFragmentDirections.actionHubToEstadoPredespacho(caravanasJson))
        }
        binding.btnEnviarWebSinVerificar.setOnClickListener { enviarSinVerificar() }
        binding.btnEnviarCierre.setOnClickListener { pedirDteCierre() }
        binding.btnVerSesiones.setOnClickListener {
            findNavController().navigate(HubFragmentDirections.actionHubToSesiones())
        }
        binding.btnCrearSesion.setOnClickListener {
            guardarSesionImportada(SessionFileStore(requireContext()), "", "Lectura", caravanas, "caravanas") { nombre ->
                android.widget.Toast.makeText(
                    requireContext(), "Sesión \"$nombre\" guardada en el dispositivo", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Pide el DTe (escaneado o escrito) al que se enviará el cierre a SENASA. */
    private fun pedirDteCierre() {
        val d = DialogDteUnicoBinding.inflate(layoutInflater)
        if (!session.manualDte) {
            d.etDteUnico.keyListener = null
            d.etDteUnico.hint = "Escanear código de barras del DTe  📷"
        }
        configurarTecladoDte(d.etDteUnico, d.btnToggleTecladoUnico)
        d.btnEscanearUnico.setOnClickListener {
            campoEscaneo = d.etDteUnico
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }
        d.tvDialogTitulo.text = "DTe para el cierre SENASA"
        d.tvDialogAyuda.text = "Ingresá o escaneá el DTe al que se enviarán las caravanas."
        d.btnDialogVerificar.visibility = View.GONE
        d.btnDialogEnviarWeb.visibility = View.GONE
        d.btnDialogCierre.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(requireContext()).setView(d.root).create()
        d.btnDialogCancelar.setOnClickListener { dialog.dismiss() }
        d.btnDialogCierre.setOnClickListener {
            val dte = d.etDteUnico.text?.toString()?.trim().orEmpty()
            if (dte.isEmpty()) { d.etDteUnico.error = "Ingresá el número de DTe"; return@setOnClickListener }
            dialog.dismiss()
            confirmarCierre(dte)
        }
        dialog.show()
    }

    private fun confirmarCierre(dte: String) {
        val invalidas = FormatoCaravana.invalidas(caravanas)
        if (invalidas.isNotEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Caravana con formato inválido")
                .setMessage(FormatoCaravana.mensaje(invalidas))
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
        confirmarEnvioSinVerificar("Las ${caravanas.size} caravanas no fueron verificadas contra el origen del DTe $dte.") {
            AlertDialog.Builder(requireContext())
                .setTitle("Confirmar cierre SENASA")
                .setMessage("Se enviarán ${caravanas.size} caravanas al DTe $dte en SENASA.\n\nEsta operación es definitiva. ¿Continuás?")
                .setPositiveButton("Sí, enviar cierre") { _, _ -> enviarCierre(dte) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    /** Saca de la lista las caravanas con formato inválido; después hay que volver a enviar. */
    private fun eliminarInvalidas() {
        val antes = caravanas.size
        caravanas = caravanas.filter { FormatoCaravana.esValida(it) }
        val quitadas = antes - caravanas.size
        if (caravanas.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No quedaron caravanas válidas", android.widget.Toast.LENGTH_LONG).show()
            findNavController().navigateUp()
            return
        }
        caravanasJson = JSONArray(caravanas).toString()
        binding.tvResumen.text = "${caravanas.size} caravana${if (caravanas.size != 1) "s" else ""} válida${if (caravanas.size != 1) "s" else ""}"
        android.widget.Toast.makeText(
            requireContext(), "Se eliminaron $quitadas caravana${if (quitadas != 1) "s" else ""}. Volvé a enviar el cierre.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun enviarCierre(dte: String) {
        if (!isOnline()) {
            PendingQueue(requireContext()).add(
                PendingQueue.PendingItem(
                    tipo = "cierre",
                    dtes = listOf(dte),
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
            setLoading(false)
            if (result.ok) showSuccess("${result.message}\nDTe: $dte") else showError(result.message)
        }
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

    private fun enviarSinVerificar() {
        val titulo = binding.etTituloMensaje.text?.toString()?.trim() ?: ""

        if (!isOnline()) {
            PendingQueue(requireContext()).add(
                PendingQueue.PendingItem(
                    tipo = "web",
                    dtes = emptyList(),
                    caravanas = caravanas,
                    lat = null,
                    lon = null,
                    cuentaId = session.cuentaActivaId,
                    titulo = titulo
                )
            )
            showPending("Sin conexión — ${caravanas.size} caravanas guardadas como pendiente")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.enviarAWeb(
                    baseUrl   = session.baseUrl(),
                    token     = session.token,
                    dte       = "",
                    caravanas = caravanas,
                    tipo      = "web",
                    extra     = mapOf("titulo" to titulo)
                )
            }
            setLoading(false)
            if (result.ok) showSuccess(result.message) else showError(result.message)
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.etTituloMensaje.isEnabled = !loading
        binding.btnEnviarWebSinVerificar.isEnabled = !loading
        binding.btnVerificarOrigen.isEnabled        = !loading
        binding.btnCompararTri.isEnabled            = !loading
        binding.btnOrdenarOrigen.isEnabled          = !loading
        binding.btnUbicacionActual.isEnabled        = !loading
        binding.btnEstadoTri.isEnabled              = !loading
        binding.btnEstadoPredespacho.isEnabled      = !loading
        binding.btnEnviarCierre.isEnabled           = !loading
        binding.btnCrearSesion.isEnabled            = !loading
        if (loading) binding.tvResultado.visibility = View.GONE
    }

    private fun showSuccess(msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "✓ $msg\n${caravanas.size} caravanas"
        binding.tvResultado.setTextColor(resources.getColor(R.color.verde_ok, null))
        binding.tvResultado.setBackgroundColor(resources.getColor(R.color.verde_ok_bg, null))
        binding.btnEnviarWebSinVerificar.isEnabled = false
        binding.btnEnviarCierre.isEnabled = false
        binding.root.postDelayed({
            if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
        }, 2500)
    }

    private fun showError(msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "✗ $msg"
        binding.tvResultado.setTextColor(resources.getColor(R.color.rojo_error, null))
        binding.tvResultado.setBackgroundColor(resources.getColor(R.color.rojo_error_bg, null))
    }

    private fun showPending(msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "⏳ $msg"
        binding.tvResultado.setTextColor(0xFF996600.toInt())
        binding.tvResultado.setBackgroundColor(0x22FFA000)
        binding.btnEnviarWebSinVerificar.isEnabled = false
        binding.btnEnviarCierre.isEnabled = false
        binding.root.postDelayed({
            if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
        }, 2500)
    }

    private fun isOnline(): Boolean {
        val cm = requireContext().getSystemService(ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
