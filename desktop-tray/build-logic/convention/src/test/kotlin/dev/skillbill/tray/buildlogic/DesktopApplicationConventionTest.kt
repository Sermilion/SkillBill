package dev.skillbill.tray.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopApplicationConventionTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `resource processing builds only the host native tray task and never needs appimagetool`() {
    val fixture = ConventionFixture(directory, mapOf("app" to """plugins { id("skillbill.desktop.application") }"""))

    val graph = fixture.run(":app:jvmProcessResources", "--dry-run").output
    val osName = System.getProperty("os.name").lowercase()

    assertTrue(":app:jvmProcessResources" in graph, "Resource processing is missing from the task graph:\n$graph")
    assertTrue((":app:compileMacTrayNative" in graph) == ("mac" in osName), "macOS tray task presence must match the host OS:\n$graph")
    assertTrue((":app:compileLinuxTrayNative" in graph) == ("linux" in osName), "Linux tray task presence must match the host OS:\n$graph")
    assertFalse("packageAppImage" in graph || "appimagetool" in graph, "Resource processing must not reach AppImage packaging:\n$graph")
  }

  @Test
  fun `packaging tasks are registered on the application module`() {
    val fixture = ConventionFixture(directory, mapOf("app" to """plugins { id("skillbill.desktop.application") }"""))

    val tasks = fixture.run(":app:tasks", "--all").output

    listOf("createDistributable", "packageAppImage", "stageAppImage", "compileMacTrayNative", "compileAppImageLauncher").forEach { name ->
      assertTrue(name in tasks, "Task '$name' is not registered:\n$tasks")
    }
  }
}
