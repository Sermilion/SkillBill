package dev.skillbill.tray.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConventionTargetInventoryTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `convention plugins produce JVM-only modules with an auditable manifest`() {
    val fixture = ConventionFixture(
      directory,
      mapOf(
        "lib" to """plugins { id("skillbill.kmp.compose"); id("skillbill.kotlin.inject") }""",
        "app" to """plugins { id("skillbill.desktop.application") }""",
      ),
    )
    fixture.run(":lib:writeArchitectureManifest", ":app:writeArchitectureManifest")

    listOf("lib", "app").forEach { module ->
      val manifest = fixture.manifest(module)
      assertEquals(listOf("jvm:jvm", "metadata:common"), manifestStrings(manifest, "targets"), "$module targets")
      assertTrue(manifestStrings(manifest, "plugins").none { "android" in it.lowercase() }, "$module applies an Android plugin")
      assertTrue(manifestStrings(manifest, "pluginClasses").none { it.startsWith("com.android.") }, "$module loads an Android plugin class")
    }
    val libKsp = manifestStrings(fixture.manifest("lib"), "kspConfigurations")
    assertTrue("kspJvm" in libKsp, "kotlin-inject must be wired through kspJvm: $libKsp")
    assertTrue(libKsp.none { Regex("^ksp(Android|Ios|Js|Wasm|Native|Linux|Macos|Mingw|Tvos|Watchos)").containsMatchIn(it) }, "Unexpected KSP targets: $libKsp")
  }
}
