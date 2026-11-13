package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.StandalonePlanStage

internal data class PlanBriefing(
  val setup: PlanRunSetup?,
  val digest: List<PlanDigestSection>?,
  val stages: List<PlanBriefingStage>,
)

internal data class PlanRunSetup(
  val specPath: String?,
  val featureSize: String,
  val ceremony: List<String>,
  val mandates: List<String>,
  val criteria: List<PlanCriterion>,
)

internal data class PlanCriterion(val id: String, val text: String)
internal data class PlanDigestSection(val title: String, val items: List<String>)
internal data class PlanBriefingStage(
  val phaseId: String,
  val text: String?,
  val bytes: Int,
  val setupBytes: Int,
  val digestBytes: Int,
  val sizeLabel: String?,
  val layers: String,
)

private data class PreplanBlock(val lines: List<String>, val value: String?)

internal fun planBriefing(stages: List<StandalonePlanStage>, reachedPhaseIds: Set<String>): PlanBriefing {
  val saved = stages.associateBy { it.phaseId }
  val parsedStages = PLAN_STAGES.map { (phaseId, _) ->
    val text = saved[phaseId]?.briefingText?.takeIf(String::isNotBlank)
    val lines = text?.splitLinesWithEnds().orEmpty()
    val bytes = text?.encodeToByteArray()?.size ?: 0
    val setupBytes = lines.sectionBytes("## Run invariants")
    val pastedDigestBytes = lines.preplanBlock()?.lines?.sumOf { it.encodeToByteArray().size } ?: 0
    val evidenceBytes = lines.sectionBytes(PREPLAN_EVIDENCE_HEADING)
    val digestBytes = pastedDigestBytes + evidenceBytes
    PlanBriefingStage(
      phaseId, text, bytes, setupBytes, digestBytes,
      text?.let { byteSizeLabel(bytes) },
      when {
        text == null -> if (phaseId in reachedPhaseIds) "the runtime did not save one" else "not started"
        evidenceBytes > 0 -> "run setup + preplan evidence file"
        pastedDigestBytes > 0 -> "run setup + preplan digest"
        else -> "run setup only"
      },
    )
  }
  val setup = parsedStages.firstNotNullOfOrNull { stage ->
    stage.text?.splitLinesWithEnds()?.let(::parseSetup)
  }
  val planLines = saved["plan"]?.briefingText?.takeIf(String::isNotBlank)?.splitLinesWithEnds()
  val digest = planLines?.preplanBlock()?.value?.let(::digestSections) ?: planLines?.preplanEvidenceSections()
  return PlanBriefing(setup, digest, parsedStages)
}

private fun List<String>.preplanEvidenceSections(): List<PlanDigestSection>? {
  val start = indexOfFirst { it.trimEnd('\r', '\n').startsWith(PREPLAN_EVIDENCE_HEADING) }
  if (start < 0) return null
  val section = (start + 1 until size).takeWhile { !get(it).startsWith("## ") }.map { get(it).trim() }
  val path = section.firstOrNull { it.startsWith("path:") }?.substringAfter("path:")?.trim() ?: return null
  val excerpts = section.dropWhile { it != "excerpts:" }.drop(1)
    .takeWhile { it.startsWith("- ") || it == "(none)" }
    .map { it.removePrefix("- ") }
  return listOf(PlanDigestSection("Preplan evidence file", listOf(path) + excerpts))
}

private const val PREPLAN_EVIDENCE_HEADING = "## Preplan evidence"

private fun byteSizeLabel(bytes: Int): String = if (bytes < 1024) "$bytes B" else {
  val tenths = (bytes * 10 + 512) / 1024
  "${tenths / 10}.${tenths % 10} KB"
}

private fun String.splitLinesWithEnds(): List<String> {
  val result = mutableListOf<String>()
  var start = 0
  indices.forEach { index ->
    if (this[index] == '\n') {
      result += substring(start, index + 1)
      start = index + 1
    }
  }
  if (start < length) result += substring(start)
  return result
}

private fun List<String>.sectionBytes(heading: String): Int {
  val start = indexOfFirst { it.removeSuffix("\n").removeSuffix("\r").startsWith(heading) }
  if (start < 0) return 0
  val end = (start + 1 until size).firstOrNull { get(it).startsWith("## ") } ?: size
  return subList(start, end).sumOf { it.encodeToByteArray().size }
}

