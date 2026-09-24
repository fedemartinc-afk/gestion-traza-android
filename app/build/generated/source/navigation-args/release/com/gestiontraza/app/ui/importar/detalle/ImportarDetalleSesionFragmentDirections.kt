package com.gestiontraza.app.ui.importar.detalle

import android.os.Bundle
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Int
import kotlin.String

public class ImportarDetalleSesionFragmentDirections private constructor() {
  private data class ActionImportarDetalleToHub(
    public val caravanas: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_importarDetalle_to_hub

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("caravanas", this.caravanas)
        return result
      }
  }

  public companion object {
    public fun actionImportarDetalleToHub(caravanas: String): NavDirections =
        ActionImportarDetalleToHub(caravanas)
  }
}
