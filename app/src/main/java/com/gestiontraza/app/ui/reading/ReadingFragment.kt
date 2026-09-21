package com.gestiontraza.app.ui.reading

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gestiontraza.app.R
import com.gestiontraza.app.bluetooth.BtConnectionViewModel
import com.gestiontraza.app.data.SessionManager
import com.gestiontraza.app.databinding.FragmentReadingBinding
import org.json.JSONArray

class ReadingFragment : Fragment() {

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val vm: ReadingViewModel by viewModels()
    private val args: ReadingFragmentArgs by navArgs()
    // Misma conexión SPP que se haya iniciado desde Inicio (u otra pantalla).
    private val btVm: BtConnectionViewModel by activityViewModels()

    private lateinit var adapter: CaravanaAdapter
    private lateinit var session: SessionManager

    // Algunos lectores HID mandan el código con un prefijo o separador propio
    // (ej. el Gallagher antepone "LA"), asi que el texto crudo puede superar
    // los 15 caracteres de un código limpio. En vez de cortar apenas se llega
    // a esa longitud (lo que trunca el código a la mitad), se espera una
    // pausa real de tipeo antes de intentar agregarlo.
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var debounceManual: Runnable? = null
    private var debounceHid: Runnable? = null
    private var tecladoQwerty = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        session = SessionManager(requireContext())

        // El perfil productor entra con un módulo concreto; el consignatario, sin modo.
        if (args.titulo.isNotBlank()) binding.tvTitulo.text = args.titulo

        binding.btnBack.setOnClickListener { volverAtras() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { volverAtras() }

        adapter = CaravanaAdapter()
        binding.recyclerCaravanas.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCaravanas.adapter = adapter

        // Ocultar entrada manual si el ajuste no está activo
        if (!session.manualCaravanas) {
            binding.panelManualCaravanas.visibility = View.GONE
        }

        // Ícono de estado del lector: gris hasta que llega la primera lectura
        // válida (sea por HID o por SPP), verde de ahí en adelante. No hay forma
        // confiable de detectar "conectado" en HID (el sistema lo trata como un
        // teclado más), así que en vez de eso se usa la primera lectura como señal.
        binding.ivBtStatus.setColorFilter(ContextCompat.getColor(requireContext(), R.color.gris_texto))

        vm.caravanas.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val validas = vm.totalValidas()
            val dups = list.count { it.esDuplicado }
            binding.tvContador.text = if (dups > 0) "$validas  •  $dups dup." else "$validas"
            binding.recyclerCaravanas.scrollToPosition(0)
            if (list.isNotEmpty()) {
                binding.ivBtStatus.setColorFilter(ContextCompat.getColor(requireContext(), R.color.verde_ok))
            }
        }

        // Modo HID: EditText oculto que recibe input del lector (emulación teclado)
        setupHidInput()

        // Entrada manual visible
        setupManualInput()

        // Modo SPP: la conexión es la misma que se haya iniciado desde Inicio,
        // compartida vía BtConnectionViewModel — antes cada pantalla armaba su
        // propio BtManager y la conexión de Inicio no llegaba hasta acá.
        // hayLineas es "sticky": si hubo lecturas antes de entrar a esta pantalla
        // (p. ej. mientras se estaba en Inicio), se drenan apenas se observa.
        btVm.hayLineas.observe(viewLifecycleOwner) {
            btVm.drenarLineas().forEach { linea -> vm.addRaw(linea) }
        }

        binding.btnFinalizar.setOnClickListener {
            val validas = vm.validas()
            if (validas.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "No hay caravanas válidas", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val duplicadas = vm.duplicadas()
            if (duplicadas.isNotEmpty()) {
                val lista = duplicadas.joinToString("\n• ", prefix = "• ")
                val msg = if (duplicadas.size == 1)
                    "La siguiente caravana está duplicada y no se enviará:\n\n$lista\n\n¿Deseás continuar o volver a revisar?"
                else
                    "Las siguientes ${duplicadas.size} caravanas están duplicadas y no se enviarán:\n\n$lista\n\n¿Deseás continuar o volver a revisar?"
                AlertDialog.Builder(requireContext())
                    .setTitle("Caravanas duplicadas")
                    .setMessage(msg)
                    .setPositiveButton("Continuar") { _, _ -> navegarSegunModo(validas) }
                    .setNegativeButton("Revisar", null)
                    .show()
            } else {
                navegarSegunModo(validas)
            }
        }
    }

