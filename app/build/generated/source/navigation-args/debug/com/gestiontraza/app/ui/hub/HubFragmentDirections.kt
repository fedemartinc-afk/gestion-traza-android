package com.gestiontraza.app.ui.hub

import android.os.Bundle
import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Int
import kotlin.String

public class HubFragmentDirections private constructor() {
  private data class ActionHubToVerificarOrigen(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_hub_to_verificarOrigen

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionHubToCompararTri(
    public val caravanas: String,
    public val dte: String = "",
  ) : NavDirections {
    public override val actionId: Int = R.id.action_hub_to_compararTri

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        result.putString("dte", this.dte)
        return result
      }
  }

  private data class ActionHubToOrdenarOrigen(
    public val caravanas: String,
    public val dtes: String = "",
  ) : NavDirections {
    public override val actionId: Int = R.id.action_hub_to_ordenarOrigen

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        result.putString("dtes", this.dtes)
        return result
      }
  }

  private data class ActionHubToUbicacionActual(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_hub_to_ubicacionActual

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionHubToEstadoTri(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_hub_to_estadoTri

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionHubToEstadoPredespacho(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_hub_to_estadoPredespacho

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  public companion object {
    public fun actionHubToVerificarOrigen(caravanas: String): NavDirections =
        ActionHubToVerificarOrigen(caravanas)

    public fun actionHubToCompararTri(caravanas: String, dte: String = ""): NavDirections =
        ActionHubToCompararTri(caravanas, dte)

    public fun actionHubToOrdenarOrigen(caravanas: String, dtes: String = ""): NavDirections =
        ActionHubToOrdenarOrigen(caravanas, dtes)

    public fun actionHubToUbicacionActual(caravanas: String): NavDirections =
        ActionHubToUbicacionActual(caravanas)

    public fun actionHubToEstadoTri(caravanas: String): NavDirections =
        ActionHubToEstadoTri(caravanas)

    public fun actionHubToEstadoPredespacho(caravanas: String): NavDirections =
        ActionHubToEstadoPredespacho(caravanas)

    public fun actionHubToSesiones(): NavDirections =
        ActionOnlyNavDirections(R.id.action_hub_to_sesiones)
  }
}
