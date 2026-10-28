package dev.skillbill.desktop.buildlogic

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private const val COROUTINES = "org.jetbrains.kotlinx:kotlinx-coroutines-core"
private const val STDLIB = "org.jetbrains.kotlin:kotlin-stdlib"

class SharedRuntimeVersionsTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `a runtime coroutines bump fails the check until the desktop catalog follows`() {
    val check = ProjectBuilder.builder().withProjectDir(directory).build()
      .tasks.register("verifySharedRuntimeVersions", VerifySharedRuntimeVersionsTask::class.java).get()
    check.runtimeVersions.set(mapOf(COROUTINES to "1.10.3", STDLIB to "2.4.21"))
    check.desktopVersions.set(mapOf(COROUTINES to "1.10.2", STDLIB to "2.4.21", "org.jetbrains.compose.ui:ui" to "1.12.1"))

    val drift = assertFailsWith<GradleException> { check.verify() }

    assertEquals(
      "The desktop resolves shared libraries at other versions than the runtime:\n" +
        "  $COROUTINES: runtime 1.10.3, desktop 1.10.2\n" +
        "Align desktop/gradle/libs.versions.toml with runtime-kotlin/gradle/libs.versions.toml.",
      drift.message,
    )

    check.desktopVersions.set(mapOf(COROUTINES to "1.10.3", STDLIB to "2.4.21", "org.jetbrains.compose.ui:ui" to "1.12.1"))

    check.verify()
  }
}
