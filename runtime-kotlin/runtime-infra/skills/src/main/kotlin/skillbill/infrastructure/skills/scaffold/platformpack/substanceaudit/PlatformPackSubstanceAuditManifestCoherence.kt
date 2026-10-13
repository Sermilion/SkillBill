package skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit

import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.relativeTo

internal fun compositionViolations(
  pack: PlatformManifest,
  packs: Map<String, PlatformManifest>,
): List<SubstanceViolation> =
  pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.mapNotNull { layer ->
    val target = packs[layer.platform]
    val measured =
      when {
        target == null -> "missing-pack:${layer.platform}"
        target.routedSkillName != layer.skill -> "mismatched-skill:${target.routedSkillName ?: "absent"}"
        hasRequiredCycle(layer.platform, packs, setOf(pack.slug)) -> "cyclic-required-composition"
        else -> return@mapNotNull null
      }
    packViolation(
      PackViolationArgs(
        pack = pack.slug,
        role = "composition:${layer.platform}",
        files = listOf("platform-packs/${pack.slug}/platform.yaml"),
        measured = measured,
        target = "valid-required-layer:${layer.skill}",
        rule = "required composition must resolve acyclically to the target pack's declared baseline",
      ),
    )
  }

internal fun hasRequiredCycle(
  slug: String,
  packs: Map<String, PlatformManifest>,
  visiting: Set<String> = emptySet(),
): Boolean {
  if (slug in visiting) return true
  val pack = packs[slug] ?: return false
  return pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.any { layer ->
    hasRequiredCycle(layer.platform, packs, visiting + slug)
  }
}

internal fun specialistMetrics(
  root: Path,
  pack: PlatformManifest,
  effective: Map<String, Set<String>>,
  packs: Map<String, PlatformManifest>,
): List<SpecialistMetric> {
  val physical =
    pack.declaredFiles.areas.map { (area, path) ->
      metric(
        root,
        pack.slug,
        area,
        path.toPath(),
        inherited = false,
      )
    }
  val inherited =
    (effective.getValue(pack.slug) - pack.declaredCodeReviewAreas.toSet()).map { area ->
      val source =
        findAreaSource(
          pack.slug,
          area,
          packs,
        ) ?: return@map SpecialistMetric(pack.slug, area, "absent", true, 0, 0, 0, emptyList())
      metric(root, pack.slug, area, source, inherited = true)
    }
  return (physical + inherited).sortedWith(compareBy({ it.area }, { it.inherited }))
}

internal fun findAreaSource(
  slug: String,
  area: String,
  packs: Map<String, PlatformManifest>,
  visiting: Set<String> = emptySet(),
): Path? {
  if (slug in visiting) return null
  val pack = packs[slug] ?: return null
  pack.declaredFiles.areas[area]?.let { return it.toPath() }
  return pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.firstNotNullOfOrNull { layer ->
    packs[layer.platform]?.takeIf {
      it.routedSkillName == layer.skill
    }?.let { findAreaSource(layer.platform, area, packs, visiting + slug) }
  }
}

internal fun metric(
  root: Path,
  pack: String,
  area: String,
  path: Path,
  inherited: Boolean,
): SpecialistMetric {
  val bullets = governedRuleBullets(Files.readString(path))
  val substantive = bullets.filter { isSubstantive(it, pack, area) }
  return SpecialistMetric(
    pack,
    area,
    path.relativeTo(root).toString(),
    inherited,
    substantive.size,
    CLUSTERS.count { cluster -> substantive.any(cluster::containsMatchIn) },
    substantive.count { hasEvidence(it, pack) },
    placeholders(Files.readString(path)),
  )
}

internal fun governedRuleBullets(text: String): List<String> {
  var governed = false
  return text.lineSequence().mapNotNull { line ->
    when {
      line.startsWith("### ") -> {
        governed = RULE_HEADING.containsMatchIn(line)
        null
      }
      line.startsWith("## ") -> {
        governed = false
        null
      }
      governed && line.matches(Regex("^\\s*[-*]\\s+.+")) -> line.replaceFirst(Regex("^\\s*[-*]\\s+"), "")
      else -> null
    }
  }.toList()
}

internal fun isSubstantive(
  rule: String,
  pack: String,
  area: String,
): Boolean {
  if (!OBLIGATION.containsMatchIn(rule) || !hasEvidence(rule, pack)) return false
  if (area != "code-quality") return FAILURE.containsMatchIn(rule)
  return QUALITY_MAINTENANCE_COST.containsMatchIn(rule)
}

internal fun hasEvidence(
  rule: String,
  pack: String,
): Boolean {
  val candidates =
    EVIDENCE.findAll(rule).map { match ->
      match.groups[1]?.value ?: match.value.trim()
    }
  return candidates.any { candidate ->
    val normalized = candidate.lowercase(Locale.ROOT).trim('`', ' ', '.', '/', ':', '-', '_')
    normalized.isNotBlank() &&
      normalized !in setOf(pack.lowercase(Locale.ROOT), "platform", "api", "command", "mechanism", "example") &&
      !GENERIC_EVIDENCE.containsMatchIn(normalized)
  }
}

internal fun placeholders(text: String): List<String> =
  PLACEHOLDERS.findAll(text).map { it.value.lowercase(Locale.ROOT) }.distinct().sorted().toList()
