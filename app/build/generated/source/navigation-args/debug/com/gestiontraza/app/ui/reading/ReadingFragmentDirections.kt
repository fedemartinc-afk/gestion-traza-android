package com.gestiontraza.app.ui.reading

import android.os.Bundle
import androidx.navigation.ActionOnlyNavDirections
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Int
import kotlin.String

public class ReadingFragmentDirections private constructor() {
  private data class ActionReadingToSend(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_send

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionReadingToVerificarOrigen(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_verificarOrigen

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionReadingToCompararTri(
    public val caravanas: String,
    public val dte: String = "",
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_compararTri

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        result.putString("dte", this.dte)
        return result
      }
  }

  private data class ActionReadingToEstadoTri(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_estadoTri

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionReadingToEstadoPredespacho(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_estadoPredespacho

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionReadingToUbicacionActual(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_ubicacionActual

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  private data class ActionReadingToOrdenarOrigen(
    public val caravanas: String,
    public val dtes: String = "",
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_ordenarOrigen

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        result.putString("dtes", this.dtes)
        return result
      }
  }

  private data class ActionReadingToHub(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_reading_to_hub

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  public companion object {
    public fun actionReadingToSend(caravanas: String): NavDirections =
        ActionReadingToSend(caravanas)

    public fun actionReadingToHome(): NavDirections =
        ActionOnlyNavDirections(R.id.action_reading_to_home)

    public fun actionReadingToVerificarOrigen(caravanas: String): NavDirections =
        ActionReadingToVerificarOrigen(caravanas)

    public fun actionReadingToCompararTri(caravanas: String, dte: String = ""): NavDirections =
        ActionReadingToCompararTri(caravanas, dte)

    public fun actionReadingToEstadoTri(caravanas: String): NavDirections =
        ActionReadingToEstadoTri(caravanas)

    public fun actionReadingToEstadoPredespacho(caravanas: String): NavDirections =
        ActionReadingToEstadoPredespacho(caravanas)

    public fun actionReadingToUbicacionActual(caravanas: String): NavDirections =
        ActionReadingToUbicacionActual(caravanas)

    public fun actionReadingToOrdenarOrigen(caravanas: String, dtes: String = ""): NavDirections =
        ActionReadingToOrdenarOrigen(caravanas, dtes)

    public fun actionReadingToHub(caravanas: String): NavDirections = ActionReadingToHub(caravanas)
  }
}
