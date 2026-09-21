package com.gestiontraza.app.ui.registrar

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.gestiontraza.app.R
import com.gestiontraza.app.bluetooth.BtConnectionViewModel
import com.gestiontraza.app.data.ApiClient
import com.gestiontraza.app.data.SenasaClient
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentRegistrarBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegistrarDispositivosFragment : Fragment() {

    private var _binding: FragmentRegistrarBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager

    // Misma conexión SPP que se haya iniciado desde Inicio (u otra pantalla).
    private val btVm: BtConnectionViewModel by activityViewModels()
    private var lecturaActiva = false

    // Algunos lectores HID mandan el código con un prefijo o separador propio
    // (ej. el Gallagher antepone "LA"), asi que el texto crudo puede superar
    // los 15 caracteres de un código limpio. En vez de cortar apenas se llega
    // a esa longitud (lo que trunca el código a la mitad), se espera una
    // pausa real de tipeo antes de intentar agregarlo.
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var debounceManual: Runnable? = null
    private var debounceHid: Runnable? = null

    private val dispositivos = mutableListOf<DispositivoData>()
    private lateinit var adapter: DispositivoAdapter

    // Datos actuales para aplicar a cada escaneo
    private var sexoActual = "M"
    private var razaNombreActual = "SIN ESPECIFICAR"
    private var razaCodigoActual = "S/E"
    private var nacimientoActual = ""
    private var numeroLoteActual = "LOTE :"

    data class Raza(val nombre: String, val codigo: String)

    private val razas = listOf(
        Raza("HOLANDO ARGENTINO", "HA"), Raza("POLLED HEREFORD", "PH"), Raza("JERSEY", "J"),
        Raza("LIMANGUS", "LA"), Raza("SIMMENTAL", "FS"), Raza("SANTA GERTRUDIS", "SG"),
        Raza("OTRA RAZA", "OR"), Raza("LIMOUSINE", "L"), Raza("KIWI", "K"),
        Raza("BOSMARA", "BO"), Raza("SUECA ROJA Y BLANCA", "SRB"), Raza("SENANGUS", "SA"),
        Raza("BRAHMAN", "B"), Raza("SHORTHORN", "SH"), Raza("SENEPOL", "SP"),
        Raza("TULI", "TL"), Raza("SAN IGNACIO", "SI"), Raza("GANADO CRUZA", "GC"),
        Raza("HEREFORD", "H"), Raza("WAGYU", "W"), Raza("SENEFORD", "SF"),
        Raza("CHAROLAIS", "CH"), Raza("ABERDEEN ANGUS", "AA"), Raza("BRANGUS", "BG"),
        Raza("BRAFORD", "BF"), Raza("CRIOLLA", "CR"), Raza("MURRAY GREY", "MG"),
        Raza("GALLOWAY", "G"), Raza("MEDITERRANEA", "ME"), Raza("JAFARABADI", "JA"),
        Raza("MURRAH", "MU"), Raza("SIN ESPECIFICAR", "S/E")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentRegistrarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        session = SessionManager(requireContext())

        // Pre-cargar RENSPA guardado
        if (session.ultimoRenspa.isNotBlank()) {
            binding.etRenspa.setText(session.ultimoRenspa)
        }

        adapter = DispositivoAdapter(
            onEditar = { mostrarDialogoCambiarDatos() },
            onEliminar = { disp -> dispositivos.remove(disp); refreshAdapter() }
        )
        binding.recyclerDispositivos.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDispositivos.adapter = adapter

        // Panel manual visible solo si el usuario lo habilitó en Configuración
        binding.panelManual.visibility =
            if (session.manualCaravanas) View.VISIBLE else View.GONE

        setupRenspaAutoformat()
        setupTecladoToggle()
        setupTecladoToggleManual()
        setupHidInput()

        btVm.estado.observe(viewLifecycleOwner) { estado ->
            binding.tvLectorEstado.text = when (estado) {
                is BtConnectionViewModel.Estado.Conectado  -> "Lector SPP: ${estado.deviceName}"
                is BtConnectionViewModel.Estado.Conectando -> "Conectando con ${estado.deviceName}..."
                is BtConnectionViewModel.Estado.Error      -> estado.msg
                BtConnectionViewModel.Estado.Desconectado  -> "Lector desconectado"
            }
        }
        btVm.hayLineas.observe(viewLifecycleOwner) {
            btVm.drenarLineas().forEach { linea -> if (lecturaActiva) procesarCaravana(linea) }
        }

        binding.btnVolver.setOnClickListener { findNavController().navigateUp() }

        // Al presionar "Iniciar lectura" → primero pide datos, luego arranca
        binding.btnIniciarLectura.setOnClickListener {
            if (lecturaActiva) {
                detenerLectura()
            } else {
                val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
                if (renspa.length < 17) {
                    showToast("Ingresá el RENSPA completo (ej: 03.014.1.56885/23)")
                    return@setOnClickListener
                }
                // Guardar RENSPA para próximas sesiones
                session.ultimoRenspa = renspa
                // Mostrar popup de datos antes de iniciar
                mostrarDialogoInicioLectura(onConfirm = { iniciarLectura() })
            }
        }

        binding.btnCambiarDatos.setOnClickListener { mostrarDialogoCambiarDatos() }
        binding.btnDeclarar.setOnClickListener { declararEnSenasa() }
        setupManualInput()
    }

    // ── Dialogo inicial antes de empezar a leer ──────────────────────────────

    private fun mostrarDialogoInicioLectura(onConfirm: () -> Unit) {
        mostrarDialogoMetadata(
            titulo = "Datos para esta lectura",
            sexoInicial = sexoActual,
            razaNombreInicial = razaNombreActual,
            nacimientoInicial = nacimientoActual,
            mostrarAplicarATodas = true,
            mostrarNumero = true,
            numeroInicial = numeroLoteActual,
            onGuardar = { sexo, raza, nac, numero ->
                sexoActual = sexo
                razaNombreActual = raza.nombre
                razaCodigoActual = raza.codigo
                nacimientoActual = nac
                numeroLoteActual = numero
                actualizarPanelDatos()
                onConfirm()
            }
        )
    }

    // ── Dialogo para cambiar datos durante la lectura ────────────────────────

    private fun mostrarDialogoCambiarDatos() {
        mostrarDialogoMetadata(
            titulo = "Cambiar datos",
            sexoInicial = sexoActual,
            razaNombreInicial = razaNombreActual,
            nacimientoInicial = nacimientoActual,
            mostrarAplicarATodas = false,
            onGuardar = { sexo, raza, nac, _ ->
                sexoActual = sexo
                razaNombreActual = raza.nombre
                razaCodigoActual = raza.codigo
                nacimientoActual = nac
                actualizarPanelDatos()
            }
        )
    }

    // ── Constructor genérico del diálogo de metadata ─────────────────────────

    private fun mostrarDialogoMetadata(
        titulo: String,
        sexoInicial: String,
        razaNombreInicial: String,
        nacimientoInicial: String,
        mostrarAplicarATodas: Boolean,
        mostrarNumero: Boolean = false,
        numeroInicial: String = "",
        textBoton: String = "Comenzar",
        onGuardar: (sexo: String, raza: Raza, nac: String, numero: String) -> Unit
    ) {
        val razaNames = razas.map { it.nombre }.toTypedArray()
        val razaAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, razaNames)
        razaAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 24, 56, 8)
        }

        val tvSexo = android.widget.TextView(requireContext()).apply { text = "Sexo" }
        val rgSexo = android.widget.RadioGroup(requireContext()).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val rbMacho = android.widget.RadioButton(requireContext()).apply { text = "Macho (M)" }
        val rbHembra = android.widget.RadioButton(requireContext()).apply { text = "Hembra (H)" }
        rgSexo.addView(rbMacho)
        rgSexo.addView(rbHembra)
        if (sexoInicial == "H") rbHembra.isChecked = true else rbMacho.isChecked = true

        val tvRaza = android.widget.TextView(requireContext()).apply {
            text = "Raza"; setPadding(0, 16, 0, 4)
        }
        val spinnerRaza = android.widget.Spinner(requireContext())
        spinnerRaza.adapter = razaAdapter
        val razaIdx = razas.indexOfFirst { it.nombre == razaNombreInicial }.coerceAtLeast(razas.size - 1)
        spinnerRaza.setSelection(razaIdx)

        val tvNac = android.widget.TextView(requireContext()).apply {
            text = "Nacimiento (MM/AAAA)"; setPadding(0, 16, 0, 4)
        }
        val etNac = android.widget.EditText(requireContext()).apply {
            hint = "MM/AAAA"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(nacimientoInicial)
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        }
        etNac.addTextChangedListener(object : TextWatcher {
            var editing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return
                editing = true
                val digits = s?.filter { it.isDigit() }?.toString() ?: ""
                val formatted = if (digits.length > 2)
                    "${digits.substring(0, 2)}/${digits.substring(2, minOf(digits.length, 6))}"
                else digits
                etNac.setText(formatted)
                etNac.setSelection(formatted.length)
                editing = false
            }
        })

        val etNumero = android.widget.EditText(requireContext()).apply {
            setText(numeroInicial)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        layout.apply {
            addView(tvSexo)
            addView(rgSexo)
            addView(tvRaza)
            addView(spinnerRaza)
            addView(tvNac)
            addView(etNac)
            if (mostrarNumero) {
                val tvNumero = android.widget.TextView(requireContext()).apply {
                    text = "LOTE:"; setPadding(0, 16, 0, 4)
                }
                addView(tvNumero)
                addView(etNumero)
            }
            if (mostrarAplicarATodas) {
                val tvInfo = android.widget.TextView(requireContext()).apply {
                    text = "Estos datos se aplicarán a todas las caravanas escaneadas. Podés cambiarlos en cualquier momento durante la lectura."
                    textSize = 11f
                    setTextColor(requireContext().getColor(R.color.gris_texto))
                    setPadding(0, 16, 0, 0)
                }
                addView(tvInfo)
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titulo)
            .setView(layout)
            .setPositiveButton(textBoton, null)
            .setNegativeButton("Cancelar", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sexo = if (rbHembra.isChecked) "H" else "M"
            val razaSel = razas[spinnerRaza.selectedItemPosition]
            val nac = etNac.text?.toString()?.trim() ?: ""
            val numero = etNumero.text?.toString()?.trim()?.ifBlank { "LOTE :" } ?: "LOTE :"

            if (nac.isNotBlank()) {
                val parts = nac.split("/")
                val mes  = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val anio = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val invalida = parts.size != 2 || mes < 1 || mes > 12 || anio < 1
                val anteriorA2025 = anio < 2025 || (anio == 2025 && mes < 2)
                when {
                    invalida       -> { etNac.error = "Formato inválido (MM/AAAA)"; return@setOnClickListener }
                    anteriorA2025  -> { etNac.error = "La fecha debe ser posterior a 01/2025"; return@setOnClickListener }
                }
            }

            onGuardar(sexo, razaSel, nac, numero)
            dialog.dismiss()
        }
    }

    // ── Control de lectura ───────────────────────────────────────────────────

    private fun iniciarLectura() {
        lecturaActiva = true
        binding.btnIniciarLectura.text = "Detener lectura"
        binding.tvLectorEstado.text = "Lectura activa — escanear caravanas"
        binding.panelDatosActuales.visibility = View.VISIBLE
        actualizarPanelDatos()
        binding.etHidInput.isFocusableInTouchMode = true
        binding.etHidInput.requestFocus()
    }

    private fun detenerLectura() {
        lecturaActiva = false
        binding.btnIniciarLectura.text = "Iniciar lectura"
        binding.tvLectorEstado.text = "Lectura detenida"
        binding.panelDatosActuales.visibility = View.GONE
        binding.etHidInput.isFocusableInTouchMode = false
        binding.etHidInput.clearFocus()
    }

    private fun actualizarPanelDatos() {
        val nac = if (nacimientoActual.isBlank()) "—" else nacimientoActual
        val sexoLabel = if (sexoActual == "H") "Hembra" else "Macho"
        binding.tvDatosActuales.text = "$sexoLabel · $razaNombreActual · $nac"
    }

    // ── Procesamiento de cada caravana escaneada ─────────────────────────────

    /** Devuelve true si el texto era un código válido (se haya agregado o
     *  resultado duplicado) — false si todavía no forma un código completo,
     *  para no borrar el campo en ese caso. */
    private fun procesarCaravana(raw: String): Boolean {
        val codigo = normalizarCaravana(raw) ?: return false
        if (dispositivos.any { it.codigo == codigo }) {
            showToast("Duplicado: $codigo")
            return true
        }
        // Mostrar diálogo con datos actuales; la caravana se agrega solo al confirmar
        mostrarDialogoMetadata(
            titulo = "Caravana: $codigo",
            sexoInicial = sexoActual,
            razaNombreInicial = razaNombreActual,
            nacimientoInicial = nacimientoActual,
            mostrarAplicarATodas = false,
            textBoton = "Guardar",
            onGuardar = { sexo, raza, nac, _ ->
                // Actualizar defaults para las siguientes
                sexoActual = sexo
                razaNombreActual = raza.nombre
                razaCodigoActual = raza.codigo
                nacimientoActual = nac
                actualizarPanelDatos()
                // Agregar la caravana con los datos confirmados
                val especie = extraerEspecie(codigo)
                val nuevo = DispositivoData(codigo, especie, sexo, raza.nombre, raza.codigo, nac)
                dispositivos.add(0, nuevo)
                refreshAdapter()
                // Al cerrar el diálogo de confirmación, el campo manual queda
                // listo para cargar la próxima caravana sin tocarlo.
                if (session.manualCaravanas) binding.etManual.post { binding.etManual.requestFocus() }
            }
        )
        return true
    }

    // ── Declarar en SENASA ───────────────────────────────────────────────────

    private fun declararEnSenasa() {
        if (dispositivos.isEmpty()) { showToast("Sin dispositivos para declarar"); return }
        val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
        if (renspa.length < 17) { showToast("Ingresá el RENSPA completo"); return }
        if (session.wsUsername.isEmpty()) { showToast("Sin credenciales SENASA"); return }

        val especie = dispositivos.first().especie
        setResultLoading(true)
        lifecycleScope.launch {
            val items = dispositivos.map { d ->
                SenasaClient.DispositivoDeclaracion(d.codigo, d.sexo, d.razaCodigo, d.nacimiento)
            }
            val result = withContext(Dispatchers.IO) {
                SenasaClient.declararDispositivos(
                    SenasaClient.senasaBase(session.senasaEnv), session.wsUsername, session.wsToken,
                    renspa, especie, numeroLoteActual, items, null, null
                )
            }
            if (result.ok) {
                withContext(Dispatchers.IO) {
                    ApiClient.enviarAWeb(session.baseUrl(), session.token, renspa, dispositivos.map { it.codigo }, "declaracion")
                }
            }
            setResultLoading(false)
            showResultado(result.ok, result.message)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun refreshAdapter() {
        binding.tvContadorBar.text = "${dispositivos.size} dispositivos"
        adapter.submitList(dispositivos.toList())
    }

    private fun extraerEspecie(codigo: String): String =
        if (codigo.startsWith("032") && codigo.length >= 5) codigo.substring(3, 5) else "01"

    private fun normalizarCaravana(raw: String): String? {
        // Lectores SPP (AS420/RS420) anteponen "#" a cada código transmitido.
        val s = raw.trim().uppercase().replace(" ", "").replace("-", "").replace("\n", "").replace("\r", "").replace("#", "")
        // Algunos lectores en modo HID mandan texto extra antes o después del
        // código (ej. el Gallagher antepone "LA") — las letras no sirven acá,
        // así que se busca el patrón real de una caravana (032 + 12 dígitos)
        // en cualquier parte de lo que llegó y se descarta el resto.
        val real = Regex("032\\d{12}").find(s)
        if (real != null) return real.value
        if (s.matches(Regex("\\d{15}"))) return s
        // El código alternativo de 9 caracteres exige al menos una letra —
        // si no, un número de 9 dígitos tipeado a mitad de camino hacia los
        // 15 (con una pausa breve) se agregaría solo antes de terminar.
        if (s.matches(Regex("[A-Z0-9]{9}")) && s.any { it.isLetter() }) return s
        return null
    }

    private fun setupManualInput() {
        binding.btnAgregarManual.setOnClickListener { agregarManual() }

        // Si el ingreso manual esta habilitado, el cursor tiene que quedar
        // listo para escribir apenas se abre la pantalla, sin que el usuario
        // tenga que tocar el campo.
        if (session.manualCaravanas) {
            binding.etManual.post {
                binding.etManual.requestFocus()
                val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm.showSoftInput(binding.etManual, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // Con el campo manual visible el lector HID escribe aca en vez de en
        // etHidInput (le gana el foco): se aplica la misma captura automatica.
        // No se dispara apenas el texto llega a 15 caracteres — algunos
        // lectores (ej. Gallagher) mandan un prefijo de letras que hace que
        // el texto crudo sea mas largo, y cortar ahi trunca el codigo a la
        // mitad. En cambio se espera una pausa real de tipeo.
        binding.etManual.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                debounceManual?.let { debounceHandler.removeCallbacks(it) }
                if (text.isEmpty()) return
                if (text.contains('\n')) {
                    binding.etManual.setText(text.replace("\n", "").trim())
                    agregarManual()
                    return
                }
                // Código limpio de 15 dígitos (tipeo manual, sin prefijo de
                // lector): se agrega al instante, como siempre.
                if (text.matches(Regex("\\d{15}"))) {
                    agregarManual()
                    return
                }
                debounceManual = Runnable { agregarManual() }
                debounceHandler.postDelayed(debounceManual!!, 180)
            }
        })

        binding.etManual.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                debounceManual?.let { debounceHandler.removeCallbacks(it) }
                agregarManual(); true
            } else false
        }

        binding.etManual.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                agregarManual(); true
            } else false
        }
    }

    private fun agregarManual() {
        val codigo = binding.etManual.text?.toString()?.trim()?.uppercase() ?: ""
        if (codigo.isEmpty()) return
        if (!lecturaActiva) {
            val renspa = binding.etRenspa.text?.toString()?.trim() ?: ""
            if (renspa.length < 17) {
                showToast("Ingresá el RENSPA completo antes de agregar caravanas")
                return
            }
            session.ultimoRenspa = renspa
            mostrarDialogoInicioLectura(onConfirm = {
                iniciarLectura()
                if (procesarCaravana(codigo)) binding.etManual.setText("")
            })
        } else {
            if (procesarCaravana(codigo)) binding.etManual.setText("")
        }
    }

    private var tecladoManualQwerty = false

    private fun setupTecladoToggleManual() {
        val et = binding.etManual
        binding.btnToggleTecladoManual.setOnClickListener {
            tecladoManualQwerty = !tecladoManualQwerty
            et.inputType = if (tecladoManualQwerty)
                android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            binding.btnToggleTecladoManual.text = if (tecladoManualQwerty) "123" else "ABC"
            et.setSelection(et.text?.length ?: 0)
            et.post {
                et.requestFocus()
                val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun setupTecladoToggle() {
        var esNumerico = true
        binding.btnToggleTeclado.setOnClickListener {
            esNumerico = !esNumerico
            val et = binding.etRenspa
            val cursor = et.selectionEnd
            if (esNumerico) {
                et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                binding.btnToggleTeclado.text = "ABC"
            } else {
                et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                binding.btnToggleTeclado.text = "123"
            }
            // Reabrir teclado con el nuevo tipo
            et.setSelection(minOf(cursor, et.text?.length ?: 0))
            val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }
    }

    private fun setupRenspaAutoformat() {
        binding.etRenspa.addTextChangedListener(object : TextWatcher {
            var editing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return
                editing = true
                // Formato: 00.000.0.00000/00 → separadores en posición 2, 5, 6, 11
                val raw = s?.filter { it.isLetterOrDigit() }?.toString()?.uppercase() ?: ""
                val formatted = buildString {
                    raw.forEachIndexed { idx, c ->
                        when (idx) {
                            2  -> append('.')
                            5  -> append('.')
                            6  -> append('.')
                            11 -> append('/')
                        }
                        if (idx < 13) append(c)
                    }
                }
                binding.etRenspa.setText(formatted)
                binding.etRenspa.setSelection(formatted.length)
                editing = false
            }
        })
    }

    private fun setupHidInput() {
        val et = binding.etHidInput
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                debounceHid?.let { debounceHandler.removeCallbacks(it) }
                if (!lecturaActiva) { et.setText(""); return }
                if (text.isEmpty()) return
                if (text.contains('\n')) {
                    var agregado = false
                    text.split('\n').forEach { line ->
                        val clean = line.trim()
                        if (clean.isNotEmpty() && procesarCaravana(clean)) agregado = true
                    }
                    if (agregado) et.setText("")
                    return
                }
                // Código limpio de 15 dígitos (sin prefijo de lector): se
                // agrega al instante, sin esperar el debounce.
                if (text.matches(Regex("\\d{15}"))) {
                    if (procesarCaravana(text)) et.setText("")
                    return
                }
                // No se corta apenas el texto llega a 15 caracteres — algunos
                // lectores (ej. Gallagher) mandan un prefijo de letras y el
                // texto crudo termina siendo mas largo. Se espera una pausa
                // real de tipeo antes de intentar agregar lo que llegó. Si
                // todavía no forma un código completo, no se toca el campo.
                debounceHid = Runnable {
                    val clean = et.text?.toString()?.trim() ?: ""
                    if (clean.isNotEmpty() && procesarCaravana(clean)) et.setText("")
                }
                debounceHandler.postDelayed(debounceHid!!, 180)
            }
        })
        // Enter fisico del lector: no siempre inserta salto de linea ni dispara IME
        et.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN && lecturaActiva) {
                debounceHid?.let { debounceHandler.removeCallbacks(it) }
                val text = et.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty() && procesarCaravana(text)) et.setText("")
                true
            } else false
        }
        // Segun el lector, el Enter llega como DONE, NEXT o sin definir
        et.setOnEditorActionListener { _, actionId, _ ->
            if (lecturaActiva && (actionId == EditorInfo.IME_ACTION_DONE ||
                                  actionId == EditorInfo.IME_ACTION_NEXT ||
                                  actionId == EditorInfo.IME_ACTION_UNSPECIFIED)) {
                debounceHid?.let { debounceHandler.removeCallbacks(it) }
                val text = et.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty() && procesarCaravana(text)) et.setText("")
                true
            } else false
        }
    }

    private fun setResultLoading(on: Boolean) {
        binding.progressSend.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnDeclarar.isEnabled = !on
    }

    private fun showResultado(ok: Boolean, msg: String) {
        binding.tvResultado.visibility = View.VISIBLE
        binding.tvResultado.text = if (ok) "✓ $msg" else "✗ $msg"
        binding.tvResultado.setTextColor(requireContext().getColor(if (ok) R.color.verde_ok else R.color.rojo_error))
        binding.tvResultado.setBackgroundResource(if (ok) R.drawable.bg_resultado_ok else R.drawable.bg_resultado_error)
        if (ok) binding.root.postDelayed({
            if (isAdded) findNavController().popBackStack(R.id.homeFragment, false)
        }, 2500)
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        debounceHandler.removeCallbacksAndMessages(null)
        // La conexión SPP es compartida (BtConnectionViewModel): no se corta al
        // salir de esta pantalla, sigue disponible en Inicio y otros módulos.
        _binding = null
    }
}
