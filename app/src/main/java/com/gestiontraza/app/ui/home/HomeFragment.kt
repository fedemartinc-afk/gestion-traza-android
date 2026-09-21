package com.gestiontraza.app.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gestiontraza.app.R
import com.gestiontraza.app.bluetooth.BtConnectionViewModel
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.PendingQueue
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var session: SessionManager
    // Alcance de Activity: la misma conexión sigue viva al navegar a Lectura y otras pantallas.
    private val btVm: BtConnectionViewModel by activityViewModels()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) showDeviceSelector()
        else showToast("Permiso Bluetooth requerido")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        setupBotonVolver()

        val tipoLabel = when (session.tipoSesionActual) {
            "productor"     -> "Productor Agropecuario"
            "consignatario" -> "Consignatario"
            else            -> ""
        }
        val sesionBase = session.sesionNombre.ifBlank { session.usuarioNombre }
        binding.tvSesionNombre.text = if (tipoLabel.isNotEmpty()) "$sesionBase · $tipoLabel" else sesionBase

        // Mostrar panel según tipo de sesión activo. El panel de conexión SPP
        // (Lector BT) es común a ambos perfiles: el productor también puede
        // necesitar conectar un lector RS420/AS420 antes de "Leer caravanas"
        // (antes solo estaba disponible para consignatario, dejando al
        // productor sin forma de iniciar la conexión SPP).
        binding.panelBt.visibility = View.VISIBLE
        if (session.tipoSesionActual == "productor") {
            binding.panelConsignatario.visibility = View.GONE
            binding.panelProductor.visibility     = View.VISIBLE
        } else {
            binding.panelConsignatario.visibility = View.VISIBLE
            binding.panelProductor.visibility     = View.GONE
        }

        btVm.estado.observe(viewLifecycleOwner) { estado ->
            when (estado) {
                is BtConnectionViewModel.Estado.Conectado -> {
                    binding.dotBt.setBackgroundResource(R.drawable.circle_green)
                    binding.tvBtEstado.text = "SPP conectado: ${estado.deviceName}"
                }
                is BtConnectionViewModel.Estado.Conectando -> {
                    binding.dotBt.setBackgroundResource(R.drawable.circle_gray)
                    binding.tvBtEstado.text = "Conectando con ${estado.deviceName}..."
                }
                is BtConnectionViewModel.Estado.Error -> {
                    binding.dotBt.setBackgroundResource(R.drawable.circle_red)
                    binding.tvBtEstado.text = estado.msg
                }
                BtConnectionViewModel.Estado.Desconectado -> {
                    binding.dotBt.setBackgroundResource(R.drawable.circle_gray)
                    binding.tvBtEstado.text = "Bluetooth — sin lector conectado"
                }
            }
        }

        binding.btnBtConectar.setOnClickListener { requestBtAndConnect() }

        binding.btnLeerCaravanas.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_reading)
        }

        binding.btnConfig.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_config)
        }

        binding.btnEnviarPendientes.setOnClickListener { enviarPendientes() }

        binding.btnUbicacionActual.setOnClickListener {
            findNavController().navigate(
                HomeFragmentDirections.actionHomeToReading(
                    "ubicacion_actual",
                    "Ubicación actual caravana"
                )
            )
        }

        binding.btnImportarSesiones.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_importar)
        }

        setupModulosProductor()
    }

    /** Cada módulo del productor entra por la pantalla de lectura con su propio modo. */
    private fun setupModulosProductor() {
        val irALectura = { mode: String, titulo: String ->
            findNavController().navigate(
                HomeFragmentDirections.actionHomeToReading(mode, titulo)
            )
        }
        // Sin modo: lee y después elige en el hub (Verificar Origen / Comparar vs
        // TRI / Ordenar por Origen) sin tener que volver a escanear para cada uno.
        binding.btnLeerCaravanasProductor.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_reading)
        }
        binding.btnEstadoTRI.setOnClickListener         { irALectura("estado_tri",         "Estado para TRI") }
        binding.btnEstadoPredespacho.setOnClickListener { irALectura("estado_predespacho", "Estado para Predespacho") }
        binding.btnUbicacionActualProductor.setOnClickListener { irALectura("ubicacion_actual", "Ubicación actual caravana") }

        binding.btnRegistrar.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_registrar)
        }

        binding.btnImportarSesionesProductor.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_importar)
        }
    }

    private fun setupBotonVolver() {
        val nav = findNavController()
        if (nav.previousBackStackEntry == null) {
            binding.btnBack.visibility = View.GONE
        } else {
            binding.btnBack.setOnClickListener { nav.navigateUp() }
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarBadgePendientes()
    }

    private fun actualizarBadgePendientes() {
        val count = PendingQueue(requireContext()).countPara(session.cuentaActivaId)
        if (count > 0) {
            binding.framePendientes.visibility = View.VISIBLE
            binding.tvBadgePendientes.text = count.toString()
        } else {
            binding.framePendientes.visibility = View.GONE
        }
    }

    private fun enviarPendientes() {
        val queue = PendingQueue(requireContext())
        val cuentaId = session.cuentaActivaId
        val items = queue.paraCuenta(cuentaId)
        if (items.isEmpty()) { actualizarBadgePendientes(); return }

        // Credenciales de la cuenta que generó estos pendientes — no las de
        // "lo que esté activo ahora", para que el envío sea correcto aunque
        // el dispositivo haya cambiado de cuenta entre medio.
        val cred = session.credencialesDe(cuentaId)
        if (cred == null) {
            showToast("No se pudo enviar: la cuenta ya no está guardada en el dispositivo")
            return
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Enviando pendientes")
            .setMessage("Enviando ${items.size} solicitud(es)...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            var enviados = 0
            var fallidos = 0
            val pendingAun = mutableListOf<PendingQueue.PendingItem>()

            for (item in items) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        if (item.tipo == "cierre") {
                            ApiClient.enviarCierre(
                                baseUrl = cred.baseUrl,
                                token = cred.token,
                                wsUsername = cred.wsUsername,
                                wsToken = cred.wsToken,
                                dte = item.dtes.firstOrNull() ?: "",
                                caravanas = item.caravanas,
                                lat = item.lat,
                                lon = item.lon,
                                senasaEnv = cred.senasaEnv
                            ).ok
                        } else {
                            ApiClient.enviarAWeb(
                                baseUrl = cred.baseUrl,
                                token = cred.token,
                                dte = item.dtes.joinToString("|"),
                                caravanas = item.caravanas,
                                tipo = "web",
                                extra = mapOf("titulo" to item.titulo)
                            ).ok
                        }
                    }.getOrElse { false }
                }
                if (ok) enviados++ else { fallidos++; pendingAun.add(item) }
            }

            // Solo se tocan los pendientes de esta cuenta; los de otras cuentas quedan intactos
            queue.reemplazarCuenta(cuentaId, pendingAun)

            dialog.dismiss()
            actualizarBadgePendientes()

            val resumen = if (fallidos == 0)
                "Enviados: $enviados ✓"
            else
                "Enviados: $enviados  •  Fallidos: $fallidos (quedan en cola)"
            showToast(resumen)
        }
    }

    private fun requestBtAndConnect() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) showDeviceSelector() else permLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelector() {
        val devices = btVm.pairedDevices()
        if (devices.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sin dispositivos")
                .setMessage("Vinculá el lector RFID en Ajustes → Bluetooth y volvé a intentar.\n\nSi tu lector funciona en modo HID (teclado), no necesitás conectarlo aquí.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // Importante: setItems() y setMessage() no pueden combinarse en un mismo
        // AlertDialog (uno pisa al otro y desaparece la lista) — el hint va aparte,
        // como Toast, antes de abrir el selector.
        showToast("Modo HID: si el lector está vinculado como teclado, no hace falta seleccionarlo aquí.")

        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar lector SPP")
            .setItems(names) { _, idx -> btVm.conectar(devices[idx]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
