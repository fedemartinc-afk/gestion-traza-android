package com.gestiontraza.app.ui.send

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavArgs
import java.lang.IllegalArgumentException
import kotlin.String
import kotlin.jvm.JvmStatic

public data class SendFragmentArgs(
  public val caravanas: String,
) : NavArgs {
  public fun toBundle(): Bundle {
    val result = Bundle()
    result.putString("caravanas", this.caravanas)
    return result
  }

  public fun toSavedStateHandle(): SavedStateHandle {
    val result = SavedStateHandle()
    result.set("caravanas", this.caravanas)
    return result
  }

  public companion object {
    @JvmStatic
    public fun fromBundle(bundle: Bundle): SendFragmentArgs {
      bundle.setClassLoader(SendFragmentArgs::class.java.classLoader)
      val __caravanas : String?
      if (bundle.containsKey("caravanas")) {
        __caravanas = bundle.getString("caravanas")
        if (__caravanas == null) {
          throw IllegalArgumentException("Argument \"caravanas\" is marked as non-null but was passed a null value.")
        }
      } else {
        throw IllegalArgumentException("Required argument \"caravanas\" is missing and does not have an android:defaultValue")
      }
      return SendFragmentArgs(__caravanas)
    }

    @JvmStatic
    public fun fromSavedStateHandle(savedStateHandle: SavedStateHandle): SendFragmentArgs {
      val __caravanas : String?
      if (savedStateHandle.contains("caravanas")) {
        __caravanas = savedStateHandle["caravanas"]
        if (__caravanas == null) {
          throw IllegalArgumentException("Argument \"caravanas\" is marked as non-null but was passed a null value")
        }
      } else {
        throw IllegalArgumentException("Required argument \"caravanas\" is missing and does not have an android:defaultValue")
      }
      return SendFragmentArgs(__caravanas)
    }
  }
}
