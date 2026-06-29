package com.gestiontraza.app.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gestiontraza.app.R
import com.gestiontraza.app.bluetooth.BtManager
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
    private lateinit var btManager: BtManager
    private var btAdapter: BluetoothAdapter? = null

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

        val btMgr = requireContext().getSystemService(BluetoothManager::class.java)
        btAdapter = btMgr?.adapter
        btManager = BtManager(btAdapter)

        val tipoLabel = when (session.tipoSesionActual) {
            "productor"     -> "Productor"
            "consignatario" -> "Consignatario"
            else            -> ""
        }
        val sesionBase = session.sesionNombre.ifBlank { session.usuarioNombre }
        binding.tvSesionNombre.text = if (tipoLabel.isNotEmpty()) "$sesionBase · $tipoLabel" else sesionBase

        // Mostrar panel según tipo de sesión activo
        if (session.tipoSesionActual == "productor") {
            binding.panelConsignatario.visibility = View.GONE
            binding.panelBt.visibility            = View.GONE
            binding.panelProductor.visibility     = View.VISIBLE
        } else {
            binding.panelConsignatario.visibility = View.VISIBLE
            binding.panelBt.visibility            = View.VISIBLE
            binding.panelProductor.visibility     = View.GONE
        }

        btManager.listener = object : BtManager.Listener {
            override fun onConnected(deviceName: String) {
                binding.dotBt.setBackgroundResource(R.drawable.circle_green)
                binding.tvBtEstado.text = "SPP conectado: $deviceName"
            }
            override fun onLine(line: String) { /* handled in ReadingFragment */ }
            override fun onDisconnected() {
                binding.dotBt.setBackgroundResource(R.drawable.circle_gray)
                binding.tvBtEstado.text = "Lector SPP desconectado"
            }
            override fun onError(msg: String) {
                binding.dotBt.setBackgroundResource(R.drawable.circle_red)
                binding.tvBtEstado.text = msg
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
    }

    override fun onResume() {
        super.onResume()
        actualizarBadgePendientes()
    }

    private fun actualizarBadgePendientes() {
        val count = PendingQueue(requireContext()).count()
        if (count > 0) {
            binding.framePendientes.visibility = View.VISIBLE
            binding.tvBadgePendientes.text = count.toString()
        } else {
            binding.framePendientes.visibility = View.GONE
        }
    }

    private fun enviarPendientes() {
        val queue = PendingQueue(requireContext())
        val items = queue.toList()
        if (items.isEmpty()) { actualizarBadgePendientes(); return }

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
                                baseUrl = session.baseUrl(),
                                token = session.token,
                                wsUsername = session.wsUsername,
                                wsToken = session.wsToken,
                                dte = item.dtes.firstOrNull() ?: "",
                                caravanas = item.caravanas,
                                lat = item.lat,
                                lon = item.lon
                            ).ok
                        } else {
                            ApiClient.enviarCaravanas(
                                baseUrl = session.baseUrl(),
                                token = session.token,
                                dte = item.dtes.joinToString("|"),
                                caravanas = item.caravanas
                            ).ok
                        }
                    }.getOrElse { false }
                }
                if (ok) enviados++ else { fallidos++; pendingAun.add(item) }
            }

            // Reconstruir la cola con los que fallaron
            queue.clear()
            pendingAun.forEach { queue.add(it) }

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
        val devices = btManager.pairedDevices()
        if (devices.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sin dispositivos")
                .setMessage("Vinculá el lector RFID en Ajustes → Bluetooth y volvé a intentar.\n\nSi tu lector funciona en modo HID (teclado), no necesitás conectarlo aquí.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar lector SPP")
            .setItems(names) { _, idx ->
                btManager.connect(devices[idx])
                binding.tvBtEstado.text = "Conectando con ${names[idx]}..."
            }
            .setNegativeButton("Cancelar", null)
            .also {
                it.setMessage("Modo HID: si el lector está vinculado como teclado, no hace falta seleccionarlo aquí.")
            }
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
