package dev.skillbill.desktop.core.ui.status

import dev.skillbill.desktop.core.designsystem.component.SbBuild
import dev.skillbill.desktop.core.designsystem.component.SbBuildPrevious
import dev.skillbill.desktop.core.designsystem.component.SbBuildState
import dev.skillbill.desktop.core.domain.model.WorkBuild
import dev.skillbill.desktop.core.domain.model.WorkBuildState

fun WorkBuild.toSbBuild(): SbBuild = SbBuild(
  state = state.toSb(),
  command = command,
  shortCommand = shortCommand,
  tool = tool,
  projectKind = projectKind,
  pid = pid,
  startedAt = startedAt,
  elapsedMs = elapsedMs,
  elapsedAsOf = elapsedAsOf,
  usualMs = usualMs,
  sampleCount = sampleCount,
  exitCode = exitCode,
  finishedAt = finishedAt,
  previous = previous?.let { SbBuildPrevious(it.state.toSb(), it.durationMs, it.finishedAt) },
)

fun WorkBuild.showsChip(): Boolean = state == WorkBuildState.Running || state == WorkBuildState.Failed

private fun WorkBuildState.toSb(): SbBuildState = when (this) {
  WorkBuildState.Running -> SbBuildState.Running
  WorkBuildState.Passed -> SbBuildState.Passed
  WorkBuildState.Failed -> SbBuildState.Failed
  WorkBuildState.Finished -> SbBuildState.Finished
}
