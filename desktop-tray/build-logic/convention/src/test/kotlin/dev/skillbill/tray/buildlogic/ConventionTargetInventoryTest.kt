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
        "store" to """plugins { id("skillbill.kotlin.inject"); id("skillbill.room") }""",
        "app" to """plugins { id("skillbill.desktop.application") }""",
      ),
    )
    fixture.run(":lib:writeArchitectureManifest", ":store:writeArchitectureManifest", ":app:writeArchitectureManifest")

    listOf("lib", "store", "app").forEach { module ->
      val manifest = fixture.manifest(module)
      assertEquals(listOf("jvm:jvm", "metadata:common"), manifestStrings(manifest, "targets"), "$module targets")
      assertTrue(manifestStrings(manifest, "plugins").none { "android" in it.lowercase() }, "$module applies an Android plugin")
      assertTrue(manifestStrings(manifest, "pluginClasses").none { it.startsWith("com.android.") }, "$module loads an Android plugin class")
    }
    val libKsp = manifestStrings(fixture.manifest("lib"), "kspConfigurations")
    assertTrue("kspJvm" in libKsp, "kotlin-inject must be wired through kspJvm: $libKsp")
    assertTrue(libKsp.none { Regex("^ksp(Android|Ios|Js|Wasm|Native|Linux|Macos|Mingw|Tvos|Watchos)").containsMatchIn(it) }, "Unexpected KSP targets: $libKsp")
    val storeKsp = manifestStrings(fixture.manifest("store"), "kspConfigurations")
    assertTrue("kspJvm" in storeKsp, "Room must be wired through kspJvm: $storeKsp")
    assertTrue(storeKsp.none { Regex("^ksp(Android|Ios|Js|Wasm|Native|Linux|Macos|Mingw|Tvos|Watchos)").containsMatchIn(it) }, "Unexpected KSP targets: $storeKsp")
    assertTrue("skillbill.room" in manifestStrings(fixture.manifest("store"), "plugins"), "The Room convention is not reported in the manifest.")
  }
}
