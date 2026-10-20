package skillbill.cli.goal.status

import skillbill.contracts.SharedPayloadKeys
import skillbill.goalrunner.model.GoalRunnerStatusProjection

internal fun StringBuilder.appendOperatorSurfaceLines(projection: GoalRunnerStatusProjection) {
  if (projection.blockedAttemptCount > 0 || projection.supervisorKillCount > 0) {
    appendLine(
      "blocked_attempts: ${projection.blockedAttemptCount} supervisor_kills=${projection.supervisorKillCount}",
    )
  }
  projection.phaseAttemptCounts.takeIf { it.isNotEmpty() }?.let { counts ->
    appendLine("phase_attempts: ${counts.entries.joinToString(" ") { (key, value) -> "$key=$value" }}")
  }
  projection.cumulativeFixIterations.takeIf { it.isNotEmpty() }?.let { iterations ->
    appendLine("fix_iterations: ${iterations.entries.joinToString(" ") { (key, value) -> "$key=$value" }}")
  }
  projection.reAttemptCauseCounts.takeIf { it.isNotEmpty() }?.let { causes ->
    appendLine("re_attempt_causes: ${causes.entries.joinToString(" ") { (key, value) -> "$key=$value" }}")
  }
  projection.findingsInScope?.let { appendLine("findings_in_scope: $it") }
  projection.outOfBandAcceptances.takeIf(List<*>::isNotEmpty)?.forEach { acceptance ->
    appendLine(
      "accepted_out_of_band: subtask=${acceptance.subtaskId} commit=${acceptance.commitSha} " +
        "at=${acceptance.acceptedAt} reason=${acceptance.reason}",
    )
  }
}

internal fun StringBuilder.appendQaFindingsLines(projection: GoalRunnerStatusProjection) {
  val findings = projection.goalQaResult?.findings.orEmpty()
  if (findings.isNotEmpty()) {
    val (fixed, notFixed) = findings.partition { it.fixed }
    val notFixedIds = notFixed.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = " not_fixed_ids=") { it.id }
    appendLine("qa_findings: fixed=${fixed.size} not_fixed=${notFixed.size}${notFixedIds.orEmpty()}")
  }
  val criteria = projection.goalQaResult?.criteria.orEmpty()
  if (criteria.isEmpty()) return
  val failed = criteria.filterNot { it.passed }
  val failedIds = failed.mapNotNull { it.id }.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = " failed_ids=")
  appendLine("qa_criteria: passed=${criteria.size - failed.size} failed=${failed.size}${failedIds.orEmpty()}")
}

internal fun StringBuilder.appendDiffStatusLines(
  projection: GoalRunnerStatusProjection,
  watchIndex: String? = null,
) {
  val stat = projection.requestedDiffStat
  val indexPrefix = watchIndex?.let { " index=$it" }.orEmpty()
  stat?.let {
    appendLine(
      "${if (watchIndex == null) "diff_stat" else "watch_diff_stat"}:$indexPrefix " +
        "files_changed=${it.filesChanged} insertions=${it.insertions} deletions=${it.deletions}",
    )
  }
  val selected = projection.selectedDiffHunks ?: return
  appendLine(
    "${if (watchIndex == null) "selected_diff_hunks" else "watch_selected_diff_hunks"}:$indexPrefix " +
      "count=${selected.hunks.size} truncated=${selected.truncated}",
  )
  selected.hunks.forEachIndexed { hunkIndex, hunk ->
    val path = hunk.path.goalCliToken()
    appendLine(
      "${if (watchIndex == null) "selected_diff_hunk" else "watch_selected_diff_hunk"}:$indexPrefix " +
        "hunk_index=${hunkIndex + 1} path=$path staged=${hunk.staged} " +
        "header=${hunk.header.goalCliToken()} line_count=${hunk.lines.size} truncated=${hunk.truncated}",
    )
    hunk.lines.forEachIndexed { lineIndex, line ->
      appendLine(
        "${if (watchIndex == null) "selected_diff_line" else "watch_selected_diff_line"}:$indexPrefix " +
          "hunk_index=${hunkIndex + 1} line_index=${lineIndex + 1} path=$path staged=${hunk.staged} " +
          "text=${line.goalCliToken()}",
      )
    }
  }
}

