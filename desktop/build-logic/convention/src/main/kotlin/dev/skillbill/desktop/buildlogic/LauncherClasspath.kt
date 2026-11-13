package dev.skillbill.desktop.buildlogic

import java.io.File
import java.net.URI
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

private const val CLASSPATH_PREFIX = "app.classpath="
private const val APP_DIRECTORY_PREFIX = "\$APPDIR/"
private const val LAUNCHER_CLASSPATH_JAR = "launcher-classpath.jar"

internal fun compactLauncherClasspath(image: File) {
  val configuration = image.walkTopDown()
    .filter { it.isFile && it.extension == "cfg" && it.parentFile.name == "app" }.single()
  val lines = configuration.readLines()
  val entries = lines.filter { it.startsWith(CLASSPATH_PREFIX) }.map { it.removePrefix(CLASSPATH_PREFIX) }
  val compactEntry = "$APP_DIRECTORY_PREFIX$LAUNCHER_CLASSPATH_JAR"
  if (entries == listOf(compactEntry)) return
  require(entries.isNotEmpty()) { "Packaged launcher has no classpath: $configuration" }
  val manifest = Manifest().apply {
    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
    mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ") { entry ->
      require(entry.startsWith(APP_DIRECTORY_PREFIX)) { "Unexpected packaged classpath entry: $entry" }
      URI(null, null, entry.removePrefix(APP_DIRECTORY_PREFIX), null).toASCIIString()
    }
  }
  val classpathJar = configuration.parentFile.resolve(LAUNCHER_CLASSPATH_JAR)
  JarOutputStream(classpathJar.outputStream(), manifest).use { }
  var classpathWritten = false
  configuration.writeText(
    lines.mapNotNull { line ->
      if (!line.startsWith(CLASSPATH_PREFIX)) line else if (classpathWritten) null else {
        classpathWritten = true
        "$CLASSPATH_PREFIX$compactEntry"
      }
    }.joinToString("\n", postfix = "\n"),
  )
}
