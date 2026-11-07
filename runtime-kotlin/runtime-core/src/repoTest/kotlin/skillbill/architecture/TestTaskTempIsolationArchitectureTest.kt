package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestTaskTempIsolationArchitectureTest {
  @Test
  fun `runtime build isolates test tasks and wrapper`() {
    val root = ArchitectureScanSupport.runtimeRoot
    assertEquals(
      emptyList(),
      TestTaskTempIsolationScan.violations { path ->
        val file = root.resolve(path)
        file.takeIf(Files::isRegularFile)?.let(Files::readString)
      },
    )
  }

  @Test
  fun `missing settings and inputs fail with their source paths`() {
    val jvmPath = "runtime-kotlin/build-logic/convention/src/main/kotlin/dev/skillbill/runtime/buildlogic/Jvm.kt"
    val buildPath = "runtime-kotlin/build-logic/convention/build.gradle.kts"
    val clean =
      mapOf(
        jvmPath to
          "fun Project.configureKotlinJvmTestDefaults() { isolateJvmTempDir() }\n-Djava.io.tmpdir=",
        buildPath to
          "tasks.withType<Test>().configureEach { jvmArgs(\"-Djava.io.tmpdir=x\") }",
        "runtime-kotlin/gradlew" to "skill_bill_jvm_temp_root=",
      )
    assertEquals(emptyList(), TestTaskTempIsolationScan.violations(clean::get))
    val cases =
      listOf(
        clean + (
          jvmPath to (
            "fun configureKotlinJvmTestDefaults() {}\n" +
              "private fun isolateJvmTempDir() {}\n-Djava.io.tmpdir="
          )
        ),
        clean + (jvmPath to "fun configureKotlinJvmTestDefaults() { isolateJvmTempDir() }"),
        clean + (buildPath to "tasks.withType<Test>().configureEach { jvmArgs(\"other\") }"),
        clean + ("runtime-kotlin/gradlew" to "wrapper without marker"),
        clean - "runtime-kotlin/gradlew",
        clean - "runtime-kotlin/build-logic/convention/build.gradle.kts",
      )
    cases.forEach { fixture -> assertTrue(TestTaskTempIsolationScan.violations(fixture::get).isNotEmpty()) }
  }
}
