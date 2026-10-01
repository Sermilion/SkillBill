package skillbill.cli.model

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import java.nio.file.Path

data class CliRunInputs(
  val databasePath: String?,
  val environment: Map<String, String>,
  val userHome: Path,
  val repositoryRoot: Path,
  val featureTaskRuntimeRunOverride: ((FeatureTaskRuntimeRunInput) -> FeatureTaskRuntimeRunReport)? = null,
  val liveStdout: (String) -> Unit,
  val liveStderr: (String) -> Unit,
)
