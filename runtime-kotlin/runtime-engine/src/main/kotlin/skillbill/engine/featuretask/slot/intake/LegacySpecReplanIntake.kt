package skillbill.engine.featuretask.slot.intake

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phaserun.LegacySpecReplanSource
import java.nio.file.Path

@Inject
class LegacySpecReplanIntake(
  private val legacySpecIntake: LegacySpecIntake,
) : LegacySpecReplanSource {
  override fun replanIntake(
    specPath: Path,
    issueKey: String,
    repoRoot: Path,
  ): String? = legacySpecIntake.route(specPath, issueKey, repoRoot).replanIntake()

  override fun replanIntakeForIssueKey(
    issueKey: String,
    repoRoot: Path,
  ): String? = legacySpecIntake.routeIssueKey(issueKey, repoRoot).replanIntake()

  private fun LegacySpecRoute.replanIntake(): String? = (this as? LegacySpecRoute.Replan)?.intake
}