private fun List<String>.preplanBlock(): PreplanBlock? {
  val start = indexOfFirst { it.trimEnd('\r', '\n') == "### from: preplan" }
  if (start < 0) return null
  val result = mutableListOf(get(start))
  var index = start + 1
  while (index < size && !get(index).trimStart().startsWith("value:")) {
    if (get(index).startsWith("## ") || get(index).startsWith("### ")) return null
    result += get(index++)
  }
  val valueLine = getOrNull(index) ?: return null
  result += valueLine
  val value = valueLine.substringAfter("value:").trim()
  val nextLine = getOrNull(index + 1)?.trim()
  val fence = fenceMarker(value) ?: if (value.isEmpty()) nextLine?.let(::fenceMarker) else null
  if (fence == null) return PreplanBlock(result, value.takeIf(String::isNotEmpty))
  if (value.isEmpty()) result += getOrNull(index + 1) ?: return PreplanBlock(result, null)
  index += if (value.isEmpty()) 2 else 1
  val bodyStart = result.size
  var headingCutoff: Int? = null
  var closed = false
  while (index < size) {
    val line = get(index)
    if (closesFence(line, fence)) {
      result += line
      closed = true
      break
    }
    if (headingCutoff == null && (line.startsWith("## ") || line.startsWith("### "))) headingCutoff = result.size
    result += line
    index++
  }
  val block = if (!closed && headingCutoff != null) result.take(headingCutoff) else result
  val bodyEnd = block.size - if (closed) 1 else 0
  val body = block.subList(bodyStart, bodyEnd).joinToString("").trimEnd('\r', '\n')
  return PreplanBlock(block, body)
}

private fun fenceMarker(value: String): String? {
  val marker = value.takeWhile { it == '`' }
  return marker.takeIf { it.length >= 3 }
}

private fun closesFence(line: String, marker: String): Boolean {
  val trimmed = line.trimEnd('\r', '\n').trim()
  return trimmed.length >= marker.length && trimmed.all { it == '`' }
}

private fun parseSetup(lines: List<String>): PlanRunSetup? {
  val start = lines.indexOfFirst { it.trimEnd('\r', '\n').startsWith("## Run invariants") }
  if (start < 0) return null
  val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
  val section = lines.subList(start + 1, end)
  fun values(key: String): List<String> {
    val keyIndex = section.indexOfFirst { it.trim() == "$key:" }
    if (keyIndex < 0) return emptyList()
    return section.drop(keyIndex + 1).takeWhile { it.startsWith(' ') || it.startsWith('\t') }
      .map { it.trim().removePrefix("- ") }.filter(String::isNotBlank)
  }
  val featureSize = section.firstOrNull { it.trimStart().startsWith("feature_size:") }
    ?.substringAfter("feature_size:")?.trim()?.takeIf(String::isNotBlank) ?: return null
  val criteria = values("acceptance_criteria").mapNotNull { line ->
    val match = Regex("^(AC-\\d{3})\\. (.*)$").matchEntire(line)
    match?.let { PlanCriterion(it.groupValues[1], it.groupValues[2]) }
  }
  if (criteria.isEmpty()) return null
  val ceremony = values("ceremony_scaling").mapNotNull { line ->
    val key = line.substringBefore(":", "")
    val value = line.substringAfter(":", "").substringBefore(" (").trim()
    if (key.isEmpty() || value.isEmpty()) null else {
      val words = value.replace('_', ' ').removePrefix("full ")
      "${key.substringBefore('_')} $words"
    }
  }
  val mandates = values("mandates_and_overrides").filterNot { it == "(none)" }
  val specPath = section.firstOrNull { it.trimStart().startsWith("spec_reference:") }
    ?.substringAfter("spec_reference:")?.trim()?.takeIf(String::isNotBlank)
  return PlanRunSetup(specPath, featureSize, ceremony, mandates, criteria)
}

internal fun digestSections(value: String): List<PlanDigestSection> {
  val lines = value.lines().filter(String::isNotBlank)
  val sections = mutableListOf<PlanDigestSection>()
  var title: String? = null
  var items = mutableListOf<String>()
  fun save() { if (title != null && items.isNotEmpty()) sections += PlanDigestSection(title!!, items) }
  lines.forEach { line ->
    val trimmed = line.trim()
    val heading = when {
      trimmed.startsWith('#') -> trimmed.trimStart('#').trim().takeIf(String::isNotBlank)
      trimmed.count(Char::isLetter) >= 3 && trimmed.none(Char::isLowerCase) -> trimmed
      else -> null
    }
    if (heading != null) {
      save()
      title = heading
      items = mutableListOf()
    } else {
      if (title == null) title = "Preplan digest"
      items += trimmed.removePrefix("- ").removePrefix("* ")
    }
  }
  save()
  return if (sections.isEmpty()) listOf(PlanDigestSection("Preplan digest", lines.map { it.trim().removePrefix("- ").removePrefix("* ") })) else sections
}
