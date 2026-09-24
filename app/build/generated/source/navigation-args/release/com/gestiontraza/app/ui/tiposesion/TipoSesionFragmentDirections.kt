package com.gestiontraza.app.ui.tiposesion

import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R

public class TipoSesionFragmentDirections private constructor() {
  public companion object {
    public fun actionTipoSesionToHome(): NavDirections =
        ActionOnlyNavDirections(R.id.action_tipoSesion_to_home)
  }
}
