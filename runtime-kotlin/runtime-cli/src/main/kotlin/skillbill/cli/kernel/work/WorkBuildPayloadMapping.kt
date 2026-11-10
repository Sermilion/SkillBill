package skillbill.cli.kernel.work

import skillbill.contracts.workflow.identity.status.WorkBuildPayloadKeys
import skillbill.ports.idestatus.model.WorkBuildView

internal fun workBuildWireMap(build: WorkBuildView): Map<String, Any?> =
  linkedMapOf(
    WorkBuildPayloadKeys.STATE to build.state.wireValue,
    WorkBuildPayloadKeys.COMMAND to build.command,
    WorkBuildPayloadKeys.SHORT_COMMAND to build.shortCommand,
    WorkBuildPayloadKeys.TOOL to build.tool,
    WorkBuildPayloadKeys.PROJECT_KIND to build.projectKind,
    WorkBuildPayloadKeys.PID to build.pid,
    WorkBuildPayloadKeys.STARTED_AT to build.startedAt.toString(),
    WorkBuildPayloadKeys.ELAPSED_MS to build.elapsedMs,
    WorkBuildPayloadKeys.ELAPSED_AS_OF to build.elapsedAsOf?.toString(),
    WorkBuildPayloadKeys.USUAL_MS to build.usualMs,
    WorkBuildPayloadKeys.SAMPLE_COUNT to build.sampleCount,
    WorkBuildPayloadKeys.EXIT_CODE to build.exitCode,
    WorkBuildPayloadKeys.FINISHED_AT to build.finishedAt?.toString(),
    WorkBuildPayloadKeys.PREVIOUS to
      build.previous?.let { previous ->
        linkedMapOf(
          WorkBuildPayloadKeys.STATE to previous.state.wireValue,
          WorkBuildPayloadKeys.DURATION_MS to previous.durationMs,
          WorkBuildPayloadKeys.FINISHED_AT to previous.finishedAt.toString(),
        )
      },
  )
