package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject

@Inject
class FeatureTaskRuntimeControlSubcommands(
  status: FeatureTaskRuntimeStatusCommand,
  resume: FeatureTaskRuntimeResumeCommand,
  abandon: FeatureTaskRuntimeAbandonCommand,
  retryBlocked: FeatureTaskRuntimeRetryBlockedCommand,
  repairIdentity: FeatureTaskRuntimeRepairIdentityCommand,
  lookup: FeatureTaskLookupCommand,
) {
  val commands: List<CliktCommand> = listOf(status, resume, abandon, retryBlocked, repairIdentity, lookup)
}

@Inject
class FeatureTaskRejectedOutputSubcommands(
  inspect: RejectedOutputInspectCliCommand,
  cleanup: RejectedOutputCleanupCliCommand,
) {
  val commands: List<CliktCommand> = listOf(inspect, cleanup)
}
