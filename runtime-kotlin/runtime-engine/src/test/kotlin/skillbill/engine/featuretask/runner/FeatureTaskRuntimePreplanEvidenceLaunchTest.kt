package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.slot.validJsonOutput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class FeatureTaskRuntimePreplanEvidenceLaunchTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-preplan-evidence-repo")

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
  }

  @Test
  fun `plan launches with the preplan evidence file in place of the pasted digest`() {
    Files.createDirectories(repoRoot.resolve("src"))
    Files.writeString(repoRoot.resolve("src/Seam.kt"), "package seam\nfun seamToChange() = Unit\nfun other() = Unit\n")
    var planPrompt: String? = null
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          repoRoot = repoRoot,
          hydratedPlanning = false,
          launcher =
            RuntimeRecordingLauncher { request ->
              val prompt = requireNotNull(request.skillRunRequest.promptOverride)
              val phaseId = phaseIdFromPrompt(prompt)
              if (phaseId == "plan" && planPrompt == null) planPrompt = prompt
              facts(if (phaseId == "preplan") PREPLAN_WITH_EVIDENCE else validJsonOutput(phaseId))
            },
        ),
      )

    harness.runner.run(harness.request())

    val prompt = assertNotNull(planPrompt)
    assertContains(prompt, "## Preplan evidence (layer 2, file)")
    assertFalse(prompt.contains(DIGEST_SENTINEL), "the digest must reach plan through the file, not the prompt")
    val evidencePath =
      assertNotNull(prompt.lineSequence().firstOrNull { it.startsWith("path: ") }).removePrefix("path: ")
    val evidence = Files.readString(Path.of(evidencePath))
    assertContains(evidence, DIGEST_SENTINEL)
    assertContains(evidence, "2  fun seamToChange() = Unit")
    assertFalse(evidence.contains("fun other()"))
  }
}

private const val DIGEST_SENTINEL = "SEAM-DIGEST-SENTINEL"

private const val PREPLAN_WITH_EVIDENCE =
  """{"contract_version":"0.4","phase_id":"preplan","status":"completed","summary":"Preplan digest.",""" +
    """"produced_outputs":{"value":"$DIGEST_SENTINEL: change seamToChange.\n\nEVIDENCE\n- src/Seam.kt:2-2"}}"""
