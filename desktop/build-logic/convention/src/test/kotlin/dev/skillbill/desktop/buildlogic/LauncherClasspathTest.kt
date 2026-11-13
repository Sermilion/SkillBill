package dev.skillbill.desktop.buildlogic

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LauncherClasspathTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `long packaged classpaths still resolve every jar and preserve resource precedence`() {
    val app = directory.resolve("Sona/lib/app").apply { mkdirs() }
    val jvmConfig = directory.resolve("Sona/lib/runtime/lib/jvm.cfg").apply {
      parentFile.mkdirs()
      writeText("-server KNOWN\n")
    }
    val names = (0..120).map { "library $it-${"a".repeat(60)}.jar" }
    names.forEachIndexed { index, name ->
      JarOutputStream(app.resolve(name).outputStream()).use { jar ->
        jar.putNextEntry(JarEntry("entry-$index"))
        jar.write(index.toString().toByteArray())
        jar.closeEntry()
        jar.putNextEntry(JarEntry("shared"))
        jar.write(index.toString().toByteArray())
        jar.closeEntry()
      }
    }
    val config = app.resolve("Sona.cfg")
    config.writeText(
      "[Application]\napp.mainclass=example.Main\n" +
        names.joinToString("\n", postfix = "\n") { "app.classpath=\$APPDIR/$it" } +
        "[JavaOptions]\njava-options=-Xmx1g\n",
    )
    assertTrue(config.length() > 8192)

    compactLauncherClasspath(directory)
    val compact = config.readText()
    compactLauncherClasspath(directory)

    assertEquals(compact, config.readText())
    assertEquals("-server KNOWN\n", jvmConfig.readText())
    assertEquals(
      "[Application]\napp.mainclass=example.Main\napp.classpath=\$APPDIR/launcher-classpath.jar\n" +
        "[JavaOptions]\njava-options=-Xmx1g\n",
      compact,
    )
    URLClassLoader(arrayOf(app.resolve("launcher-classpath.jar").toURI().toURL()), null).use { loader ->
      names.indices.forEach { index ->
        assertEquals(index.toString(), loader.getResourceAsStream("entry-$index")!!.bufferedReader().use { it.readText() })
      }
      assertEquals("0", loader.getResourceAsStream("shared")!!.bufferedReader().use { it.readText() })
    }
  }
}
