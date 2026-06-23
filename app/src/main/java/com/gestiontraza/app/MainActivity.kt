package com.gestiontraza.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var btAdapter: BluetoothAdapter? = null

    // Lanzador para el diálogo nativo "¿Activar Bluetooth?"
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkLocationEnabled()
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            val nombres = denied.map { permNombre(it) }.distinct().joinToString(", ")
            AlertDialog.Builder(this)
                .setTitle("Permisos denegados")
                .setMessage("La app necesita: $nombres.\n\nAlgunas funciones no estarán disponibles. Podés habilitarlos desde Ajustes → Aplicaciones → Gestión Traza → Permisos.")
                .setPositiveButton("Entendido", null)
                .show()
        }
        setupNav()
        checkServicesEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        val faltantes = permisosNecesarios().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (faltantes.isEmpty()) {
            setupNav()
            checkServicesEnabled()
        } else {
            val necesitaExplicacion = faltantes.any { shouldShowRequestPermissionRationale(it) }
            if (necesitaExplicacion) {
                AlertDialog.Builder(this)
                    .setTitle("Permisos necesarios")
                    .setMessage("Esta app necesita los siguientes permisos para funcionar:\n\n• Bluetooth — conectar lectores RFID\n• Ubicación — requerida por Android para Bluetooth\n• Cámara — escanear códigos QR y DTe")
                    .setPositiveButton("Continuar") { _, _ -> permLauncher.launch(faltantes.toTypedArray()) }
                    .setCancelable(false)
                    .show()
            } else {
                permLauncher.launch(faltantes.toTypedArray())
            }
        }
    }

    private fun checkServicesEnabled() {
        checkBluetoothEnabled()
    }

    private fun checkBluetoothEnabled() {
        val adapter = btAdapter ?: return
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            checkLocationEnabled()
        }
    }

    private fun checkLocationEnabled() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val activa = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                     lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!activa) {
            AlertDialog.Builder(this)
                .setTitle("Ubicación desactivada")
                .setMessage("Android requiere que la Ubicación esté activada para usar Bluetooth con lectores RFID. Activala en Ajustes.")
                .setPositiveButton("Ir a Ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Omitir", null)
                .show()
        }
    }

    private fun setupNav() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.setStartDestination(
            if (SessionManager(this).isConfigured()) R.id.homeFragment else R.id.configFragment
        )
        navController.graph = navGraph
    }

    private fun permisosNecesarios(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun permNombre(perm: String) = when {
        perm.contains("BLUETOOTH") -> "Bluetooth"
        perm.contains("LOCATION")  -> "Ubicación"
        perm.contains("CAMERA")    -> "Cámara"
        else -> perm.substringAfterLast(".")
    }
}
