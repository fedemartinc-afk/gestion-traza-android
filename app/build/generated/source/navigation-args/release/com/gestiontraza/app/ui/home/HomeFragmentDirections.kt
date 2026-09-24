package com.gestiontraza.app.ui.home

import android.os.Bundle
import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Boolean
import kotlin.Int
import kotlin.String

public class HomeFragmentDirections private constructor() {
  private data class ActionHomeToReading(
    public val mode: String = "",
    public val titulo: String = "",
  ) : NavDirections {
    public override val actionId: Int = R.id.action_home_to_reading

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("mode", this.mode)
        result.putString("titulo", this.titulo)
        return result
      }
  }

  private data class ActionHomeToConfig(
    public val agregarCuenta: Boolean = false,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_home_to_config

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putBoolean("agregarCuenta", this.agregarCuenta)
        return result
      }
  }

  public companion object {
    public fun actionHomeToReading(mode: String = "", titulo: String = ""): NavDirections =
        ActionHomeToReading(mode, titulo)

    public fun actionHomeToConfig(agregarCuenta: Boolean = false): NavDirections =
        ActionHomeToConfig(agregarCuenta)

    public fun actionHomeToRegistrar(): NavDirections =
        ActionOnlyNavDirections(R.id.action_home_to_registrar)

    public fun actionHomeToImportar(): NavDirections =
        ActionOnlyNavDirections(R.id.action_home_to_importar)
  }
}
