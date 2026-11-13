package skillbill.engine.work.verify

import me.tatarka.inject.annotations.Inject
import skillbill.engine.operation.verify.VerifyWorkflow
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.VerifyCriterionResolution
import skillbill.ports.idestatus.model.VerifyGradeStatus
import skillbill.ports.workflow.model.WorkflowFamily
import java.time.Clock

@Inject
public class VerifyCriterionResolutionService(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
) {
  public fun resolve(
    workflowId: String,
    criterionNumber: Int,
    evidence: String,
  ): VerifyCriterionResolveResult =
    database.transaction { unit ->
      val snapshot =
        unit.workflowStates.get(WorkflowFamily.VERIFY, workflowId)
          ?: return@transaction VerifyCriterionResolveResult.WorkflowNotFound
      val grades =
        VerifyCriteriaParser.completeness(snapshot.artifacts[VerifyWorkflow.COMPLETENESS_AUDIT_RECEIPT])?.grades
          ?: return@transaction VerifyCriterionResolveResult.NotAudited
      val grade =
        grades.firstOrNull { it.number == criterionNumber }
          ?: return@transaction VerifyCriterionResolveResult.UnknownCriterion(grades.map { it.number })
      if (grade.status == VerifyGradeStatus.PASS) return@transaction VerifyCriterionResolveResult.AlreadyMet
      val resolution = VerifyCriterionResolution(criterionNumber, evidence.trim(), clock.instant())
      unit.verifyCriterionResolutions.record(workflowId, resolution)
      VerifyCriterionResolveResult.Recorded(resolution)
    }
}

public sealed interface VerifyCriterionResolveResult {
  public data class Recorded(val resolution: VerifyCriterionResolution) : VerifyCriterionResolveResult

  public data object WorkflowNotFound : VerifyCriterionResolveResult

  public data object NotAudited : VerifyCriterionResolveResult

  public data class UnknownCriterion(val graded: List<Int>) : VerifyCriterionResolveResult

  public data object AlreadyMet : VerifyCriterionResolveResult
}
