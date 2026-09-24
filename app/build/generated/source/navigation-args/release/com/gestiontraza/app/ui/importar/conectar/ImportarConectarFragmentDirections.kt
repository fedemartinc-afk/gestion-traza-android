package com.gestiontraza.app.ui.importar.conectar

import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R

public class ImportarConectarFragmentDirections private constructor() {
  public companion object {
    public fun actionImportarConectarToSesiones(): NavDirections =
        ActionOnlyNavDirections(R.id.action_importarConectar_to_sesiones)
  }
}
