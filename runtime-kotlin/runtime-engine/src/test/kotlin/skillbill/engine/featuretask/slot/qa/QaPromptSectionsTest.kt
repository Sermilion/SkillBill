package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.model.execution.QaDeclaredTarget
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.qa.report.qaTestExpected
import skillbill.model.FileLocation
import skillbill.ports.qa.model.QaWorkspace
import skillbill.scaffold.model.QaTargetKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class QaPromptSectionsTest {
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
