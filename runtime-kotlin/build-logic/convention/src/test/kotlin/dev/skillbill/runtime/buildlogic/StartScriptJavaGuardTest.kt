package dev.skillbill.runtime.buildlogic

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val ANCHOR = "# Determine the Java command to use to start the JVM."

private const val GUARD = "skill_bill_required_java_major=21\n"

class StartScriptJavaGuardTest {
  @Test
  fun `the guard is inserted once before the anchor and survives a regenerated script`() {
    val generated = "#!/bin/sh\n\n$ANCHOR\nJAVACMD=java\n"

    val patched = startScriptWithJavaGuard(generated, GUARD, "skill-bill")
    val repatched = startScriptWithJavaGuard(patched, GUARD, "skill-bill")

    assertEquals("#!/bin/sh\n\n$GUARD$ANCHOR\nJAVACMD=java\n", patched)
    assertEquals(
      1,
      Regex("skill_bill_required_java_major=").findAll(repatched).count(),
      "A second guard body lets the launcher resolve JAVA_HOME twice under conflicting rules.",
    )
  }

  @Test
  fun `the run temp root snippet follows the shebang on both launcher forms and is inserted once`() {
    val forms =
      listOf(
        "#!/bin/sh\n\n$ANCHOR\nJAVACMD=java\n",
        "#!/usr/bin/env sh\nJAVA_HOME=\"\$APP_HOME\"\nexec java\n",
      )

    forms.forEach { form ->
      val guarded = startScriptWithJavaGuard(form, GUARD, "skill-bill")
      val patched = startScriptWithRunTempRoot(guarded, "skill-bill")

      assertEquals(listOf(form.substringBefore('\n'), "skill_bill_run_temp_root="), patched.lines().take(2))
      assertEquals(patched, startScriptWithRunTempRoot(patched, "skill-bill"))
      assertEquals(1, Regex("(?m)^skill_bill_run_temp_root=\$").findAll(patched).count())
    }
  }

  @Test
  fun `a launcher without a shebang fails loudly instead of shipping without the snippet`() {
    assertThrows<GradleException> { startScriptWithRunTempRoot("exec java\n", "skill-bill") }
    assertThrows<GradleException> { startScriptWithRunTempRoot("#!/bin/sh", "skill-bill") }
  }

  @Test
  fun `the snippet passes only a usable run scratch directory to JAVA_OPTS`(
    @TempDir workspace: File,
  ) {
    val scratch = workspace.resolve("scratch").apply { mkdirs() }
    val unsafe = workspace.resolve("sc\$ratch").apply { mkdirs() }
    val state = workspace.resolve("state")

    assertEquals(
      " \"-Djava.io.tmpdir=$scratch\"",
      javaOptsAfterSnippet(scratch.path, state, javaToolOptions = "-Djava.io.tmpdir=/elsewhere"),
    )
    assertEquals("", javaOptsAfterSnippet(unsafe.path, state, javaToolOptions = "-Djava.io.tmpdir=/elsewhere"))
    assertEquals("", javaOptsAfterSnippet(workspace.resolve("gone").path, state, "-Djava.io.tmpdir=/elsewhere"))
  }

  @Test
  fun `outside a run on Linux the snippet falls back to the state scratch directory`(
    @TempDir workspace: File,
  ) {
    assumeTrue(System.getProperty("os.name") == "Linux")
    val state = workspace.resolve("state")

    assertEquals(
      " \"-Djava.io.tmpdir=$state/scratch/jvm\"",
      javaOptsAfterSnippet(runScratch = "", state, javaToolOptions = ""),
    )
    assertTrue(state.resolve("scratch/jvm").isDirectory)
  }

  private fun javaOptsAfterSnippet(
    runScratch: String,
    state: File,
    javaToolOptions: String,
  ): String {
    val script = startScriptWithRunTempRoot("#!/bin/sh\nprintf '%s' \"\$JAVA_OPTS\"\n", "skill-bill")
    val process =
      ProcessBuilder("sh", "-c", script)
        .apply {
          environment().remove("JAVA_OPTS")
          environment()["SKILL_BILL_RUN_SCRATCH_DIR"] = runScratch
          environment()["SKILL_BILL_STATE_DIR"] = state.path
          environment()["JAVA_TOOL_OPTIONS"] = javaToolOptions
        }.start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor())
    return output
  }

  @Test
  fun `a launcher without the guard anchor fails loudly`() {
    assertThrows<GradleException> {
      startScriptWithJavaGuard("#!/bin/sh\nexec java -jar app.jar\n", GUARD, "skill-bill")
    }
  }
}
