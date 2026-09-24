package com.gestiontraza.app.ui.importar.lector

import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R

public class ImportarLectorFragmentDirections private constructor() {
  public companion object {
    public fun actionImportarLectorToSesiones(): NavDirections =
        ActionOnlyNavDirections(R.id.action_importarLector_to_sesiones)
  }
}
