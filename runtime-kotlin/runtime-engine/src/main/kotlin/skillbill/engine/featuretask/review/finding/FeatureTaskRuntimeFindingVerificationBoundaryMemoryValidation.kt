package skillbill.engine.featuretask.review.finding

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

public fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.selectionsRequiringBodyDelivery(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>> {
  val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
  return dispositions.mapNotNull { disposition ->
    val section = sectionByFindingId[disposition.findingId] ?: return@mapNotNull null
    if (section.discovery.boundaryContextUnavailable) return@mapNotNull null
    val validated =
      catalogValidatedBoundaryHeadings(
        catalog = section.discovery.boundaryCatalog,
        selected = disposition.selectedBoundaryHeadings,
      )
    if (validated.isEmpty()) return@mapNotNull null
    disposition.findingId to validated
  }.toMap()
}

public fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.validateDispositionBoundaryContext(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
): String? {
  val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
  return dispositions.firstNotNullOfOrNull { disposition ->
    dispositionBoundaryContextFailure(sectionByFindingId[disposition.findingId], disposition)
  }
}

public fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.validateDispositionBoundaryProvenance(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
): String? =
  when {
    sections.isEmpty() && dispositions.isEmpty() -> null
    else -> null
  }

public fun catalogValidatedBoundaryHeadings(
  catalog: List<GoalPlanningBoundaryHeading>,
  selected: List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>,
): List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance> {
  if (selected.isEmpty()) return emptyList()
  val byId = catalog.associateBy(GoalPlanningBoundaryHeading::headingId)
  return selected.filter { heading -> byId[heading.headingId]?.sourcePath == heading.sourcePath }
}

public fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.validatePersistedBoundarySelectionsMatch(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
): String? {
  val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
  return persisted.firstNotNullOfOrNull { (findingId, persistedForFinding) ->
    val disposition =
      dispositions.firstOrNull { it.findingId == findingId }
        ?: return@firstNotNullOfOrNull (
          "finding verification disposition must cover every finding whose boundary headings were " +
            "delivered; missing finding_id $findingId."
        )
    val catalog = sectionByFindingId[findingId]?.discovery?.boundaryCatalog.orEmpty()
    val validated = catalogValidatedBoundaryHeadings(catalog, disposition.selectedBoundaryHeadings)
    if (!boundaryHeadingsMatch(persistedForFinding, validated)) {
      "finding verification disposition for $findingId must reuse the persisted " +
        "selected_boundary_headings verbatim; do not change heading selections after the initial settlement pass."
    } else {
      null
    }
  }
}

public fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.validatePersistedBoundarySelectionsAgainstCatalog(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>> {
  val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
  return persisted.mapNotNull { (findingId, selections) ->
    val catalog = sectionByFindingId[findingId]?.discovery?.boundaryCatalog.orEmpty()
    val validated = catalogValidatedBoundaryHeadings(catalog, selections)
    if (validated.isEmpty()) null else findingId to validated
  }.toMap()
}

public fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.validateBoundarySelectionsDelivered(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?,
): String? {
  val pending = selectionsRequiringBodyDelivery(sections, dispositions)
  return when {
    persisted != null -> validateDeliveredWithPersisted(sections, dispositions, persisted, pending)
    pending.isNotEmpty() ->
      "finding verification selected boundary headings but resolved entry bodies were not yet " +
        "delivered; re-read the briefing after the runtime records selections."
    else -> null
  }
}

internal fun boundaryHeadingsMatch(
  persisted: List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>,
  disposition: List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>,
): Boolean {
  if (persisted.size != disposition.size) return false
  return persisted.zip(disposition).all { (left, right) ->
    left.headingId == right.headingId && left.sourcePath == right.sourcePath
  }
}

internal fun dispositionBoundaryContextFailure(
  section: FeatureTaskRuntimeFindingBoundaryMemorySection?,
  disposition: FeatureTaskRuntimeFindingVerificationDisposition,
): String? {
  if (section == null || !section.discovery.boundaryContextUnavailable) return null
  return when {
    !disposition.boundaryContextUnavailable ->
      "finding verification disposition for ${disposition.findingId} must set " +
        "boundary_context_unavailable when no eligible boundary owns its paths."
    disposition.selectedBoundaryHeadings.isNotEmpty() ->
      "finding verification disposition for ${disposition.findingId} must not select " +
        "boundary headings when boundary context is unavailable."
    else -> null
  }
}
