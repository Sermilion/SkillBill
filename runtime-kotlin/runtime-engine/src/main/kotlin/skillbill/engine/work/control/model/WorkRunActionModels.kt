package skillbill.engine.work.control.model

import skillbill.ports.filesystem.model.DatabaseFileObservation
import java.nio.file.Path
import java.time.Instant

public data class WorkRunTarget(
  val id: String,
  val repositoryIdentity: String,
  val repositoryRoot: Path,
  val executionId: String?,
  val statusRevision: String?,
  val updatedAt: Instant,
  val databaseFileObservation: DatabaseFileObservation,
)

public data class WorkRunOptions(
  val canCancel: Boolean,
  val canLaunch: Boolean,
  val launchLabel: String,
  val agentId: String?,
  val agentChoices: List<String>,
  val unavailableReason: String? = null,
)
