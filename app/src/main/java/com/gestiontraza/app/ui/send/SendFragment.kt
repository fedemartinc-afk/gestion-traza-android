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
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentSendBinding
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

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var currentScanField: TextInputEditText? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) fetchLocation() }

    private val barcodeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        val soloDigitos = code.filter { it.isDigit() }.take(9)
        val value = if (soloDigitos.isNotEmpty()) soloDigitos else code
        (currentScanField ?: binding.etDte).setText(value)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        val n = caravanas.size
        binding.tvResumen.text = "$n caravana${if (n != 1) "s" else ""} válida${if (n != 1) "s" else ""}"

        fetchLocation()

        binding.btnEscanearDte.setOnClickListener {
            currentScanField = binding.etDte
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }

        binding.btnAgregarDte.setOnClickListener { addExtraDteRow() }

        binding.btnEnviarWeb.setOnClickListener {
            val dtes = getDteValues()
            if (dtes.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Sin número de DTe")
                    .setMessage("Está por enviar una lectura de caravanas sin informar DTe. ¿Desea continuar?")
                    .setPositiveButton("Sí, continuar") { _, _ -> doEnviar(dtes, cierre = false) }
                    .setNegativeButton("No, volver", null)
                    .show()
            } else {
                doEnviar(dtes, cierre = false)
            }
        }

        binding.btnEnviarCierre.setOnClickListener {
            val dtes = getDteValues()
            if (dtes.isEmpty()) {
                showError("Ingresá el número de DTe para el cierre SENASA")
                return@setOnClickListener
            }
            if (session.wsUsername.isEmpty()) {
                showError("Tu usuario web no tiene credenciales SENASA configuradas")
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Confirmar cierre SENASA")
                .setMessage("Se enviarán ${caravanas.size} caravanas al DTe ${dtes.first()} en SENASA.\n\nEsta operación es definitiva. ¿Continuás?")
                .setPositiveButton("Sí, enviar cierre") { _, _ -> doEnviar(dtes, cierre = true) }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.btnEnviarTri.setOnClickListener {
            val nota = binding.etNotaTri.text?.toString()?.trim() ?: ""
            AlertDialog.Builder(requireContext())
                .setTitle("Enviar para nueva TRI")
                .setMessage("Se enviarán ${caravanas.size} caravanas${if (nota.isNotEmpty()) " (\"$nota\")" else ""} a la bandeja de Recibidas.\n\n¿Confirmás?")
                .setPositiveButton("Sí, enviar") { _, _ -> doEnviarTRI(nota) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun addExtraDteRow() {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_dte_row, binding.llExtraDtes, false)

        val et  = row.findViewById<TextInputEditText>(R.id.etDteExtra)
        val btnScan   = row.findViewById<MaterialButton>(R.id.btnScanDteExtra)
        val btnDelete = row.findViewById<MaterialButton>(R.id.btnDeleteDteExtra)

        btnScan.setOnClickListener {
            currentScanField = et
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }
        btnDelete.setOnClickListener {
            binding.llExtraDtes.removeView(row)
            actualizarVisibilidadCierre()
        }

        binding.llExtraDtes.addView(row)
        actualizarVisibilidadCierre()
    }

    private fun actualizarVisibilidadCierre() {
        val hayExtra = binding.llExtraDtes.childCount > 0
        binding.btnEnviarCierre.visibility  = if (hayExtra) View.GONE else View.VISIBLE
        binding.tvMultipleDteNota.visibility = if (hayExtra) View.VISIBLE else View.GONE
    }

    private fun getDteValues(): List<String> {
        val result = mutableListOf<String>()
        val first = binding.etDte.text?.toString()?.trim() ?: ""
        if (first.isNotEmpty()) result.add(first)
        for (i in 0 until binding.llExtraDtes.childCount) {
            val v = binding.llExtraDtes.getChildAt(i)
                .findViewById<TextInputEditText>(R.id.etDteExtra)
                ?.text?.toString()?.trim() ?: ""
            if (v.isNotEmpty()) result.add(v)
        }
        return result
    }

    private fun doEnviar(dtes: List<String>, cierre: Boolean) {
        if (!isOnline()) {
            PendingQueue(requireContext()).add(
                PendingQueue.PendingItem(
                    tipo = if (cierre) "cierre" else "web",
                    dtes = dtes,
                    caravanas = caravanas,
                    lat = lastLat,
                    lon = lastLon
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
                        baseUrl = session.baseUrl(),
                        token = session.token,
                        wsUsername = session.wsUsername,
                        wsToken = session.wsToken,
                        dte = dtes.first(),
                        caravanas = caravanas,
                        lat = lastLat,
                        lon = lastLon,
                        senasaEnv = session.senasaEnv
                    )
                } else {
                    ApiClient.enviarCaravanas(
                        baseUrl = session.baseUrl(),
                        token = session.token,
                        dte = dtes.joinToString("|"),
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

    private fun doEnviarTRI(nota: String) {
        if (!isOnline()) {
            showPending("Sin conexión — ${caravanas.size} caravanas no enviadas (TRI requiere conexión)")
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.enviarNuevaTRI(
                    baseUrl   = session.baseUrl(),
                    token     = session.token,
                    nota      = nota,
                    caravanas = caravanas
                )
            }
            setLoading(false)
            if (result.ok) {
                showSuccessTRI(result.message, nota)
            } else {
                showError(result.message)
            }
        }
    }

    private fun showSuccessTRI(msg: String, nota: String) {
        binding.tvResultado.visibility = View.VISIBLE
        val extra = if (nota.isNotEmpty()) " · \"$nota\"" else ""
        binding.tvResultado.text = "✓ $msg${extra}\n${caravanas.size} caravanas"
        binding.tvResultado.setTextColor(resources.getColor(R.color.verde_ok, null))
        binding.tvResultado.setBackgroundColor(resources.getColor(R.color.verde_ok_bg, null))
        binding.btnEnviarWeb.isEnabled    = false
        binding.btnEnviarCierre.isEnabled = false
        binding.btnEnviarTri.isEnabled    = false
        binding.root.postDelayed({
            if (isAdded) findNavController().navigate(R.id.action_send_to_home)
        }, 3000)
    }

    private fun showSuccess(msg: String, dtesStr: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = "✓ $msg\nDTe: $dtesStr · ${caravanas.size} caravanas"
        binding.tvResultado.setTextColor(resources.getColor(R.color.verde_ok, null))
        binding.tvResultado.setBackgroundColor(resources.getColor(R.color.verde_ok_bg, null))
        binding.btnEnviarWeb.isEnabled = false
        binding.btnEnviarCierre.isEnabled = false
        binding.root.postDelayed({
            if (isAdded) findNavController().navigate(R.id.action_send_to_home)
        }, 3000)
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
        binding.btnEnviarWeb.isEnabled = false
        binding.btnEnviarCierre.isEnabled = false
        binding.root.postDelayed({
            if (isAdded) findNavController().navigate(R.id.action_send_to_home)
        }, 2500)
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility    = if (loading) View.VISIBLE else View.GONE
        binding.btnEnviarWeb.isEnabled    = !loading
        binding.btnEnviarCierre.isEnabled = !loading
        binding.btnEnviarTri.isEnabled    = !loading
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
