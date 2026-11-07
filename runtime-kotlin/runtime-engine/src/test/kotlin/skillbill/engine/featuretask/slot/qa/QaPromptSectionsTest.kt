package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.model.execution.QaDeclaredTarget
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.qa.report.qaTestExpected
import skillbill.model.FileLocation
import skillbill.ports.qa.model.QaWorkspace
import skillbill.scaffold.model.QaTargetKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaPromptSectionsTest {
  @Test
  fun `named serial and avd rules follow the lease block while no selection adds no line`() {
    val directory = Path.of("/tmp/qa-named-device")

    fun supplement(device: QaNamedDevice?) =
      QaPromptSections.supplement(
        QaExecutionContext(
          workspace = QaWorkspace("phr-qa-named", directory, directory.resolve("report.md"), "$directory/"),
          expected = qaTestExpected(),
          target =
            QaExecutionTarget.Supported(
              listOf(
                QaDeclaredTarget("kmp", setOf(QaTargetKind("android")), FileLocation("/packs/kmp/qa.md")),
              ),
              Path.of("/tmp/qa-checkout"),
            ),
          namedDevice = device,
        ),
      )

    val serial = supplement(QaNamedDevice(QaNamedDeviceKind.SERIAL, "R58M123"))
    val shellSerial = supplement(QaNamedDevice(QaNamedDeviceKind.SERIAL, "R58M'\$USER"))
    val avd = supplement(QaNamedDevice(QaNamedDeviceKind.AVD, "Pixel_8_API_35"))
    val withoutDevice = supplement(null)

    assertContains(serial, "Use it for `adb -s 'R58M123'`")
    assertContains(shellSerial, "Use it for `adb -s 'R58M'\"'\"'\$USER'`")
    assertContains(serial, "unauthorized, offline, or leased by another run")
    assertContains(avd, "AVD `Pixel_8_API_35`")
    assertContains(avd, "boot it by the pack's emulator-boot steps")
    assertFalse("operator named" in withoutDevice)
    listOf(serial, avd).forEach { selected ->
      assertEquals(withoutDevice, selected.replace(Regex("\n- The operator named[^\n]*"), ""))
      assertTrue(selected.indexOf("operator named") > selected.indexOf("qa lease release"))
    }
  }

  @Test
  fun `unblock and detached process guidance reaches every policy and target kind`() {
    val directory = Path.of("/tmp/qa-unblock")
    val targets =
      listOf(
        QaDeclaredTarget("backend", setOf(QaTargetKind("backend")), FileLocation("/packs/backend/qa.md")),
        QaDeclaredTarget("kmp", setOf(QaTargetKind("kmp"), QaTargetKind("android")), FileLocation("/packs/kmp/qa.md")),
      )

    listOf(QaPolicy.STANDALONE, QaPolicy.AUTO, QaPolicy.ON).forEach { policy ->
      val qa =
        QaExecutionContext(
          workspace = QaWorkspace("phr-qa-unblock", directory, directory.resolve("report.md"), "$directory/"),
          expected = qaTestExpected(),
          target = QaExecutionTarget.Supported(targets, Path.of("/tmp/qa-checkout")),
          policy = policy,
        )
      val supplement = QaPromptSections.supplement(qa).replace(Regex("\\s+"), " ")

      assertContains(supplement, "## Unblock")
      assertContains(supplement, "#### <capability>")
      assertContains(supplement, "- Target:")
      assertContains(supplement, "- Tried:")
      assertContains(supplement, "- Evidence:")
      assertContains(supplement, "- Remedy:")
      assertContains(supplement, ".skill-bill/phases/qa.md")
      assertContains(supplement, "Never start a line inside the block with `#`")
      assertContains(supplement, "setsid nohup")
    }
  }

  @Test
  fun `only standalone QA gets the progress block, and existing rules keep their wording`() {
    val directory = Path.of("/tmp/qa-progress")

    fun context(policy: QaPolicy) =
      QaExecutionContext(
        workspace = QaWorkspace("phr-qa-progress", directory, directory.resolve("report.md"), "$directory/"),
        expected = qaTestExpected(),
        target =
          QaExecutionTarget.Supported(
            targets =
              listOf(QaDeclaredTarget("web", setOf(QaTargetKind("web")), FileLocation("/packs/web/qa/guide.md"))),
            checkout = Path.of("/tmp/qa-checkout"),
          ),
        policy = policy,
      )

    val standalone = QaPromptSections.supplement(context(QaPolicy.STANDALONE))

    assertContains(standalone, "skill-bill qa plan --invocation phr-qa-progress --file <path>")
    assertContains(standalone, "\"contract_version\": \"0.1\"")
    assertContains(standalone, "Progress commands never decide the verdict")
    assertContains(standalone, "Edit nothing outside the QA checkout and the invocation directory")
    assertContains(standalone, "`- [passed|failed|not_exercised] <title>: <detail>. Evidence: <paths>`")
    assertContains(standalone, "`Steps:`, `Expected:`, `Observed:` and `Evidence:`")
    assertContains(standalone, "`[not restored]` only when the edit outlives the QA checkout")
    listOf(QaPolicy.AUTO, QaPolicy.ON).forEach { policy ->
      assertFalse("skill-bill qa plan" in QaPromptSections.supplement(context(policy)))
    }
  }

  @Test
  fun `the QA supplement names the guidance path and kinds of every declared target`() {
    val directory = Path.of("/tmp/qa-invocation")
    val qa =
      QaExecutionContext(
        workspace = QaWorkspace("phr-qa-test", directory, directory.resolve("report.md"), "$directory/"),
        expected = qaTestExpected(),
        target =
          QaExecutionTarget.Supported(
            targets =
              listOf(
                QaDeclaredTarget("backend", setOf(QaTargetKind("backend")), FileLocation("/packs/backend/qa/guide.md")),
                QaDeclaredTarget(
                  "web",
                  setOf(QaTargetKind("web"), QaTargetKind("desktop")),
                  FileLocation("/packs/web/qa/guide.md"),
                ),
              ),
            checkout = Path.of("/tmp/qa-checkout"),
          ),
      )

    val supplement = QaPromptSections.supplement(qa)

    assertContains(
      supplement,
      "- Target `backend`: kinds backend; guidance `/packs/backend/qa/guide.md`. Read it first and follow it.",
    )
    assertContains(
      supplement,
      "- Target `web`: kinds web, desktop; guidance `/packs/web/qa/guide.md`. Read it first and follow it.",
    )
    assertContains(supplement, "- Report coverage for every kind listed above.")
  }

  @Test
  fun `desktop-only criteria do not require probing mobile targets while cross-platform criteria stay in scope`() {
    val directory = Path.of("/tmp/qa-desktop")
    val qa =
      QaExecutionContext(
        workspace = QaWorkspace("phr-qa-desktop", directory, directory.resolve("report.md"), "$directory/"),
        expected = qaTestExpected(),
        target =
          QaExecutionTarget.Supported(
            targets =
              listOf(
                QaDeclaredTarget(
                  "kmp",
                  setOf(QaTargetKind("android"), QaTargetKind("desktop")),
                  FileLocation("/packs/kmp/qa/kmp-qa.md"),
                ),
                QaDeclaredTarget("ios", setOf(QaTargetKind("ios")), FileLocation("/packs/ios/qa/ios-qa.md")),
              ),
            checkout = Path.of("/tmp/qa-checkout"),
          ),
      )

    val supplement = QaPromptSections.supplement(qa).replace(Regex("\\s+"), " ")

    assertContains(supplement, "map each acceptance criterion to the target kinds it actually describes")
    assertContains(supplement, "do not probe its tools or devices")
    assertContains(supplement, "Shared code alone does not put every dependent app in scope")
    assertContains(supplement, "Explicit cross-platform behavior or regression criteria do")
    assertFalse("`empty_scope` has no other use" in supplement)
  }

  @Test
  fun `device QA can use staging or defaults without satisfying backend isolation`() {
    val directory = Path.of("/tmp/qa-device")
    val qa =
      QaExecutionContext(
        workspace = QaWorkspace("phr-qa-device", directory, directory.resolve("report.md"), "$directory/"),
        expected = qaTestExpected(),
        target =
          QaExecutionTarget.Supported(
            targets =
              listOf(
                QaDeclaredTarget("kmp", setOf(QaTargetKind("android")), FileLocation("/packs/kmp/qa/android.md")),
                QaDeclaredTarget("backend", setOf(QaTargetKind("backend")), FileLocation("/packs/backend/qa.md")),
              ),
            checkout = Path.of("/tmp/qa-checkout"),
          ),
      )

    val supplement = QaPromptSections.supplement(qa).replace(Regex("\\s+"), " ")

    assertContains(supplement, "look for a staging environment switch in the app, build flavors, or configuration")
    assertContains(supplement, "Select staging when available; otherwise continue with the app's default environment")
    assertContains(supplement, "An existing signed-in session may be used")
    assertContains(supplement, "synthetic account provenance is not a prerequisite")
    assertContains(supplement, "Do not require a local backend, disposable database, or outbound substitutes")
    assertContains(supplement, "These requirements apply only to backend and web targets, not device QA")
    assertContains(supplement, "A backend or web setup failure does not prevent testing an available device app")
    assertContains(supplement, "Use fresh owned volumes")
    assertFalse("Never use production" in supplement)
  }
}
