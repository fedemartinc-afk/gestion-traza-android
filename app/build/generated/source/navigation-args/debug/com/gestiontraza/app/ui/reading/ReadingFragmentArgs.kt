package com.gestiontraza.app.ui.reading

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavArgs
import java.lang.IllegalArgumentException
import kotlin.String
import kotlin.jvm.JvmStatic

public data class ReadingFragmentArgs(
  public val mode: String = "",
  public val titulo: String = "",
) : NavArgs {
  public fun toBundle(): Bundle {
    val result = Bundle()
    result.putString("mode", this.mode)
    result.putString("titulo", this.titulo)
    return result
  }

  public fun toSavedStateHandle(): SavedStateHandle {
    val result = SavedStateHandle()
    result.set("mode", this.mode)
    result.set("titulo", this.titulo)
    return result
  }

  public companion object {
    @JvmStatic
    public fun fromBundle(bundle: Bundle): ReadingFragmentArgs {
      bundle.setClassLoader(ReadingFragmentArgs::class.java.classLoader)
      val __mode : String?
      if (bundle.containsKey("mode")) {
        __mode = bundle.getString("mode")
        if (__mode == null) {
          throw IllegalArgumentException("Argument \"mode\" is marked as non-null but was passed a null value.")
        }
      } else {
        __mode = ""
      }
      val __titulo : String?
      if (bundle.containsKey("titulo")) {
        __titulo = bundle.getString("titulo")
        if (__titulo == null) {
          throw IllegalArgumentException("Argument \"titulo\" is marked as non-null but was passed a null value.")
        }
      } else {
        __titulo = ""
      }
      return ReadingFragmentArgs(__mode, __titulo)
    }

    @JvmStatic
    public fun fromSavedStateHandle(savedStateHandle: SavedStateHandle): ReadingFragmentArgs {
      val __mode : String?
      if (savedStateHandle.contains("mode")) {
        __mode = savedStateHandle["mode"]
        if (__mode == null) {
          throw IllegalArgumentException("Argument \"mode\" is marked as non-null but was passed a null value")
        }
      } else {
        __mode = ""
      }
      val __titulo : String?
      if (savedStateHandle.contains("titulo")) {
        __titulo = savedStateHandle["titulo"]
        if (__titulo == null) {
          throw IllegalArgumentException("Argument \"titulo\" is marked as non-null but was passed a null value")
        }
      } else {
        __titulo = ""
      }
      return ReadingFragmentArgs(__mode, __titulo)
    }
  }
}