internal fun StringBuilder.appendOperatorSurfaceLines(payload: Map<*, *>) {
  val blockedAttemptCount = (payload["blocked_attempt_count"] as? Number)?.toInt() ?: 0
  val supervisorKillCount = (payload["supervisor_kill_count"] as? Number)?.toInt() ?: 0
  if (blockedAttemptCount > 0 || supervisorKillCount > 0) {
    appendLine("blocked_attempts: $blockedAttemptCount supervisor_kills: $supervisorKillCount")
  }
  (payload["phase_attempt_counts"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { counts ->
    appendLine("phase_attempts: ${counts.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["cumulative_fix_iterations"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { iters ->
    appendLine("fix_iterations: ${iters.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["re_attempt_causes"] as? Map<*, *>)?.takeIf(Map<*, *>::isNotEmpty)?.let { causes ->
    appendLine("re_attempt_causes: ${causes.entries.joinToString(" ") { (k, v) -> "$k=$v" }}")
  }
  (payload["findings_in_scope"] as? Number)?.toInt()?.let { appendLine("findings_in_scope: $it") }
  (payload["out_of_band_acceptances"] as? List<*>)?.takeIf(List<*>::isNotEmpty)?.forEach { raw ->
    val acceptance = raw as? Map<*, *> ?: return@forEach
    appendLine(
      "accepted_out_of_band: subtask=${acceptance[SharedPayloadKeys.SUBTASK_ID]} commit=${acceptance["commit_sha"]} " +
        "at=${acceptance["accepted_at"]} reason=${acceptance["reason"]}",
    )
  }
}

internal fun StringBuilder.appendDiffStatusLines(
  payload: Map<*, *>,
  watchIndex: String? = null,
) {
  val indexPrefix = watchIndex?.let { " index=$it" }.orEmpty()
  (payload["diff_stat"] as? Map<*, *>)?.let { stat ->
    appendLine(
      "${if (watchIndex == null) "diff_stat" else "watch_diff_stat"}:$indexPrefix " +
        "files_changed=${stat["files_changed"]} insertions=${stat["insertions"]} deletions=${stat["deletions"]}",
    )
  }
  val selected = payload["selected_diff_hunks"] as? Map<*, *> ?: return
  val hunks = (selected["hunks"] as? List<*>).orEmpty()
  appendLine(
    "${if (watchIndex == null) "selected_diff_hunks" else "watch_selected_diff_hunks"}:$indexPrefix " +
      "count=${hunks.size} truncated=${selected["truncated"]}",
  )
  hunks.forEachIndexed { hunkIndex, rawHunk ->
    val hunk = rawHunk as? Map<*, *> ?: return@forEachIndexed
    val path = hunk["path"].toString().goalCliToken()
    val staged = hunk["staged"]
    val lines = (hunk["lines"] as? List<*>).orEmpty()
    appendLine(
      "${if (watchIndex == null) "selected_diff_hunk" else "watch_selected_diff_hunk"}:$indexPrefix " +
        "hunk_index=${hunkIndex + 1} path=$path staged=$staged " +
        "header=${hunk["header"].toString().goalCliToken()} line_count=${lines.size} truncated=${hunk["truncated"]}",
    )
    lines.forEachIndexed { lineIndex, rawLine ->
      appendLine(
        "${if (watchIndex == null) "selected_diff_line" else "watch_selected_diff_line"}:$indexPrefix " +
          "hunk_index=${hunkIndex + 1} line_index=${lineIndex + 1} path=$path staged=$staged " +
          "text=${rawLine.toString().goalCliToken()}",
      )
    }
  }
}

internal fun String.goalCliToken(): String =
  replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace(" ", "\\s")
