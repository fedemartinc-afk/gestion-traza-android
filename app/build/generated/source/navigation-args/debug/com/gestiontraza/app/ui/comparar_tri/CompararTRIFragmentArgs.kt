package com.gestiontraza.app.ui.comparar_tri

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavArgs
import java.lang.IllegalArgumentException
import kotlin.String
import kotlin.jvm.JvmStatic

public data class CompararTRIFragmentArgs(
  public val caravanas: String,
  public val dte: String = "",
) : NavArgs {
  public fun toBundle(): Bundle {
    val result = Bundle()
    result.putString("caravanas", this.caravanas)
    result.putString("dte", this.dte)
    return result
  }

  public fun toSavedStateHandle(): SavedStateHandle {
    val result = SavedStateHandle()
    result.set("caravanas", this.caravanas)
    result.set("dte", this.dte)
    return result
  }

  public companion object {
    @JvmStatic
    public fun fromBundle(bundle: Bundle): CompararTRIFragmentArgs {
      bundle.setClassLoader(CompararTRIFragmentArgs::class.java.classLoader)
      val __caravanas : String?
      if (bundle.containsKey("caravanas")) {
        __caravanas = bundle.getString("caravanas")
        if (__caravanas == null) {
          throw IllegalArgumentException("Argument \"caravanas\" is marked as non-null but was passed a null value.")
        }
      } else {
        throw IllegalArgumentException("Required argument \"caravanas\" is missing and does not have an android:defaultValue")
      }
      val __dte : String?
      if (bundle.containsKey("dte")) {
        __dte = bundle.getString("dte")
        if (__dte == null) {
          throw IllegalArgumentException("Argument \"dte\" is marked as non-null but was passed a null value.")
        }
      } else {
        __dte = ""
      }
      return CompararTRIFragmentArgs(__caravanas, __dte)
    }

    @JvmStatic
    public fun fromSavedStateHandle(savedStateHandle: SavedStateHandle): CompararTRIFragmentArgs {
      val __caravanas : String?
      if (savedStateHandle.contains("caravanas")) {
        __caravanas = savedStateHandle["caravanas"]
        if (__caravanas == null) {
          throw IllegalArgumentException("Argument \"caravanas\" is marked as non-null but was passed a null value")
        }
      } else {
        throw IllegalArgumentException("Required argument \"caravanas\" is missing and does not have an android:defaultValue")
      }
      val __dte : String?
      if (savedStateHandle.contains("dte")) {
        __dte = savedStateHandle["dte"]
        if (__dte == null) {
          throw IllegalArgumentException("Argument \"dte\" is marked as non-null but was passed a null value")
        }
      } else {
        __dte = ""
      }
      return CompararTRIFragmentArgs(__caravanas, __dte)
    }
  }
}
