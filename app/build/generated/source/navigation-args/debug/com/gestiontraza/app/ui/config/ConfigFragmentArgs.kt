package com.gestiontraza.app.ui.config

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavArgs
import java.lang.IllegalArgumentException
import kotlin.Boolean
import kotlin.jvm.JvmStatic

public data class ConfigFragmentArgs(
  public val agregarCuenta: Boolean = false,
) : NavArgs {
  public fun toBundle(): Bundle {
    val result = Bundle()
    result.putBoolean("agregarCuenta", this.agregarCuenta)
    return result
  }

  public fun toSavedStateHandle(): SavedStateHandle {
    val result = SavedStateHandle()
    result.set("agregarCuenta", this.agregarCuenta)
    return result
  }

  public companion object {
    @JvmStatic
    public fun fromBundle(bundle: Bundle): ConfigFragmentArgs {
      bundle.setClassLoader(ConfigFragmentArgs::class.java.classLoader)
      val __agregarCuenta : Boolean
      if (bundle.containsKey("agregarCuenta")) {
        __agregarCuenta = bundle.getBoolean("agregarCuenta")
      } else {
        __agregarCuenta = false
      }
      return ConfigFragmentArgs(__agregarCuenta)
    }

    @JvmStatic
    public fun fromSavedStateHandle(savedStateHandle: SavedStateHandle): ConfigFragmentArgs {
      val __agregarCuenta : Boolean?
      if (savedStateHandle.contains("agregarCuenta")) {
        __agregarCuenta = savedStateHandle["agregarCuenta"]
        if (__agregarCuenta == null) {
          throw IllegalArgumentException("Argument \"agregarCuenta\" of type boolean does not support null values")
        }
      } else {
        __agregarCuenta = false
      }
      return ConfigFragmentArgs(__agregarCuenta)
    }
  }
}
