package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal fun verifyChatBrief(run: VerifyRunDetails, items: List<FixItem>): String = buildString {
  appendLine("Repository: ${run.repoRoot.orEmpty()}")
  appendLine("Branch: ${run.target.orEmpty()}")
  appendLine("Range: ${run.baseRevision.orEmpty()}..${run.headRevision.orEmpty()}")
  appendLine("Work on branch ${run.target.orEmpty()}. Do not resume, continue or modify Verify run ${run.workflowId}.")
  items.forEach { item ->
    appendLine()
    appendLine("${item.label} (${item.kindLabel()})")
    when (item) {
      is FixItem.Finding -> {
        appendLine("Severity: ${item.finding.severity}")
        item.finding.location.takeIf(String::isNotBlank)?.let { appendLine("Location: $it") }
        item.finding.description.takeIf(String::isNotBlank)?.let { appendLine(it) }
      }
      is FixItem.Criterion -> {
        appendLine(item.text)
        item.grade.missing?.takeIf(String::isNotBlank)?.let { appendLine("Missing: $it") }
        item.grade.notFound?.takeIf(String::isNotBlank)?.let { appendLine("Not found: $it") }
      }
      is FixItem.TestRow -> {
        appendLine("Area: ${item.row.area}")
        appendLine("Verdict: ${item.row.verdict}")
        appendLine("Why: ${item.row.why}")
        appendLine("Action: ${item.row.action}")
      }
      is FixItem.QaFinding -> {
        appendLine(item.finding.title)
        item.finding.observed?.takeIf(String::isNotBlank)?.let { appendLine("Observed: $it") }
        item.finding.expected?.takeIf(String::isNotBlank)?.let { appendLine("Expected: $it") }
      }
    }
  }
  val shared = items.flatMap { it.locations.distinct().map { location -> location to it.label } }
    .groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
  if (shared.isNotEmpty()) {
    appendLine()
    appendLine("Shared locations:")
    shared.forEach { (location, labels) -> appendLine("$location: ${labels.distinct().joinToString(", ")}") }
  }
}.trimEnd()

internal fun verifyGoalIntake(run: VerifyRunDetails, items: List<FixItem>): String = buildString {
  appendLine("Fix verified work in repository ${run.repoRoot.orEmpty()} on branch ${run.target.orEmpty()}.")
  items.forEach { item ->
    appendLine()
    appendLine("${item.label} (${item.kindLabel()})")
    when (item) {
      is FixItem.Finding -> {
        appendLine("Severity: ${item.finding.severity}")
        item.finding.location.takeIf(String::isNotBlank)?.let { appendLine("Location: $it") }
        appendLine(item.finding.description)
      }
      is FixItem.Criterion -> {
        appendLine("Criterion ${item.grade.number}: ${item.text}")
        item.grade.missing?.takeIf(String::isNotBlank)?.let { appendLine("Missing: $it") }
        item.grade.notFound?.takeIf(String::isNotBlank)?.let { appendLine("Not found: $it") }
        item.grade.evidence.takeIf { it.isNotEmpty() }?.let { appendLine("Evidence: ${it.joinToString(", ")}") }
      }
      is FixItem.TestRow -> {
        appendLine("Area: ${item.row.area}")
        appendLine("Verdict: ${item.row.verdict}")
        appendLine("Why: ${item.row.why}")
        appendLine("Action: ${item.row.action}")
      }
      is FixItem.QaFinding -> {
        appendLine(item.finding.title)
        item.finding.observed?.takeIf(String::isNotBlank)?.let { appendLine("Observed: $it") }
        item.finding.expected?.takeIf(String::isNotBlank)?.let { appendLine("Expected: $it") }
      }
    }
  }
  append("Build the fixes on branch ${run.target.orEmpty()}.")
}.trimEnd()
