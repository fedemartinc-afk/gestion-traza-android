package com.gestiontraza.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
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
    private val args: ConfigFragmentArgs by navArgs()

    // Solo se usan cuando args.agregarCuenta es true: el QR puede escanearse
    // antes de que exista la cuenta nueva, así que el servidor/entorno quedan
    // acá hasta que "Conectar" confirme el token — recién ahí se crea la
    // cuenta y se escriben, para no pisar los datos de la cuenta que estaba
    // activa hasta ese momento.
    private var serverPendiente: String? = null
    private var senasaEnvPendiente: String? = null

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = BarcodeScanActivity.getResultCode(result) ?: return@registerForActivityResult
        try {
            val json = JSONObject(raw)
            // Leer 'url' (formato nuevo) o 'server' (compatibilidad hacia atrás)
            val url       = json.optString("url",       "").trim()
            val server    = json.optString("server",    "").trim()
            val token     = json.optString("token",     "").trim()
            val senasaEnv = json.optString("senasaEnv", "").trim()
            val serverValue = url.ifEmpty { server }

            if (args.agregarCuenta) {
                if (serverValue.isNotEmpty()) serverPendiente = serverValue
                if (senasaEnv.isNotEmpty())   senasaEnvPendiente = senasaEnv
            } else {
                val session = SessionManager(requireContext())
                if (serverValue.isNotEmpty()) session.serverUrl = serverValue
                if (senasaEnv.isNotEmpty())   session.senasaEnv = senasaEnv
            }
            if (token.isNotEmpty()) binding.etToken.setText(token)
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
        val esAgregar = args.agregarCuenta

        val nav = findNavController()
        if (nav.previousBackStackEntry == null) binding.btnBack.visibility = View.GONE
        else binding.btnBack.setOnClickListener { nav.navigateUp() }

        if (esAgregar) {
            // Se está agregando una cuenta nueva: no mostrar datos de la cuenta
            // activa (token, ajustes, perfil) para no confundirlos con los de
            // la cuenta que se está por conectar.
            binding.tvSubtitulo.text = "Agregando una cuenta nueva — escaneá su QR"
            binding.cardAjustes.visibility     = View.GONE
            binding.btnCambiarCuenta.visibility = View.GONE
            binding.btnCambiarPerfil.visibility = View.GONE
            binding.tvPerfilActual.visibility   = View.GONE
        } else {
            // Pre-llenar token si ya hay sesión guardada
            if (session.token.isNotBlank()) binding.etToken.setText(session.token)
            setupAjustes(session)
            setupCambiarCuenta(session)
            setupCambiarPerfil(session)

            // Con sesión ya conectada no hace falta ver el token ni el QR de
            // nuevo — se ocultan y queda solo el botón para sumar otra cuenta
            // (que reabre esta misma pantalla en modo "agregar").
            if (session.isConfigured()) {
                binding.llQrEntry.visibility     = View.GONE
                binding.llTokenManual.visibility = View.GONE
                binding.tvSubtitulo.text = session.sesionNombre.ifBlank { session.usuarioNombre }
                binding.btnAgregarNuevaCuenta.visibility = View.VISIBLE
                binding.btnAgregarNuevaCuenta.setOnClickListener {
                    findNavController().navigate(
                        ConfigFragmentDirections.actionConfigToConfig(agregarCuenta = true)
                    )
                }
            }
        }

        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) { null }
        binding.tvVersion.text = "Versión ${versionName ?: "?"}"

        binding.btnEscanearQR.setOnClickListener {
            qrLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }

        binding.btnConectar.setOnClickListener {
            val token = binding.etToken.text?.toString()?.trim() ?: ""
            if (token.isEmpty()) {
                setEstado("Ingresá o escaneá el token", false)
                return@setOnClickListener
            }
            val serverUrlCruda = if (esAgregar) serverPendiente ?: "" else session.serverUrl
            if (serverUrlCruda.isBlank()) {
                setEstado("Escaneá el QR para configurar el servidor", false)
                return@setOnClickListener
            }
            val baseUrlParaVerificar = run {
                val s = serverUrlCruda.trim().trimEnd('/')
                if (s.startsWith("http")) s else "https://$s"
            }

            binding.btnConectar.isEnabled   = false
            binding.btnEscanearQR.isEnabled = false
            setEstado("Verificando...", null)

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.verificarSesion(baseUrlParaVerificar, token)
                }
                binding.btnConectar.isEnabled   = true
                binding.btnEscanearQR.isEnabled = true

                if (result.ok && result.data != null) {
                    if (esAgregar) {
                        session.crearCuentaYActivar()
                        session.serverUrl = serverPendiente ?: ""
                    }
                    session.token        = token
                    session.sesionNombre = result.data.optString("sesion", "")
                    session.usuarioNombre= result.data.optString("usuario", "")
                    session.wsUsername   = result.data.optString("wsUsername", "")
                    session.wsToken      = result.data.optString("wsToken", "")
                    session.isAdmin      = result.data.optBoolean("esAdmin", false)
                    val envServer = result.data.optString("senasaEnv", "")
                    if (envServer.isNotEmpty()) {
                        session.senasaEnv = envServer
                    } else if (esAgregar && senasaEnvPendiente != null) {
                        session.senasaEnv = senasaEnvPendiente!!
                    }

                    // Guardar tipos de sesión permitidos
                    val tiposArr = result.data.optJSONArray("tiposSesion")
                    val tipos = mutableListOf<String>()
                    if (tiposArr != null) {
                        for (i in 0 until tiposArr.length()) tipos.add(tiposArr.getString(i))
                    }
                    if (tipos.isEmpty()) tipos.add("consignatario") // fallback para APIs viejas
                    session.tiposSesionPermitidos = tipos

                    // Siempre mostrar selector de perfil
                    if (esAgregar) {
                        // Viene de Home → Config → Cuentas → Config(agregar): se
                        // limpia toda esa cadena, no solo esta pantalla, para no
                        // dejar la lista de cuentas ni el Config anterior colgados
                        // debajo del selector de perfil de la cuenta nueva.
                        findNavController().navigate(
                            R.id.tipoSesionFragment,
                            null,
                            NavOptions.Builder()
                                .setPopUpTo(R.id.homeFragment, true)
                                .build()
                        )
                    } else {
                        findNavController().navigate(R.id.action_config_to_tipoSesion)
                    }
                } else {
                    setEstado("Error: ${result.message}", false)
                }
            }
        }
    }

    /** Abre la lista de cuentas guardadas para elegir otra o agregar una nueva. */
    private fun setupCambiarCuenta(session: SessionManager) {
        if (!session.isConfigured()) {
            binding.btnCambiarCuenta.visibility = View.GONE
            return
        }
        binding.btnCambiarCuenta.setOnClickListener {
            findNavController().navigate(R.id.action_config_to_cuentas)
        }
    }

    /**
     * Permite volver al selector de perfil sin tener que escanear el QR de nuevo.
     * Solo tiene sentido con una sesión ya validada.
     */
    private fun setupCambiarPerfil(session: SessionManager) {
        if (!session.isConfigured()) {
            binding.btnCambiarPerfil.visibility = View.GONE
            binding.tvPerfilActual.visibility   = View.GONE
            return
        }

        binding.tvPerfilActual.text = when (session.tipoSesionActual) {
            "productor"     -> "Perfil actual: Productor Agropecuario"
            "consignatario" -> "Perfil actual: Consignatario"
            else            -> "Sin perfil seleccionado"
        }

        binding.btnCambiarPerfil.setOnClickListener {
            session.tipoSesionActual = ""
            findNavController().navigate(R.id.action_config_to_tipoSesion)
        }
    }

    private fun setupAjustes(session: SessionManager) {
        binding.switchManualCaravanas.isChecked = session.manualCaravanas
        binding.switchManualDte.isChecked       = session.manualDte

        binding.switchManualCaravanas.setOnCheckedChangeListener { _, checked ->
            session.manualCaravanas = checked
        }
        binding.switchManualDte.setOnCheckedChangeListener { _, checked ->
            session.manualDte = checked
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
