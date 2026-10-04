package skillbill.engine.featuretask.review.finding

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.engine.featuretask.runner.disposition
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertTrue

private fun boundaryMemoryTestRepo(): Path {
  val repo = Files.createTempDirectory("verify-findings-persisted-omits-current")
  val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
  Files.writeString(
    agent.resolve("history.md"),
    "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
  )
  return repo
}

private fun boundaryMemoryFindingRequests() =
  listOf(
    FeatureTaskRuntimeFindingBoundaryMemoryRequest(
      findingId = "F-001",
      findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
    ),
    FeatureTaskRuntimeFindingBoundaryMemoryRequest(
      findingId = "F-002",
      findingPaths = listOf("runtime-kotlin/runtime-application/src/Bar.kt"),
    ),
  )

private fun verifiedDisposition(
  findingId: String,
  heading: FeatureTaskRuntimeVerificationBoundaryHeadingProvenance,
) = FeatureTaskRuntimeFindingVerificationDisposition(
  findingId = findingId,
  disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
  reason = "Matches intent",
  selectedBoundaryHeadings = listOf(heading),
)

internal fun assertPersistedBoundarySelectionsCoverCurrentFindings(
  memory: FeatureTaskRuntimeFindingVerificationBoundaryMemory,
) {
  val repo = boundaryMemoryTestRepo()
  val sections = memory.sectionsForFindings(repo, boundaryMemoryFindingRequests())
  val catalogByFindingId = sections.associate { it.findingId to it.discovery.boundaryCatalog.single() }
  val dispositions =
    listOf("F-001", "F-002").map { findingId ->
      val catalog = catalogByFindingId.getValue(findingId)
      verifiedDisposition(
        findingId,
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = catalog.headingId,
          sourcePath = catalog.sourcePath,
        ),
      )
    }
  val persisted =
    mapOf(
      "F-001" to
        listOf(
          FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
            headingId = catalogByFindingId.getValue("F-001").headingId,
            sourcePath = catalogByFindingId.getValue("F-001").sourcePath,
          ),
        ),
    )
  val reason =
    memory.validateBoundarySelectionsDelivered(
      sections = sections,
      dispositions = dispositions,
      persisted = persisted,
    )
  assertTrue(
    reason != null &&
      reason.contains("F-002") &&
      reason.contains("not yet"),
  )
}
