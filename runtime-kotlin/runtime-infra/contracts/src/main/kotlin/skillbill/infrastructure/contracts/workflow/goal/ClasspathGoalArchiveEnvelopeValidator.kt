package skillbill.infrastructure.contracts.workflow.goal

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.goal.GoalArchiveEnvelopeKeys
import skillbill.contracts.workflow.goal.goalArchiveEnvelopeWireMap
import skillbill.ports.goalrunner.GoalArchiveEnvelopeValidator
import skillbill.ports.goalrunner.model.GoalArchiveEnvelope

@Inject
class ClasspathGoalArchiveEnvelopeValidator : GoalArchiveEnvelopeValidator {
  override fun validate(envelope: GoalArchiveEnvelope) {
    GoalArchiveSchemaValidator.validate(
      goalArchiveEnvelopeWireMap(
        contractVersion = envelope.contractVersion,
        issueKey = envelope.issueKey,
        featureBranch = envelope.featureBranch,
        commitSha = envelope.commitSha,
        exportedAt = envelope.exportedAt,
      ),
      GoalArchiveEnvelopeKeys.ARCHIVE_JSON,
    )
  }
}
