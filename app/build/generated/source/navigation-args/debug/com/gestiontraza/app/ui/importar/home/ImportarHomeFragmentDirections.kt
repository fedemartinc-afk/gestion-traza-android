package com.gestiontraza.app.ui.importar.home

import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R

public class ImportarHomeFragmentDirections private constructor() {
  public companion object {
    public fun actionImportarHomeToConectar(): NavDirections =
        ActionOnlyNavDirections(R.id.action_importarHome_to_conectar)

    public fun actionImportarHomeToSesiones(): NavDirections =
        ActionOnlyNavDirections(R.id.action_importarHome_to_sesiones)

    public fun actionImportarHomeToLector(): NavDirections =
        ActionOnlyNavDirections(R.id.action_importarHome_to_lector)
  }
}
