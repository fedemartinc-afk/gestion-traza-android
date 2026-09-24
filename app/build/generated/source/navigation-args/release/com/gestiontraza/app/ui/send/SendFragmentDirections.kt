package com.gestiontraza.app.ui.send

import android.os.Bundle
import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Int
import kotlin.String

public class SendFragmentDirections private constructor() {
  private data class ActionSendToEnviarWeb(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_send_to_enviarWeb

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionSendToCompararTri(
    public val caravanas: String,
    public val dte: String = "",
  ) : NavDirections {
    public override val actionId: Int = R.id.action_send_to_compararTri

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        result.putString("dte", this.dte)
        return result
      }
  }

  private data class ActionSendToOrdenarOrigen(
    public val caravanas: String,
    public val dtes: String = "",
  ) : NavDirections {
    public override val actionId: Int = R.id.action_send_to_ordenarOrigen

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        result.putString("dtes", this.dtes)
        return result
      }
  }

  public companion object {
    public fun actionSendToHome(): NavDirections = ActionOnlyNavDirections(R.id.action_send_to_home)

    public fun actionSendToEnviarWeb(caravanas: String): NavDirections =
        ActionSendToEnviarWeb(caravanas)

    public fun actionSendToCompararTri(caravanas: String, dte: String = ""): NavDirections =
        ActionSendToCompararTri(caravanas, dte)

    public fun actionSendToOrdenarOrigen(caravanas: String, dtes: String = ""): NavDirections =
        ActionSendToOrdenarOrigen(caravanas, dtes)
  }
}
