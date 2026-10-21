package dev.skillbill.tray.buildlogic

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
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

  @Test
  fun `identity-only changes replace the identity in an existing application image`() {
    val fixture = ConventionFixture(directory, mapOf(
      "app" to """
        plugins { id("skillbill.desktop.application") }
        compose.desktop.application {
          mainClass = "MainKt"
          nativeDistributions.appResourcesRootDir.set(layout.projectDirectory.dir("app-resources"))
        }
        configurations.configureEach {
          resolutionStrategy.dependencySubstitution {
            substitute(module("dev.skillbill:runtime-cli")).using(project(":runtime-cli"))
          }
        }
      """,
      "runtime-cli" to """
        plugins { `java-library` }
        group = "dev.skillbill"
        version = "1.0"
      """,
    ))
    fixture.write("app/src/jvmMain/kotlin/Main.kt", "fun main() = Unit")
    val identity = "app/app-resources/common/build-identity.properties"
    fixture.write(identity, "commit=first\ndirty=false")
    val nativeTask = if (System.getProperty("os.name").contains("linux", true)) "compileLinuxTrayNative" else "compileMacTrayNative"
    val arguments = arrayOf(":app:createDistributable", "-x", ":app:$nativeTask")

    assertEquals(TaskOutcome.SUCCESS, fixture.run(*arguments).task(":app:createDistributable")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, fixture.run(*arguments).task(":app:createDistributable")?.outcome)

    fixture.write(identity, "commit=second\ndirty=true\ninstall_run_id=next-install")

    assertEquals(TaskOutcome.SUCCESS, fixture.run(*arguments).task(":app:createDistributable")?.outcome)
    val packagedIdentity = fixture.file("app/build/compose/binaries/main/app").walkTopDown()
      .single { it.isFile && it.name == "build-identity.properties" }
    assertEquals(fixture.file(identity).readText(), packagedIdentity.readText())
  }
}
