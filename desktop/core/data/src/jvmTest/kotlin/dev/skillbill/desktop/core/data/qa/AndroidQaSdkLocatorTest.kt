package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AndroidQaSdkLocatorTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `environment SDK root wins over repository properties and Android SDK root`() {
    val home = sdk("home-sdk")
    val androidRoot = sdk("root-sdk")
    val local = sdk("local-sdk")
    val repo = repository(local)
    val locator = AndroidQaSdkLocator(
      HostEnvironment(mapOf("ANDROID_HOME" to home.toString(), "ANDROID_SDK_ROOT" to androidRoot.toString()), "", ""),
      RecordingDiagnostics(),
    )

    assertEquals(home.resolve("platform-tools").resolve(adbName()), locator.locate(listOf(repo.toString()))?.adb)
  }

  @Test
  fun `repository SDK property wins over PATH and resolves relative to its repository`() {
    val pathSdk = sdk("path-sdk")
    val repo = Files.createTempDirectory(directory, "repo")
    val local = sdkAt(repo.resolve("project-sdk"))
    Files.writeString(repo.resolve("local.properties"), "sdk.dir=project-sdk\n")
    val path = pathSdk.resolve("platform-tools")
    val locator = AndroidQaSdkLocator(
      HostEnvironment(mapOf("PATH" to path.toString()), "", ""),
      RecordingDiagnostics(),
    )

    assertEquals(local.resolve("platform-tools").resolve(adbName()), locator.locate(listOf(repo.toString()))?.adb)
  }

  @Test
  fun `PATH is the last adb source and missing adb stays explicit`() {
    val pathSdk = sdk("path-sdk")
    val path = pathSdk.resolve("platform-tools")
    val found = AndroidQaSdkLocator(
      HostEnvironment(mapOf("PATH" to path.toString()), "", ""),
      RecordingDiagnostics(),
    )
    assertEquals(path.resolve(adbName()), found.locate(emptyList())?.adb)

    val missing = AndroidQaSdkLocator(HostEnvironment(emptyMap(), "", ""), RecordingDiagnostics())
    assertNull(missing.locate(emptyList()))
  }

  @Test
  fun `a usable environment SDK avoids reading malformed repository properties`() {
    val home = sdk("home-sdk")
    val repo = repository(home, "\\uZZZZ")
    val diagnostics = RecordingDiagnostics()
    val locator = AndroidQaSdkLocator(
      HostEnvironment(mapOf("ANDROID_HOME" to home.toString()), "", ""),
      diagnostics,
    )

    assertEquals(home.resolve("platform-tools").resolve(adbName()), locator.locate(listOf(repo.toString()))?.adb)
    assertTrue(diagnostics.categories.isEmpty())
  }

  @Test
  fun `malformed repository properties are diagnosed before falling back to PATH`() {
    val pathSdk = sdk("path-sdk")
    val repo = repository(pathSdk, "\\uZZZZ")
    val diagnostics = RecordingDiagnostics()
    val path = pathSdk.resolve("platform-tools")
    val locator = AndroidQaSdkLocator(HostEnvironment(mapOf("PATH" to path.toString()), "", ""), diagnostics)

    assertEquals(path.resolve(adbName()), locator.locate(listOf(repo.toString()))?.adb)
    assertTrue("desktop_qa_local_properties_invalid" in diagnostics.categories)
    assertTrue("desktop_qa_adb_path_fallback" in diagnostics.categories)
  }

  private fun sdk(name: String): Path {
    val root = directory.resolve(name)
    return sdkAt(root)
  }

  private fun sdkAt(root: Path): Path {
    val platformTools = Files.createDirectories(root.resolve("platform-tools"))
    val adb = Files.createFile(platformTools.resolve(adbName()))
    adb.toFile().setExecutable(true)
    return root
  }

  private fun repository(sdk: Path, configured: String = sdk.toString()): Path {
    val root = Files.createTempDirectory(directory, "repo")
    Files.writeString(root.resolve("local.properties"), "sdk.dir=$configured\n")
    return root
  }

  private fun adbName() = if (java.io.File.separatorChar == '\\') "adb.exe" else "adb"
}
