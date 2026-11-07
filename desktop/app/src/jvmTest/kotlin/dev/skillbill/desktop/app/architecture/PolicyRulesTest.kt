package dev.skillbill.desktop.app.architecture

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyRulesTest {
  @Test
  fun `no shell or feature source sorts work or reads a deadline`() {
    val desktopRoot = Path.of(requireNotNull(System.getProperty("desktopRoot")) { "The desktopRoot system property is not set." })
    assertEquals(emptyList(), PolicyRules.violations(PolicyRules.readSources(desktopRoot, loadManifests())))
  }

  @Test
  fun `an empty scan fails`() {
    assertTrue(PolicyRules.violations(emptyList()).isNotEmpty())
  }

  @Test
  fun `temp-root scan covers every desktop source set and stays clean`() {
    val desktopRoot = Path.of(requireNotNull(System.getProperty("desktopRoot")) { "The desktopRoot system property is not set." })
    val sources = PolicyRules.readTempScanSources(desktopRoot, loadManifests())
    assertEquals(emptyList(), PolicyRules.sharedTempViolations(sources))
    assertTrue(sources.any { it.path.startsWith("benchmark/") })
    assertTrue(sources.any { it.path.startsWith("core/data/") })
    assertTrue(sources.any { it.path == "core/data/src/jvmMain/kotlin/dev/skillbill/desktop/core/data/runtime/DesktopTempRoot.kt" })
  }

  @Test
  fun `temp-root scan rejects shared access and accepts owned roots`() {
    val rejected = listOf(
      "System.getProperty(\"java.io.tmpdir\")", "System.getProperty(\"java.io.tmpdir\", fallback)",
      "val result = \"\"\"${'$'}{System.getProperty(\"java.io.tmpdir\")}\"\"\"",
      "Path.of(\"/tmp\")", "Paths.get(\"/tmp/x\")", "File(\"/tmp/x\")",
      "Files.createTempFile(\"x\", \".y\")", "Files.createTempDirectory(\"x\")",
      "Files.createTempFile(\n  \"x\", \".y\")",
      "Files.createTempDirectory(STAGING_PREFIX)", "File.createTempFile(\"x\", \".y\")",
      "createTempDirectory()", "createTempFile(\"x\")", "createTempDirectory(prefix = \"x\")",
      "createTempFile(null, \"x\")", "import java.nio.file.Files as F\nF.createTempFile(\"x\", \".y\")",
      "import java.lang.System as S\nS.getProperty(\"java.io.tmpdir\")",
      "import kotlin.io.path.createTempDirectory as tempDir\ntempDir()",
      "import java.nio.file.Path as P\nP.of(\"/tmp/x\")", "import java.nio.file.Paths as P\nP.get(\"/tmp/x\")",
      "import java.io.File as F\nF(\"/tmp/x\")", "import kotlin.io.path.createTempFile as tempFile\ntempFile(\"x\")",
      "java.nio.file.Files.createTempFile(\"x\", \".y\")", "java.lang.System.getProperty(\"java.io.tmpdir\")",
      "java.nio.file.Path.of(\"/tmp/x\")", "java.nio.file.Paths.get(\"/tmp/x\")", "java.io.File(\"/tmp/x\")",
      "java.io.File.createTempFile(\"x\", \".y\")", "kotlin.io.path.createTempDirectory()",
    )
    rejected.forEach { code -> assertTrue(PolicyRules.sharedTempViolations(listOf(source(code))).isNotEmpty(), code) }
    assertTrue(PolicyRules.sharedTempViolations(emptyList()).isNotEmpty())
    val accepted = listOf(
      "Files.createTempFile(DesktopTempRoot.resolve(), \"x\", \".y\")", "Files.createTempFile(directory, \"x\", \".y\")",
      "System.setProperty(\"java.io.tmpdir\", value)", "val option = \"-Djava.io.tmpdir=/state/scratch/jvm\"",
      "// System.getProperty(\"java.io.tmpdir\")",
      "Path.of(\"/tmpfs\")",
      "val example = \"\"\"System.getProperty(\"java.io.tmpdir\")\"\"\"",
    )
    accepted.forEach { code ->
      assertEquals(emptyList(), PolicyRules.sharedTempViolations(listOf(source(code))), code)
    }
    assertTrue(PolicyRules.sharedTempViolations(listOf(source("val url = \"https://x\"; System.getProperty(\"java.io.tmpdir\")"))).isNotEmpty())
    assertEquals(emptyList(), PolicyRules.sharedTempViolations(listOf(source("System.getProperty(\"java.io.tmpdir\")", "core/data/src/jvmMain/kotlin/dev/skillbill/desktop/core/data/runtime/DesktopTempRoot.kt"))))
  }

  @Test
  fun `desktop test tasks and wrapper keep their isolated temp root`() {
    val desktopRoot = Path.of(requireNotNull(System.getProperty("desktopRoot")) { "The desktopRoot system property is not set." })
    assertEquals(emptyList(), PolicyRules.tempIsolationViolations { path ->
      val file = desktopRoot.resolve(path)
      file.takeIf(java.nio.file.Files::isRegularFile)?.let(java.nio.file.Files::readString)
    })
    val clean = mapOf(
      "build-logic/convention/src/main/kotlin/dev/skillbill/desktop/buildlogic/QualityConventionPlugin.kt" to
        "tasks.withType(Test::class.java).configureEach { isolateJvmTempDir() }",
      "build-logic/convention/src/main/kotlin/dev/skillbill/desktop/buildlogic/TestTempDir.kt" to "-Djava.io.tmpdir=",
      "build-logic/convention/build.gradle.kts" to "tasks.withType<Test>().configureEach { jvmArgs(\"java.io.tmpdir\") }",
      "gradlew" to "skill_bill_jvm_temp_root=",
    )
    val missingCall = clean + ("build-logic/convention/src/main/kotlin/dev/skillbill/desktop/buildlogic/QualityConventionPlugin.kt" to "tasks.withType(Test::class.java).configureEach {}")
    val missingArgument = clean + ("build-logic/convention/src/main/kotlin/dev/skillbill/desktop/buildlogic/TestTempDir.kt" to "jvmArgs(\"other\")")
    val missingBuildSetting = clean + ("build-logic/convention/build.gradle.kts" to "tasks.withType<Test>().configureEach { jvmArgs(\"other\") }")
    val missingWrapper = clean + ("gradlew" to "wrapper")
    val missingFile = clean - "gradlew"
    listOf(missingCall, missingArgument, missingBuildSetting, missingWrapper, missingFile).forEach { fixture ->
      assertTrue(PolicyRules.tempIsolationViolations(fixture::get).isNotEmpty())
    }
  }

  @Test
  fun `tray sends app-owned icon files to NativeTray without file rendering`() {
    val desktopRoot = Path.of(requireNotNull(System.getProperty("desktopRoot")) { "The desktopRoot system property is not set." })
    val host = java.nio.file.Files.readString(desktopRoot.resolve("app/src/jvmMain/kotlin/dev/skillbill/desktop/app/tray/TrayHostContent.kt"))
    val icons = java.nio.file.Files.readString(desktopRoot.resolve("app/src/jvmMain/kotlin/dev/skillbill/desktop/app/tray/TrayIconFiles.kt"))
    assertTrue(host.contains("NativeTray()"))
    assertTrue(host.contains("tray.update(paths.png, paths.ico"))
    assertTrue(!host.contains("dev.nucleusframework.composenativetray.tray.api.Tray"))
    assertTrue(!icons.contains("renderComposableToPngFile"))
    assertTrue(!icons.contains("renderComposableToIcoFile"))
  }

  @Test
  fun `each pattern rejects a synthetic violation and ignores comments and strings`() {
    val violating = listOf(
      "items.sorted()", "items.sortedBy { it.title }", "items.sortedByDescending { it.title }", "items.sortedWith(order)",
      "items.sortedDescending()", "items.sortBy { it.title }", "items.sortByDescending { it.title }", "items.sortWith(order)",
      "items.sortDescending()", "item.membershipDeadline", "item.freshnessDeadline",
    )
    violating.forEach { code ->
      assertEquals(1, PolicyRules.violations(listOf(source("val x = $code"))).size, code)
    }
    val clean = source("// items.sorted()\nval label = \"items.sortedBy { it } membershipDeadline\"\n/* freshnessDeadline */")
    assertEquals(emptyList(), PolicyRules.violations(listOf(clean)))
  }

  private fun source(text: String) = ScannedSource("feature/work/src/commonMain/kotlin/Synthetic.kt", text)
  private fun source(text: String, path: String) = ScannedSource(path, text)
}
