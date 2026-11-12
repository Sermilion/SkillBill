package skillbill.infrastructure.skills.scaffold

import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AppRunScriptTest {
  private val root = Files.createTempDirectory("skillbill-app-run-script")
  private val script = repoRootFromTest().resolve("platform-packs/kmp/app-run/android-app-run.sh")

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `run selects metadata for the requested flavored variant and rejects ambiguity`() {
    val checkout = Files.createDirectories(root.resolve("checkout"))
    val app = Files.createDirectories(checkout.resolve("app"))
    val apk = app.resolve("build/outputs/apk")
    writeMetadata(apk.resolve("debug/output-metadata.json"), "debug", "wrong.stale.app")
    writeMetadata(apk.resolve("product/demo/debug/output-metadata.json"), "productDemoDebug", "org.example.app")
    val binaries = Files.createDirectories(root.resolve("bin"))
    Files.writeString(app.resolve("build.gradle.kts"), "plugins { id(\"com.android.application\") }\n")
    val gradleCalls = root.resolve("gradle-calls.txt")
    writeExecutable(checkout.resolve("gradlew"), "#!/usr/bin/env bash\nprintf '%s\\n' \"\$*\" >> '$gradleCalls'\n")
    writeExecutable(
      binaries.resolve("adb"),
      """
      #!/usr/bin/env bash
      case "${'$'}*" in
        *"query-activities"*) printf '%s\n' 'org.example.app/.MainActivity' 'org.example.app/leakcanary.LeakActivity' ;;
        *"am start"*) exit 0 ;;
        *"dumpsys activity activities"*) printf '%s\n' 'topResumedActivity=ActivityRecord{ org.example.app/.MainActivity }' ;;
      esac
      """.trimIndent() + "\n",
    )
    val result = invoke(checkout, binaries)

    assertEquals(0, result.first)
    assertContains(result.second, "##app-run step build :app:installProductDemoDebug")
    assertContains(result.second, "##app-run launched org.example.app/org.example.app.MainActivity")
    assertEquals(":app:installProductDemoDebug", Files.readString(gradleCalls).trim())

    writeMetadata(apk.resolve("another/path/output-metadata.json"), "productDemoDebug", "org.example.other")
    val ambiguous = invoke(checkout, binaries)
    assertEquals(4, ambiguous.first)
    assertContains(ambiguous.second, "##app-run failure application-id-unreadable")
  }

  private fun invoke(
    checkout: Path,
    binaries: Path,
  ): Pair<Int, String> {
    val process =
      ProcessBuilder("bash", script.toString(), "run")
        .directory(checkout.toFile())
        .apply {
          environment().remove("ANDROID_HOME")
          environment().remove("ANDROID_SDK_ROOT")
          environment()["APP_RUN_CHECKOUT"] = checkout.toString()
          environment()["APP_RUN_PLATFORM"] = "android"
          environment()["APP_RUN_MODULE"] = "app"
          environment()["APP_RUN_VARIANT"] = "productDemoDebug"
          environment()["ANDROID_SERIAL"] = "emulator-5554"
          environment()["PATH"] = "$binaries:${environment()["PATH"]}"
        }.start()
    val output =
      process.inputStream.bufferedReader().use { it.readText() } +
        process.errorStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output
  }

  private fun writeMetadata(
    path: Path,
    variant: String,
    applicationId: String,
  ) {
    Files.createDirectories(path.parent)
    Files.writeString(path, "{\"variantName\":\"$variant\",\"applicationId\":\"$applicationId\"}")
  }

  private fun writeExecutable(
    path: Path,
    content: String,
  ) {
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
    path.toFile().setExecutable(true)
  }
}
