package com.gestiontraza.app.ui.config

import android.os.Bundle
import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Boolean
import kotlin.Int

public class ConfigFragmentDirections private constructor() {
  private data class ActionConfigToConfig(
    public val agregarCuenta: Boolean = false,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_config_to_config

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putBoolean("agregarCuenta", this.agregarCuenta)
        return result
      }
  }

  public companion object {
    public fun actionConfigToHome(): NavDirections =
        ActionOnlyNavDirections(R.id.action_config_to_home)

    public fun actionConfigToTipoSesion(): NavDirections =
        ActionOnlyNavDirections(R.id.action_config_to_tipoSesion)

    public fun actionConfigToCuentas(): NavDirections =
        ActionOnlyNavDirections(R.id.action_config_to_cuentas)

    public fun actionConfigToConfig(agregarCuenta: Boolean = false): NavDirections =
        ActionConfigToConfig(agregarCuenta)
  }
}
