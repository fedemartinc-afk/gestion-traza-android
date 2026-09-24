package com.gestiontraza.app.ui.importar.sesiones

import android.os.Bundle
import androidx.navigation.NavDirections
import com.gestiontraza.app.R
import kotlin.Int
import kotlin.String

public class ImportarSesionesFragmentDirections private constructor() {
  private data class ActionImportarSesionesToDetalle(
    public val rutaArchivo: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_importarSesiones_to_detalle

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("rutaArchivo", this.rutaArchivo)
        return result
      }
  }

  private data class ActionImportarSesionesToEditar(
    public val rutaArchivo: String,
  ) : NavDirections {
    public override val actionId: Int = R.id.action_importarSesiones_to_editar

    public override val arguments: Bundle
      get() {
        val result = Bundle()
        result.putString("rutaArchivo", this.rutaArchivo)
        return result
      }
  }

  public companion object {
    public fun actionImportarSesionesToDetalle(rutaArchivo: String): NavDirections =
        ActionImportarSesionesToDetalle(rutaArchivo)

    public fun actionImportarSesionesToEditar(rutaArchivo: String): NavDirections =
        ActionImportarSesionesToEditar(rutaArchivo)
  }
}
