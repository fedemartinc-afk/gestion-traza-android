package com.gestiontraza.app.ui.cuentas

import android.os.Bundle
import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Boolean
import kotlin.Int

public class CuentasFragmentDirections private constructor() {
  private data class ActionCuentasToConfig(
    public val agregarCuenta: Boolean = false,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_cuentas_to_config

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putBoolean("agregarCuenta", this.agregarCuenta)
        return result
      }
  }

  public companion object {
    public fun actionCuentasToConfig(agregarCuenta: Boolean = false): NavDirections =
        ActionCuentasToConfig(agregarCuenta)

    public fun actionCuentasToTipoSesion(): NavDirections =
        ActionOnlyNavDirections(R.id.action_cuentas_to_tipoSesion)
  }
}
