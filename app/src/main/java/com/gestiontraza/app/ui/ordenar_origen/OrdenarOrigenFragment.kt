package com.gestiontraza.app.ui.ordenar_origen

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
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentOrdenarOrigenBinding
import com.gestiontraza.app.databinding.ItemGrupoOrdenarBinding
import com.gestiontraza.app.ui.send.BarcodeScanActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class OrdenarOrigenFragment : Fragment() {

    private var _binding: FragmentOrdenarOrigenBinding? = null
    private val binding get() = _binding!!
    private val args: OrdenarOrigenFragmentArgs by navArgs()
    private lateinit var session: SessionManager
    private lateinit var caravanas: List<String>

    /** Datos de SENASA por caravana, consultados una vez al entrar a la pantalla. */
    private val datosCaravana = mutableMapOf<String, SenasaClient.EstadoCaravana>()

    /** true recién cuando terminó la consulta inicial de todas las caravanas —
     *  hasta entonces no se puede ordenar por origen, porque faltaría el RENSPA
     *  actual de las caravanas que todavía no se consultaron. */
    private var caravanasListas = false

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    private var gruposActuales: List<Grupo> = emptyList()
    private val gruposConResultado = mutableSetOf<String>()

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

    private data class Grupo(
        val dteNumero: String,
        val origenCodigo: String,
        val origenNombre: String,
        val caravanas: MutableList<String> = mutableListOf()
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentOrdenarOrigenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())
        val arr = JSONArray(args.caravanas)
        caravanas = (0 until arr.length()).map { arr.getString(it) }
        binding.tvContadorBar.text = "${caravanas.size} caravanas"

        if (!session.manualDte) {
            binding.etDte.keyListener = null
            binding.etDte.hint = "Escanear código de barras del DTe  📷"
        }

        // Los DTe ya cargados más arriba (pantalla de Enviar) se traen directo acá,
        // para no tener que volver a escribirlos: el primero va al campo principal
        // y el resto arma filas adicionales, igual que en Enviar.
        val dtesPrevios = args.dtes.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (dtesPrevios.isNotEmpty()) binding.etDte.setText(dtesPrevios.first())
        dtesPrevios.drop(1).forEach { addExtraDteRow(it) }

        binding.btnVolver.setOnClickListener { findNavController().navigateUp() }
        binding.btnEscanearDte.setOnClickListener {
            currentScanField = binding.etDte
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }
        setupTecladoToggleDte(binding.etDte, binding.btnToggleTecladoDte)
        binding.btnAgregarDte.setOnClickListener { addExtraDteRow() }
        binding.btnCargar.setOnClickListener { cargarDtesYOrdenar() }
        binding.btnEnviarWeb.setOnClickListener { enviarAWebSinVerificar() }
        binding.btnReintentarFallidas.setOnClickListener {
            lifecycleScope.launch { reintentarFallidas() }
        }
        binding.btnVolverInicio.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }

        fetchLocation()
    }

    /** Consulta una caravana en SENASA, reintentando hasta 10 veces si falla. */
    private suspend fun consultarConReintentos(base: String, cod: String, progreso: (Int) -> Unit): SenasaClient.EstadoCaravana {
        var estado: SenasaClient.EstadoCaravana
        var intento = 1
        while (true) {
            withContext(Dispatchers.Main) { progreso(intento) }
            estado = withContext(Dispatchers.IO) {
                SenasaClient.consultarCaravana(base, session.wsUsername, session.wsToken, cod)
            }
            if (estado.ok || intento >= 10) break
            intento++
            delay(400)
        }
        return estado
    }

    /** Consulta cada caravana leída una sola vez — hace falta su RENSPA actual para poder
     *  compararla con el origen de cada DTe, y de paso alimenta los contadores globales. */
    private suspend fun consultarDatosCaravanas() {
        if (session.wsUsername.isEmpty()) { showToast("Sin credenciales SENASA"); return }
        binding.progressCarga.visibility = View.VISIBLE
        binding.tvProgreso.visibility = View.VISIBLE
        val base = SenasaClient.senasaBase(session.senasaEnv)
        caravanas.forEachIndexed { idx, cod ->
            datosCaravana[cod] = consultarConReintentos(base, cod) { intento ->
                binding.tvProgreso.text = if (intento == 1)
                    "Consultando datos ${idx + 1} de ${caravanas.size}…"
                else
                    "Consultando datos ${idx + 1} de ${caravanas.size}… (reintento $intento de 10)"
            }
        }
        binding.progressCarga.visibility = View.GONE
        binding.tvProgreso.visibility = View.GONE
        caravanasListas = true
        actualizarContadoresGlobales()
        actualizarPanelFallidas()

        val exitosas = caravanas.filter { datosCaravana[it]?.ok == true }
        withContext(Dispatchers.IO) {
            ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
        }
    }

    /** Reintenta manualmente solo las caravanas que quedaron sin datos tras los 10 intentos automáticos. */
    private suspend fun reintentarFallidas() {
        val fallidas = caravanas.filter { datosCaravana[it]?.ok != true }
        if (fallidas.isEmpty()) { actualizarPanelFallidas(); return }

        binding.btnReintentarFallidas.isEnabled = false
        binding.progressCarga.visibility = View.VISIBLE
        binding.tvProgreso.visibility = View.VISIBLE
        val base = SenasaClient.senasaBase(session.senasaEnv)
        fallidas.forEachIndexed { idx, cod ->
            datosCaravana[cod] = consultarConReintentos(base, cod) { intento ->
                binding.tvProgreso.text = if (intento == 1)
                    "Reintentando ${idx + 1} de ${fallidas.size}…"
                else
                    "Reintentando ${idx + 1} de ${fallidas.size}… (intento $intento de 10)"
            }
        }
        binding.progressCarga.visibility = View.GONE
        binding.tvProgreso.visibility = View.GONE
        binding.btnReintentarFallidas.isEnabled = true
        actualizarContadoresGlobales()
        actualizarPanelFallidas()

        val exitosas = fallidas.filter { datosCaravana[it]?.ok == true }
        withContext(Dispatchers.IO) {
            ApiClient.registrarConsultas(session.baseUrl(), session.token, exitosas)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun actualizarPanelFallidas() {
        val fallidas = datosCaravana.values.count { !it.ok }
        if (fallidas > 0) {
            binding.panelFallidas.visibility = View.VISIBLE
            binding.tvFallidasInfo.text = "⚠ $fallidas caravana${if (fallidas != 1) "s" else ""} no se pudo consultar"
        } else {
            binding.panelFallidas.visibility = View.GONE
        }
    }

    /** Entidades (RENSPA/feria) distintas y desglose de sexo, sobre todas las caravanas leídas. */
    @SuppressLint("SetTextI18n")
    private fun actualizarContadoresGlobales() {
        val conDatos = datosCaravana.values.filter { it.ok }
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
        binding.panelContadores.visibility = if (datosCaravana.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** DTe cargados: el campo principal más las filas adicionales agregadas con "+ Agregar otro DTe". */
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
        return result.distinct()
    }

    /** Teclado numérico por defecto para DTe (son números), con botón para
     *  pasar a QWERTY si hace falta cargar un código alfanumérico. */
    private fun setupTecladoToggleDte(et: TextInputEditText, btnToggle: MaterialButton) {
        var qwerty = false
        btnToggle.setOnClickListener {
            qwerty = !qwerty
            et.inputType = if (qwerty)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            btnToggle.text = if (qwerty) "123" else "ABC"
            et.setSelection(et.text?.length ?: 0)
            et.post {
                et.requestFocus()
                val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun addExtraDteRow(prefill: String = "") {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_dte_row, binding.llExtraDtes, false)

        val et        = row.findViewById<TextInputEditText>(R.id.etDteExtra)
        val btnToggle = row.findViewById<MaterialButton>(R.id.btnToggleTecladoDteExtra)
        val btnScan   = row.findViewById<MaterialButton>(R.id.btnScanDteExtra)
        val btnDelete = row.findViewById<MaterialButton>(R.id.btnDeleteDteExtra)

        if (prefill.isNotEmpty()) et.setText(prefill)

        if (!session.manualDte) {
            et.keyListener = null
            et.hint = "Escanear  📷"
        }

        setupTecladoToggleDte(et, btnToggle)

        btnScan.setOnClickListener {
            currentScanField = et
            barcodeResultLauncher.launch(BarcodeScanActivity.newIntent(requireContext()))
        }
        btnDelete.setOnClickListener { binding.llExtraDtes.removeView(row) }

        binding.llExtraDtes.addView(row)
    }

    private fun cargarDtesYOrdenar() {
        val dtes = getDteValues()
        if (dtes.isEmpty()) { showToast("Ingresá al menos un número de DT-e"); return }
        if (session.wsUsername.isEmpty()) { showToast("Sin credenciales SENASA"); return }

        binding.btnCargar.isEnabled = false
        binding.emptyState.visibility = View.GONE

        lifecycleScope.launch {
            // Recién acá se consulta cada caravana contra SENASA — no al entrar
            // a la pantalla — porque "Ordenar por Origen" no debe verificar nada
            // hasta que se confirme con este botón.
            if (!caravanasListas) consultarDatosCaravanas()

            val base = SenasaClient.senasaBase(session.senasaEnv)
            binding.progressCarga.visibility = View.VISIBLE
            binding.tvProgreso.visibility = View.VISIBLE
            val grupos = mutableListOf<Grupo>()
            dtes.forEachIndexed { idx, dteNum ->
                withContext(Dispatchers.Main) {
                    binding.tvProgreso.text = "Consultando DTe ${idx + 1} de ${dtes.size}…"
                }
                val (ok, info) = withContext(Dispatchers.IO) {
                    SenasaClient.consultarDte(base, session.wsUsername, session.wsToken, dteNum)
                }
                if (ok && info != null) {
                    grupos.add(Grupo(dteNum, info.origenCodigo, info.origenNombre))
                }
            }

            val sinCoincidencia = mutableListOf<String>()
            caravanas.forEach { cod ->
                val renspa = datosCaravana[cod]?.renspaActual?.trim()?.uppercase() ?: ""
                val grupo = if (renspa.isNotEmpty())
                    grupos.firstOrNull { it.origenCodigo.trim().uppercase() == renspa }
                else null
                if (grupo != null) grupo.caravanas.add(cod) else sinCoincidencia.add(cod)
            }

            binding.progressCarga.visibility = View.GONE
            binding.tvProgreso.visibility = View.GONE
            binding.btnCargar.isEnabled = true

            if (grupos.isEmpty()) {
                showToast("No se pudo consultar ningún DTe válido")
                binding.emptyState.visibility = View.VISIBLE
                return@launch
            }
            if (dtes.size > grupos.size) {
                showToast("${dtes.size - grupos.size} DTe(s) no se pudieron consultar")
            }

            mostrarGrupos(grupos, sinCoincidencia)
        }
    }

    /** Manda el listado de caravanas y los DTe cargados directo a la web, sin
     *  consultar ni verificar nada contra SENASA — para cuando no hace falta
     *  que la app ordene, solo avisarle al usuario web. */
    private fun enviarAWebSinVerificar() {
        val dtes = getDteValues()

        fun enviar() {
            if (!isOnline()) {
                PendingQueue(requireContext()).add(
                    PendingQueue.PendingItem(
                        tipo = "web",
                        dtes = dtes,
                        caravanas = caravanas,
                        lat = null,
                        lon = null,
                        cuentaId = session.cuentaActivaId
                    )
                )
                mostrarResultadoEnvio(true, "Sin conexión — ${caravanas.size} caravanas guardadas como pendiente")
                return
            }

            binding.btnEnviarWeb.isEnabled = false
            binding.btnCargar.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.enviarAWeb(
                        baseUrl   = session.baseUrl(),
                        token     = session.token,
                        dte       = dtes.joinToString("|"),
                        caravanas = caravanas,
                        tipo      = "verificacion",
                        extra     = mapOf("titulo" to "Ordenar por Origen")
                    )
                }
                binding.btnCargar.isEnabled = true
                mostrarResultadoEnvio(result.ok, result.message)
            }
        }

        if (dtes.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sin número de DTe")
                .setMessage("Está por enviar una lectura de caravanas sin informar DTe. ¿Desea continuar?")
                .setPositiveButton("Sí, continuar") { _, _ -> enviar() }
                .setNegativeButton("No, volver", null)
                .show()
        } else {
            enviar()
        }
    }

    private fun mostrarResultadoEnvio(ok: Boolean, msg: String) {
        binding.tvResultadoEnvio.visibility = View.VISIBLE
        binding.tvResultadoEnvio.text = "${if (ok) "✓" else "✗"} $msg\n${caravanas.size} caravanas"
        binding.tvResultadoEnvio.setTextColor(requireContext().getColor(if (ok) R.color.verde_ok else R.color.rojo_error))
        binding.tvResultadoEnvio.setBackgroundResource(if (ok) R.drawable.bg_resultado_ok else R.drawable.bg_resultado_error)
        if (ok) {
            binding.btnEnviarWeb.isEnabled = false
            binding.root.postDelayed({
                if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
            }, 2500)
        } else {
            binding.btnEnviarWeb.isEnabled = true
        }
    }

    private fun isOnline(): Boolean {
        val cm = requireContext().getSystemService(ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }

    private fun mostrarGrupos(grupos: List<Grupo>, sinCoincidencia: List<String>) {
        binding.llGrupos.removeAllViews()
        grupos.forEach { g -> binding.llGrupos.addView(crearCardGrupo(g)) }

        if (sinCoincidencia.isNotEmpty()) {
            binding.panelSinCoincidencia.visibility = View.VISIBLE
            binding.tvSinCoincidenciaTitulo.text = "Sin coincidencia de origen (${sinCoincidencia.size})"
            binding.tvSinCoincidenciaLista.text = sinCoincidencia.joinToString("\n")
        } else {
            binding.panelSinCoincidencia.visibility = View.GONE
        }

        gruposActuales = grupos
        gruposConResultado.clear()
        actualizarBotonVolver()
    }

    /** Destaca "Volver al inicio" una vez que todos los grupos con caravanas ya tienen
     *  resultado de envío (exitoso o no) — sin bloquear al usuario si prefiere salir antes. */
    private fun actualizarBotonVolver() {
        val gruposEnviables = gruposActuales.count { it.caravanas.isNotEmpty() }
        val listo = gruposEnviables > 0 && gruposConResultado.size >= gruposEnviables
        val ctx = requireContext()
        if (listo) {
            binding.btnVolverInicio.text = "✓ Volver al inicio"
            binding.btnVolverInicio.backgroundTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.verde_ok))
            binding.btnVolverInicio.setTextColor(ctx.getColor(R.color.blanco))
        } else {
            binding.btnVolverInicio.text = "Volver al inicio"
            binding.btnVolverInicio.backgroundTintList = null
            binding.btnVolverInicio.setTextColor(ctx.getColor(R.color.verde_ok))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun crearCardGrupo(g: Grupo): View {
        val item = ItemGrupoOrdenarBinding.inflate(layoutInflater, binding.llGrupos, false)

        item.tvDteNumero.text = "DTe ${g.dteNumero}"
        item.tvOrigenInfo.text = if (g.origenNombre.isNotBlank())
            "${g.origenNombre} · ${g.origenCodigo}" else g.origenCodigo
        item.tvCantidadGrupo.text = "${g.caravanas.size} caravana${if (g.caravanas.size != 1) "s" else ""}"

        // Listado colapsado por defecto (primeras 5) con opción de ver todo.
        val maxColapsado = 5
        var expandido = false
        fun actualizarListado() {
            item.tvCaravanasGrupo.text = when {
                g.caravanas.isEmpty() -> "Sin caravanas coincidentes"
                expandido || g.caravanas.size <= maxColapsado -> g.caravanas.joinToString("\n")
                else -> g.caravanas.take(maxColapsado).joinToString("\n") + "\n…"
            }
            item.tvVerMas.visibility = if (g.caravanas.size > maxColapsado) View.VISIBLE else View.GONE
            item.tvVerMas.text = if (expandido) "▲ Ver menos" else "▼ Ver listado completo (${g.caravanas.size})"
        }
        actualizarListado()
        item.tvVerMas.setOnClickListener {
            expandido = !expandido
            actualizarListado()
        }

        var machos = 0; var hembras = 0; var sinSexo = 0
        g.caravanas.forEach { cod ->
            val s = datosCaravana[cod]?.sexo?.trim()?.uppercase() ?: ""
            when {
                s == "M" || s == "MACHO"  -> machos++
                s == "H" || s == "HEMBRA" -> hembras++
                else                      -> sinSexo++
            }
        }
        item.tvSexoGrupo.text = "Macho $machos   Hembra $hembras   s/sexo $sinSexo"

        item.btnEnviarGrupo.isEnabled = g.caravanas.isNotEmpty()
        item.btnEnviarGrupo.setOnClickListener { enviarGrupo(g, item) }

        return item.root
    }

    private fun enviarGrupo(g: Grupo, item: ItemGrupoOrdenarBinding) {
        if (lastLat == null || lastLon == null) {
            showToast("Falta la ubicación — esperá a que se obtenga el GPS antes de enviar")
            return
        }
        item.btnEnviarGrupo.isEnabled = false
        item.progressGrupo.visibility = View.VISIBLE
        item.tvResultadoGrupo.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.enviarCierre(
                    baseUrl    = session.baseUrl(),
                    token      = session.token,
                    wsUsername = session.wsUsername,
                    wsToken    = session.wsToken,
                    dte        = g.dteNumero,
                    caravanas  = g.caravanas,
                    lat        = lastLat,
                    lon        = lastLon,
                    senasaEnv  = session.senasaEnv
                )
            }
            item.progressGrupo.visibility = View.GONE
            item.tvResultadoGrupo.visibility = View.VISIBLE
            if (result.ok) {
                item.tvResultadoGrupo.text = "✓ ${result.message}"
                item.tvResultadoGrupo.setTextColor(requireContext().getColor(R.color.verde_ok))
                item.tvResultadoGrupo.setBackgroundResource(R.drawable.bg_resultado_ok)
            } else {
                item.btnEnviarGrupo.isEnabled = true
                item.tvResultadoGrupo.text = "✗ ${result.message}"
                item.tvResultadoGrupo.setTextColor(requireContext().getColor(R.color.rojo_error))
                item.tvResultadoGrupo.setBackgroundResource(R.drawable.bg_resultado_error)
            }
            gruposConResultado.add(g.dteNumero)
            actualizarBotonVolver()
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

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
