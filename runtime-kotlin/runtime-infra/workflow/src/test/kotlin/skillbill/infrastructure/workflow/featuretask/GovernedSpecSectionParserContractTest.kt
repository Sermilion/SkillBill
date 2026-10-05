package skillbill.infrastructure.workflow.featuretask

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GovernedSpecSectionParserContractTest {
  @Test
  fun `a spec accepted by the invariants reader yields the same ordered acceptance criteria from the shared parser`() {
    val spec =
      Files.createTempDirectory("governed-spec-parser").resolve("spec.md").also { path ->
        Files.writeString(
          path,
          """
          # Runtime spec

          ## Acceptance Criteria
          1. Tests cover, at minimum:
             - running duplicate protection;
             - repository isolation.
          2. Maintainer validation passes.
          """.trimIndent(),
        )
      }
    val fromReader =
      (FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec) as FeatureTaskRuntimeRunInvariantsRead.Read)
        .invariants.acceptanceCriteria
    val fromParser =
      GovernedSpecSectionParser.parseListSection(Files.readString(spec)) {
        it.startsWith(ACCEPTANCE_CRITERIA_PREFIX)
      }
    assertEquals(fromReader, fromParser)
  }
}
