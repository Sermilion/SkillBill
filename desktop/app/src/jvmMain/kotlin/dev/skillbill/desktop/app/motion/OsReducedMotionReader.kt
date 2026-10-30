package dev.skillbill.desktop.app.motion

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit.SECONDS

sealed interface ReducedMotionRead {
  data class Known(val reduced: Boolean, val source: String) : ReducedMotionRead
  data class Unavailable(val source: String, val reason: String) : ReducedMotionRead
}

class OsReducedMotionReader {
  fun read(): ReducedMotionRead = when {
    Platform.isMac() -> readCommand("macOS com.apple.universalaccess reduceMotion", "defaults", "read", "com.apple.universalaccess", "reduceMotion") { output, exitCode ->
      if (exitCode != 0 && output.contains("does not exist", ignoreCase = true)) {
        ReducedMotionRead.Known(false, "macOS com.apple.universalaccess reduceMotion")
      } else if (exitCode != 0) {
        ReducedMotionRead.Unavailable("macOS com.apple.universalaccess reduceMotion", "defaults returned exit code $exitCode")
      } else when (output.trim()) {
        "1" -> ReducedMotionRead.Known(true, "macOS com.apple.universalaccess reduceMotion")
        "0" -> ReducedMotionRead.Known(false, "macOS com.apple.universalaccess reduceMotion")
        else -> ReducedMotionRead.Unavailable("macOS com.apple.universalaccess reduceMotion", "unexpected output")
      }
    }
    Platform.isLinux() -> readCommand(
      "GNOME org.gnome.desktop.interface enable-animations",
      "gsettings",
      "get",
      "org.gnome.desktop.interface",
      "enable-animations",
    ) { output, exitCode ->
      when {
        exitCode != 0 -> ReducedMotionRead.Unavailable("GNOME enable-animations", "gsettings returned exit code $exitCode")
        output.trim() == "false" -> ReducedMotionRead.Known(true, "GNOME enable-animations")
        output.trim() == "true" -> ReducedMotionRead.Known(false, "GNOME enable-animations")
        else -> ReducedMotionRead.Unavailable("GNOME enable-animations", "unexpected output")
      }
    }
    Platform.isWindows() -> readWindows()
    else -> ReducedMotionRead.Unavailable(System.getProperty("os.name", "unknown"), "unsupported operating system")
  }

  private fun readWindows(): ReducedMotionRead = try {
    val enabled = IntByReference()
    val user32 = Native.load("user32", User32Spi::class.java, W32APIOptions.UNICODE_OPTIONS)
    if (user32.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0)) {
      ReducedMotionRead.Known(enabled.value == 0, "Windows SPI_GETCLIENTAREAANIMATION")
    } else {
      ReducedMotionRead.Unavailable("Windows SPI_GETCLIENTAREAANIMATION", "SystemParametersInfoW failed")
    }
  } catch (failure: RuntimeException) {
    ReducedMotionRead.Unavailable("Windows SPI_GETCLIENTAREAANIMATION", failure.message ?: "JNA could not load user32")
  } catch (failure: LinkageError) {
    ReducedMotionRead.Unavailable("Windows SPI_GETCLIENTAREAANIMATION", failure.message ?: "JNA could not load user32")
  }

  private fun readCommand(
    source: String,
    vararg command: String,
    interpret: (String, Int) -> ReducedMotionRead,
  ): ReducedMotionRead {
    val process = try {
      ProcessBuilder(*command).redirectErrorStream(true).start()
    } catch (failure: IOException) {
      return ReducedMotionRead.Unavailable(source, failure.message ?: "could not start OS setting reader")
    } catch (failure: SecurityException) {
      return ReducedMotionRead.Unavailable(source, failure.message ?: "OS setting reader was denied")
    }
    return try {
      if (!process.waitFor(1, SECONDS)) {
        process.destroyForcibly()
        ReducedMotionRead.Unavailable(source, "OS setting reader timed out")
      } else {
        val output = process.inputStream.use { it.readNBytes(MAX_OUTPUT_BYTES).toString(StandardCharsets.UTF_8) }
        interpret(output, process.exitValue())
      }
    } catch (failure: IOException) {
      ReducedMotionRead.Unavailable(source, failure.message ?: "could not read OS setting")
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      ReducedMotionRead.Unavailable(source, "interrupted while reading OS setting")
    } finally {
      process.destroy()
    }
  }

  private interface User32Spi : StdCallLibrary {
    fun SystemParametersInfoW(action: Int, parameter: Int, value: IntByReference, updateProfile: Int): Boolean
  }

  private companion object {
    const val MAX_OUTPUT_BYTES = 128
    const val SPI_GETCLIENTAREAANIMATION = 0x1042
  }
}
