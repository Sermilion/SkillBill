package dev.skillbill.desktop.app

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path

/** The one loader the app runs in. A runtime installed while the refusal window is open joins it, so Compose loads only once. */
class AppClassLoader(classpath: List<URL>) : URLClassLoader(classpath.toTypedArray(), ClassLoader.getPlatformClassLoader()) {
  /** Adds the runtime jars whose library the loader does not hold yet, so the image's own copies stay first. */
  fun addJars(jars: List<Path>) {
    val present = urLs.mapTo(mutableSetOf()) { libraryName(Path.of(it.toURI()).fileName.toString()) }
    jars.filter { libraryName(it.fileName.toString()) !in present }.forEach { addURL(it.toUri().toURL()) }
  }
}

internal fun libraryName(fileName: String): String = fileName.removeSuffix(".jar").replace(Regex("-[0-9].*$"), "")
