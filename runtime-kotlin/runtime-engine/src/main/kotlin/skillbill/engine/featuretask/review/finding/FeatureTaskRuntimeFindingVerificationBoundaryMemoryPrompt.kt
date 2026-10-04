package skillbill.engine.featuretask.review.finding

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import java.nio.file.Path

fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.validateDispositionBoundaryBodies(
  repoRoot: Path,
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  persistedSelections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = null,
): String? {
  val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
  return dispositions.firstNotNullOfOrNull { disposition ->
    dispositionBoundaryBodyFailure(
      repoRoot = repoRoot,
      section = sectionByFindingId[disposition.findingId],
      disposition = disposition,
      persistedSelections = persistedSelections,
    )
  }
}

private fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.dispositionBoundaryBodyFailure(
  repoRoot: Path,
  section: FeatureTaskRuntimeFindingBoundaryMemorySection?,
  disposition: FeatureTaskRuntimeFindingVerificationDisposition,
  persistedSelections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?,
): String? {
  if (section == null || section.discovery.boundaryContextUnavailable) return null
  val selections =
    persistedSelections?.get(disposition.findingId)
      ?: catalogValidatedBoundaryHeadings(section.discovery.boundaryCatalog, disposition.selectedBoundaryHeadings)
  if (selections.isEmpty()) return null
  return try {
    resolveSelectedBodies(
      repoRoot = repoRoot,
      catalog = section.discovery.boundaryCatalog,
      selectedHeadingIds = selections.map(FeatureTaskRuntimeVerificationBoundaryHeadingProvenance::headingId),
    )
    null
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.code == WorkflowFailureCode.GOAL_VERIFICATION_BOUNDARY_CAP_EXCEEDED)
    error.message ?: "finding verification boundary body resolution exceeded a verification cap."
  }
}

fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.resolvedBodiesPromptSection(
  repoRoot: Path,
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  selectionsByFindingId: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
): String {
  if (selectionsByFindingId.isEmpty()) return ""
  val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
  val validatedSelections = validatePersistedBoundarySelectionsAgainstCatalog(sections, selectionsByFindingId)
  if (validatedSelections.isEmpty()) return ""
  return buildString {
    appendLine()
    appendLine("## Selected boundary memory (verify_findings)")
    appendLine(
      "Resolved entry bodies for the heading ids you selected. Unselected bodies and whole " +
        "history.md or decisions.md files are absent by design.",
    )
    validatedSelections.forEach { (findingId, selections) ->
      val section = sectionByFindingId[findingId] ?: return@forEach
      val resolved =
        resolveSelectedBodies(
          repoRoot = repoRoot,
          catalog = section.discovery.boundaryCatalog,
          selectedHeadingIds = selections.map(FeatureTaskRuntimeVerificationBoundaryHeadingProvenance::headingId),
        )
      appendLine()
      appendLine("### Finding $findingId")
      for (body in resolved.bodies) {
        appendLine("#### ${body.headingId}")
        appendLine(body.heading)
        appendLine(body.body)
      }
      if (resolved.truncated) {
        appendLine(
          "Truncated: at least one body above exceeded max_body_bytes " +
            "(${GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION.maxBodyBytes}) and ends at that limit.",
        )
      }
      if (resolved.unresolvedHeadingIds.isNotEmpty()) {
        appendLine(
          "Unresolved selections (no body delivered): ${resolved.unresolvedHeadingIds.joinToString(", ")}",
        )
      }
    }
  }
}

fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.promptSection(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
): String {
  if (sections.isEmpty()) return ""
  return buildString {
    appendLine()
    appendLine("## Scoped boundary memory (verify_findings)")
    appendLine(
      "Each finding below carries a titles-only heading catalog scoped to the boundaries that own " +
        "its paths. Boundary memory is optional supporting evidence only: your disposition still " +
        "settles from spec intent even when you omit selected_boundary_headings or when the runtime " +
        "ignores an invalid selection. When you cite boundary memory, copy heading_id and source_path " +
        "verbatim from this finding's boundary_catalog only — never invent hashes, reuse another " +
        "finding's catalog, or prefix a heading_id with a different source_path. Only selected entry " +
        "bodies are resolved after settlement; unselected bodies and whole history.md or decisions.md " +
        "files never belong in this briefing.",
    )
    for (section in sections) {
      appendLine()
      appendLine("### Finding ${section.findingId}")
      if (section.discovery.boundaryContextUnavailable) {
        appendLine("boundary_context_unavailable: true")
        appendLine("Proceed intent-only; no eligible boundary owns this finding's paths.")
      } else {
        appendLine("boundary_catalog:")
        appendLine(
          JsonCodec.mapToJsonString(
            mapOf(
              "headings" to
                section.discovery.boundaryCatalog.map { heading ->
                  mapOf(
                    "heading_id" to heading.headingId,
                    "source_path" to heading.sourcePath,
                    "kind" to heading.kind.wireValue,
                    "heading" to heading.heading,
                  )
                },
              "truncated" to section.discovery.boundaryCatalogTruncated,
            ),
          ),
        )
      }
    }
  }
}