    /**
     * Sin modo (perfil consignatario) la lectura sigue al envío de DT-e.
     * Con modo (perfil productor) va al módulo elegido en el inicio.
     */
    private fun navegarSegunModo(validas: List<String>) {
        val json = JSONArray(validas).toString()
        // Se limpia acá, ya con el snapshot de esta tanda capturado en `json`.
        // Esta pantalla queda en el back stack (no hay popUpTo en las acciones
        // de navegación), así que si el usuario vuelve sin haber enviado nada
        // y sigue leyendo, no se deben arrastrar las caravanas de la tanda anterior.
        vm.reset()
        val nav  = findNavController()
        when (args.mode) {
            "verificar_origen"   -> nav.navigate(ReadingFragmentDirections.actionReadingToVerificarOrigen(json))
            "comparar_tri"       -> nav.navigate(ReadingFragmentDirections.actionReadingToCompararTri(json, ""))
            "estado_tri"         -> nav.navigate(ReadingFragmentDirections.actionReadingToEstadoTri(json))
            "estado_predespacho" -> nav.navigate(ReadingFragmentDirections.actionReadingToEstadoPredespacho(json))
            "ubicacion_actual"   -> nav.navigate(ReadingFragmentDirections.actionReadingToUbicacionActual(json))
            "ordenar_origen"     -> nav.navigate(ReadingFragmentDirections.actionReadingToOrdenarOrigen(json))
            // Sin modo: el consignatario sigue directo al envío; el productor
            // elige qué hacer con la lectura recién hecha en el hub.
            else -> if (session.tipoSesionActual == "productor")
                nav.navigate(ReadingFragmentDirections.actionReadingToHub(json))
            else
                nav.navigate(ReadingFragmentDirections.actionReadingToSend(json))
        }
    }

