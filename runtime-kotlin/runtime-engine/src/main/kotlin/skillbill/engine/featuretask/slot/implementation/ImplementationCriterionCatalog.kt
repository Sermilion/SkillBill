package skillbill.engine.featuretask.slot.implementation

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.planning.PlanBundleSpecText
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.attempt.PhaseImplementationTurnAccess
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.projectstore.model.resolveSpecPath
import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnRecord
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeImplementationTurnRecords
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.io.IOException
import java.nio.file.InvalidPathException

internal data class ImplementationCriterion(
  val id: String,
  val text: String,
)

internal sealed interface ImplementationCriterionCatalog {
  data object Legacy : ImplementationCriterionCatalog

  data class Criteria(
    val criteria: List<ImplementationCriterion>,
  ) : ImplementationCriterionCatalog {
    val ids: List<String> get() = criteria.map(ImplementationCriterion::id)

    fun criterion(id: String): ImplementationCriterion? = criteria.firstOrNull { it.id == id }
  }

  data class Invalid(
    val reason: String,
  ) : ImplementationCriterionCatalog

  companion object {
    fun of(
      run: PhaseRun,
      access: PhaseImplementationTurnAccess,
    ): ImplementationCriterionCatalog {
      if (!criterionModeEligible(run)) return Legacy
      val specReference = run.request.runInvariants.specReference
      val specText = selectedSpecText(run, access) ?: return Legacy
      val authored = PlanBundleSpecText.acceptanceCriteriaOf(specText)
      if (authored.isEmpty()) return Legacy
      return resolve(authored, specReference, run.request.runInvariants.acceptanceCriteria)
    }

    private fun criterionModeEligible(run: PhaseRun): Boolean =
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        run.reentry == null &&
        run.request.workflowId.isNotBlank() &&
        (run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair == null

    private fun selectedSpecText(
      run: PhaseRun,
      access: PhaseImplementationTurnAccess,
    ): String? =
      try {
        access.specText(
          resolveSpecPath(run.projectStoreRoot, run.request.repoRoot, run.request.runInvariants.specReference)
            .normalize(),
        )
      } catch (_: IOException) {
        null
      } catch (_: InvalidPathException) {
        null
      }

    private fun resolve(
      authored: List<String>,
      specReference: String,
      invariantCriteria: List<String>,
    ): ImplementationCriterionCatalog {
      if (authored.size > MAX_CRITERIA) {
        return Invalid("The spec '$specReference' authors more than $MAX_CRITERIA criteria.")
      }
      val criteria =
        authored.mapIndexed { index, text ->
          acceptanceCriterionIdentity(text, index + 1)?.let { ImplementationCriterion(it.canonicalRef, text.trim()) }
        }
      val unresolved = criteria.indexOfFirst { it == null }
      val ids = criteria.map { it?.id }
      val invariantIds = identitiesOf(invariantCriteria)
      val reason =
        when {
          unresolved >= 0 -> "Criterion ${unresolved + 1} of '$specReference' has no resolvable identity."
          ids.distinct().size != ids.size -> "The spec '$specReference' authors duplicate criterion identities $ids."
          invariantIds != ids ->
            "The spec '$specReference' authors criteria $ids but the run invariants carry $invariantIds; " +
              "implement cannot tell which catalog to assign."
          else -> null
        }
      return reason?.let(::Invalid) ?: Criteria(criteria.filterNotNull())
    }

    private const val MAX_CRITERIA: Int = 999

    private fun identitiesOf(criteria: List<String>): List<String?> =
      criteria.mapIndexed { index, text ->
        if (index < MAX_CRITERIA) acceptanceCriterionIdentity(text, index + 1)?.canonicalRef else null
      }
  }
}

internal data class ImplementationCriterionLedger(
  val catalog: ImplementationCriterionCatalog.Criteria,
  val stored: FeatureTaskRuntimeImplementationProgress?,
  val records: List<FeatureTaskRuntimeImplementationTurnRecord>,
) {
  val progress: FeatureTaskRuntimeImplementationProgress
    get() = stored ?: FeatureTaskRuntimeImplementationProgress.initial(catalog.ids)

  companion object {
    fun load(
      catalog: ImplementationCriterionCatalog.Criteria,
      access: PhaseImplementationTurnAccess,
      workflowId: String,
    ): ImplementationCriterionLedgerRead {
      val stored =
        try {
          access.implementationRecords.loadImplementationProgress(workflowId)
        } catch (error: SkillBillRuntimeException) {
          return ImplementationCriterionLedgerRead.Unreadable(error.message.orEmpty())
        }
      if (stored != null && stored.criterionIds != catalog.ids) {
        return ImplementationCriterionLedgerRead.Unreadable(
          "Recorded implementation progress names criteria ${stored.criterionIds} but the selected spec " +
            "authors ${catalog.ids}.",
        )
      }
      val attempts = access.implementationRecords.loadImplementationAttempts(workflowId).orEmpty()
      return ImplementationCriterionLedgerRead.Read(
        ImplementationCriterionLedger(catalog, stored, featureTaskRuntimeImplementationTurnRecords(attempts)),
      )
    }
  }
}

internal sealed interface ImplementationCriterionLedgerRead {
  data class Read(
    val ledger: ImplementationCriterionLedger,
  ) : ImplementationCriterionLedgerRead

  data class Unreadable(
    val reason: String,
  ) : ImplementationCriterionLedgerRead
}
