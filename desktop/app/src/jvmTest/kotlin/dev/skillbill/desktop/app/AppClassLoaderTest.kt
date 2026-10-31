package dev.skillbill.desktop.app

import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class AppClassLoaderTest {
  @Test
  fun `packaged app native tray overrides the dependency resource after jar sorting`() {
    val directory = Files.createTempDirectory("tray-classpath")
    try {
      val resource = "composetray/native/linux-x64/libLinuxTray.so"
      val dependency = directory.resolve("ComposeNativeTray.jar")
      val app = directory.resolve("app-jvm.jar")
      mapOf(dependency to "upstream", app to "patched").forEach { (path, content) ->
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
          jar.putNextEntry(JarEntry(resource))
          jar.write(content.toByteArray())
          jar.closeEntry()
        }
      }
      val entries = applicationEntriesFirst(listOf(dependency, app), app)
      AppClassLoader(entries.map { it.toUri().toURL() }).use { loader ->
        assertEquals("patched", loader.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() })
      }
    } finally {
      directory.toFile().deleteRecursively()
    }
  }
}
