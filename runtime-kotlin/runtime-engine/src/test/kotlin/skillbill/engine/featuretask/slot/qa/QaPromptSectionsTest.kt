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
}