    private fun volverAtras() {
        if (vm.validas().isEmpty()) {
            findNavController().navigateUp()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Salir de la lectura")
            .setMessage("Tenés ${vm.validas().size} caravana(s) leída(s) sin enviar. Si salís se pierden.")
            .setPositiveButton("Salir igual") { _, _ -> findNavController().navigateUp() }
            .setNegativeButton("Seguir leyendo", null)
            .show()
    }

    /**
     * Agrega un bloque de texto pegado o tipeado al listado. Soporta códigos
     * separados por salto de línea o por espacios (p. ej. pegado desde un
     * mensaje de texto), y también un pegado sin separadores donde varias
     * caravanas de 15 dígitos quedan concatenadas en un solo string (p. ej.
     * copiado desde una planilla en una sola celda) — en ese caso se parte
     * en bloques de 15.
     */
    /** Devuelve true si al menos un código válido se agregó — para no borrar el
     *  campo cuando lo tipeado/leído todavía no forma un código completo. */
    private fun agregarListado(text: String): Boolean = vm.addTexto(text)

    private fun setupManualInput() {
        val et = binding.etManual

        // Si el ingreso manual está habilitado, el cursor tiene que quedar
        // listo para escribir apenas se abre la pantalla, sin que el usuario
        // tenga que tocar el campo — le gana el foco a etHidInput a propósito.
        if (session.manualCaravanas) {
            et.post {
                et.requestFocus()
                val imm = requireContext().getSystemService(InputMethodManager::class.java)
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        binding.btnAgregar.setOnClickListener {
            val codigo = et.text?.toString()?.trim() ?: ""
            if (codigo.isEmpty()) return@setOnClickListener
            if (vm.addRaw(codigo)) et.setText("")
            // Ocultar teclado y devolver foco al HID
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(et.windowToken, 0)
        }

        // Teclado numérico por defecto (los códigos son dígitos); botón para
        // pasar a QWERTY cuando hace falta cargar un código alfanumérico.
        binding.btnToggleTeclado.setOnClickListener {
            tecladoQwerty = !tecladoQwerty
            et.inputType = if (tecladoQwerty)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            else
                android.text.InputType.TYPE_CLASS_NUMBER
            binding.btnToggleTeclado.text = if (tecladoQwerty) "123" else "ABC"
            et.setSelection(et.text?.length ?: 0)
            et.post {
                et.requestFocus()
                val imm = requireContext().getSystemService(InputMethodManager::class.java)
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // Con el panel manual visible, el lector HID escribe acá en vez de en
        // etHidInput (le gana el foco). Se aplica la misma captura que el campo
        // oculto para que la lectura se agregue sola, esté donde esté el foco.
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                debounceManual?.let { debounceHandler.removeCallbacks(it) }
                if (text.isEmpty()) return
                if (text.contains('\n')) {
                    if (agregarListado(text)) et.setText("")
                    et.post { et.requestFocus() }
                    return
                }
                // Código limpio de 15 dígitos (tipeo manual, sin prefijo de
                // lector): se agrega al instante, como siempre.
                if (text.matches(Regex("\\d{15}"))) {
                    if (agregarListado(text)) et.setText("")
                    et.post { et.requestFocus() }
                    return
                }
                // Si todavía no forma un código completo (sea porque la persona
                // sigue tipeando, o el lector no terminó de mandar todo), no se
                // toca el campo — se espera una pausa real antes de intentarlo,
                // y si falla se deja el texto como está en vez de borrarlo.
                debounceManual = Runnable {
                    if (agregarListado(et.text?.toString() ?: "")) et.setText("")
                    et.post { et.requestFocus() }
                }
                debounceHandler.postDelayed(debounceManual!!, 180)
            }
        })

        // Enter físico del lector
        et.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                debounceManual?.let { debounceHandler.removeCallbacks(it) }
                binding.btnAgregar.performClick()
                true
            } else false
        }

        // Cualquier acción del IME agrega: según el lector llega como DONE, NEXT o sin definir
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                debounceManual?.let { debounceHandler.removeCallbacks(it) }
                binding.btnAgregar.performClick()
                true
            } else false
        }
    }

    private fun setupHidInput() {
        val et = binding.etHidInput
        et.requestFocus()

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                debounceHid?.let { debounceHandler.removeCallbacks(it) }
                if (text.isEmpty()) return
                if (text.contains('\n')) {
                    if (agregarListado(text)) et.setText("")
                    et.post { et.requestFocus() }
                    return
                }
                // Código limpio de 15 dígitos (sin prefijo de lector): se
                // agrega al instante, sin esperar el debounce.
                if (text.matches(Regex("\\d{15}"))) {
                    if (agregarListado(text)) et.setText("")
                    et.post { et.requestFocus() }
                    return
                }
                // Si todavía no forma un código completo, no se toca el campo
                // — se espera una pausa real, y si falla se deja como está.
                debounceHid = Runnable {
                    if (agregarListado(et.text?.toString() ?: "")) et.setText("")
                    et.post { et.requestFocus() }
                }
                debounceHandler.postDelayed(debounceHid!!, 180)
            }
        })

        // Captura el Enter físico del lector BT antes de que llegue a otros views
        et.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                debounceHid?.let { debounceHandler.removeCallbacks(it) }
                val text = et.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    if (vm.addRaw(text)) et.setText("")
                }
                et.post { et.requestFocus() }
                true // consumir — no propagar a btnFinalizar
            } else false
        }

        // Retorna true siempre para evitar que cualquier acción IME se propague
        et.setOnEditorActionListener { _, _, _ ->
            debounceHid?.let { debounceHandler.removeCallbacks(it) }
            val text = et.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                if (vm.addRaw(text)) et.setText("")
            }
            et.post { et.requestFocus() }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        binding.etHidInput.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        debounceHandler.removeCallbacksAndMessages(null)
        // La conexión SPP es compartida (BtConnectionViewModel): no se corta al
        // salir de esta pantalla, sigue disponible en Inicio y otros módulos.
        _binding = null
    }
}
