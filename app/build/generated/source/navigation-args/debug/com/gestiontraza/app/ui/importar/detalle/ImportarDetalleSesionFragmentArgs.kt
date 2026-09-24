package com.gestiontraza.app.ui.importar.detalle

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavArgs
import java.lang.IllegalArgumentException
import kotlin.String
import kotlin.jvm.JvmStatic

public data class ImportarDetalleSesionFragmentArgs(
  public val rutaArchivo: String,
) : NavArgs {
  public fun toBundle(): Bundle {
    val result = Bundle()
    result.putString("rutaArchivo", this.rutaArchivo)
    return result
  }

  public fun toSavedStateHandle(): SavedStateHandle {
    val result = SavedStateHandle()
    result.set("rutaArchivo", this.rutaArchivo)
    return result
  }

  public companion object {
    @JvmStatic
    public fun fromBundle(bundle: Bundle): ImportarDetalleSesionFragmentArgs {
      bundle.setClassLoader(ImportarDetalleSesionFragmentArgs::class.java.classLoader)
      val __rutaArchivo : String?
      if (bundle.containsKey("rutaArchivo")) {
        __rutaArchivo = bundle.getString("rutaArchivo")
        if (__rutaArchivo == null) {
          throw IllegalArgumentException("Argument \"rutaArchivo\" is marked as non-null but was passed a null value.")
        }
      } else {
        throw IllegalArgumentException("Required argument \"rutaArchivo\" is missing and does not have an android:defaultValue")
      }
      return ImportarDetalleSesionFragmentArgs(__rutaArchivo)
    }

    @JvmStatic
    public fun fromSavedStateHandle(savedStateHandle: SavedStateHandle):
        ImportarDetalleSesionFragmentArgs {
      val __rutaArchivo : String?
      if (savedStateHandle.contains("rutaArchivo")) {
        __rutaArchivo = savedStateHandle["rutaArchivo"]
        if (__rutaArchivo == null) {
          throw IllegalArgumentException("Argument \"rutaArchivo\" is marked as non-null but was passed a null value")
        }
      } else {
        throw IllegalArgumentException("Required argument \"rutaArchivo\" is missing and does not have an android:defaultValue")
      }
      return ImportarDetalleSesionFragmentArgs(__rutaArchivo)
    }
  }
}
