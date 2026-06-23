package com.gestiontraza.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gestiontraza.app.R
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentConfigBinding
import com.gestiontraza.app.ui.send.BarcodeScanActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        try {
            val json = JSONObject(raw)
            val server    = json.optString("server",    "").trim()
            val token     = json.optString("token",     "").trim()
            val senasaEnv = json.optString("senasaEnv", "").trim()
            val session = SessionManager(requireContext())
            if (server.isNotEmpty())    session.serverUrl  = server
            if (token.isNotEmpty())     binding.etToken.setText(token)
            if (senasaEnv.isNotEmpty()) session.senasaEnv  = senasaEnv
            setEstado("QR leído — presioná Conectar", null)
        } catch (e: Exception) {
            setEstado("QR inválido: no se pudo leer el contenido", false)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        val session = SessionManager(requireContext())

        // Pre-llenar token si ya hay sesión guardada
        if (session.token.isNotBlank()) binding.etToken.setText(session.token)

        // Mostrar panel admin si ya está identificado como admin
        if (session.isAdmin) {
            mostrarPanelAdmin(session)
        }

        binding.btnEscanearQR.setOnClickListener {
            qrLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }

        binding.btnConectar.setOnClickListener {
            val token = binding.etToken.text?.toString()?.trim() ?: ""
            if (token.isEmpty()) {
                setEstado("Ingresá o escaneá el token", false)
                return@setOnClickListener
            }
            if (session.serverUrl.isBlank()) {
                setEstado("Escaneá el QR para configurar el servidor", false)
                return@setOnClickListener
            }

            binding.btnConectar.isEnabled   = false
            binding.btnEscanearQR.isEnabled = false
            setEstado("Verificando...", null)

            lifecycleScope.launch {
                val baseUrl = session.baseUrl()
                val result = withContext(Dispatchers.IO) {
                    ApiClient.verificarSesion(baseUrl, token)
                }
                binding.btnConectar.isEnabled   = true
                binding.btnEscanearQR.isEnabled = true

                if (result.ok && result.data != null) {
                    session.token        = token
                    session.sesionNombre = result.data.optString("sesion", "")
                    session.usuarioNombre= result.data.optString("usuario", "")
                    session.wsUsername   = result.data.optString("wsUsername", "")
                    session.wsToken      = result.data.optString("wsToken", "")
                    session.isAdmin      = result.data.optBoolean("esAdmin", false)
                    // El servidor define el ambiente SENASA; el admin puede sobreescribir localmente
                    val envServer = result.data.optString("senasaEnv", "")
                    if (envServer.isNotEmpty()) session.senasaEnv = envServer
                    if (session.isAdmin) mostrarPanelAdmin(session)
                    findNavController().navigate(R.id.action_config_to_home)
                } else {
                    setEstado("Error: ${result.message}", false)
                }
            }
        }
    }

    private fun mostrarPanelAdmin(session: SessionManager) {
        binding.cardAdmin.visibility = View.VISIBLE
        // Seleccionar el radio button según la preferencia guardada
        when (session.senasaEnv) {
            "produccion_gov" -> binding.rgSenasaEnv.check(R.id.rbProduccionGov)
            "produccion_gob" -> binding.rgSenasaEnv.check(R.id.rbProduccionGob)
            else             -> binding.rgSenasaEnv.check(R.id.rbReplica)
        }
        binding.rgSenasaEnv.setOnCheckedChangeListener { _, checkedId ->
            session.senasaEnv = when (checkedId) {
                R.id.rbProduccionGov -> "produccion_gov"
                R.id.rbProduccionGob -> "produccion_gob"
                else                 -> "replica"
            }
        }
    }

    private fun setEstado(msg: String, ok: Boolean?) {
        binding.tvEstado.text = msg
        binding.tvEstado.setTextColor(
            resources.getColor(
                when (ok) {
                    true  -> R.color.verde_ok
                    false -> R.color.rojo_error
                    null  -> R.color.gris_texto
                },
                null
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
