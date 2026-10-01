package skillbill.infrastructure.skills.scaffold.validation.review

import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentBundle
import skillbill.infrastructure.skills.scaffold.authoring.parentViolation
import skillbill.infrastructure.skills.scaffold.platformpack.packRootsBySlug
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal object ReviewSkillStructureValidatorContent {
  fun contentViolations(
    pack: Path,
    manifest: Map<*, *>,
    file: Path,
    packRootsBySlug: Map<String, Path> = emptyMap(),
  ): List<ReviewSkillStructureViolation> {
    val parentViolation =
      if (hasInternalParent(file, PACK_SIDECAR_PARENT_SKILL)) {
        emptyList()
      } else {
        listOf(violation(file, "code-review internal parent"))
      }
    val relativeFile = pack.relativize(file).let(::portablePath)
    return parentViolation +
      if (relativeFile == declaredBaseline(manifest)) {
        baselineViolations(file, packRootsBySlug)
      } else {
        specialistViolations(file, declaredAreaForFile(manifest, relativeFile))
      }
  }

  private fun specialistViolations(
    file: Path,
    area: String?,
  ): List<ReviewSkillStructureViolation> {
    val required = listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules")
    val content = Files.readString(file)
    val projectRules = h2Section(content, "Project-Specific Rules")
    val headings = headings(file)
    return buildList {
      if (headings != required && headings != required + "Repo-Local Knowledge") {
        add(violation(file, "specialist H2 sequence"))
      }
      if (!projectRules.contains("### ")) {
        add(violation(file, "specialist H3 grouping"))
      }
      if (definesOwnSeverityVocabulary(content)) {
        add(violation(file, "specialist defines own severity vocabulary"))
      }
      if (!hasCanonicalSeverityCloser(area, content)) {
        add(violation(file, "missing canonical severity closer"))
      }
      if (!projectRules.contains('`') ||
        !Regex("(?i)\\b(must|do not|never|require|reject|verify|preserve)\\b")
          .containsMatchIn(projectRules) ||
        !Regex("(?i)failure|error|invariant|boundary").containsMatchIn(projectRules)
      ) {
        add(violation(file, "enforceable stack failure modes"))
      }
      if (Regex("(?i)\\b(run|invoke|spawn)\\b[^\\n]*bill-[a-z0-9-]+-code-review-")
          .containsMatchIn(content)
      ) {
        add(violation(file, "sibling specialist invocation"))
      }
      if (area == "ui" &&
        !containsAll(ignoreSection(content), "ux-accessibility", "security")
      ) {
        add(violation(file, "UI lane deferrals"))
      }
      if (area == "ux-accessibility" &&
        !containsAll(ignoreSection(content), "ui", "security")
      ) {
        add(violation(file, "UX accessibility lane deferrals"))
      }
    }
  }

  fun nativeAgentViolations(
    pack: Path,
    manifest: Map<*, *>,
  ): List<ReviewSkillStructureViolation> {
    val baseline = declaredBaseline(manifest) ?: return emptyList()
    val agentsFile = pack.resolve(baseline).parent.resolve("native-agents/agents.yaml")
    if (!Files.isRegularFile(agentsFile)) {
      return listOf(violation(agentsFile, "native-agent source bundle"))
    }
    val agents =
      try {
        parseNativeAgentBundle(agentsFile)
      } catch (error: IllegalArgumentException) {
        invalidNativeAgentBundle(agentsFile, error)
      } catch (error: IOException) {
        invalidNativeAgentBundle(agentsFile, error)
      }
    val displayName = (manifest["display_name"] ?: manifest["platform"]).toString()
    val areaMetadata = manifest["area_metadata"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val expectedDescriptions =
      areaMetadata.mapNotNull { (rawArea, rawMetadata) ->
        val area = rawArea as? String ?: return@mapNotNull null
        val focus =
          (rawMetadata as? Map<*, *>)?.get("focus") as? String
            ?: return@mapNotNull null
        "bill-${pack.name}-code-review-$area" to
          "$displayName ${area.replace('-', ' ')} specialist — $focus."
      }.toMap()
    return buildList {
      if (agents.any { agent -> expectedDescriptions[agent.name] != agent.description }) {
        add(violation(agentsFile, "native-agent description pattern"))
      }
      val expectedNames =
        declaredAreas(manifest)
          .map { "bill-${pack.name}-code-review-$it" }
          .toSet()
      if (agents.map { it.name }.toSet() != expectedNames) {
        add(violation(agentsFile, "native-agent specialist coverage"))
      }
    }
  }

  fun authoredSidecarViolations(
    reviewFiles: List<Path>,
    manifest: Map<*, *>,
  ): List<ReviewSkillStructureViolation> =
    reviewFiles
      .filter { !it.parent.name.endsWith("code-review") }
      .flatMap { contentFile ->
        Files.list(contentFile.parent).use { siblings ->
          val sidecars =
            siblings
              .filter { it.fileName.toString().endsWith(".md") && it != contentFile }
              .toList()
          if (sidecars.size > 1) {
            return@use listOf(violation(contentFile, "one authored rubric sidecar"))
          }
          val sidecar = sidecars.singleOrNull() ?: return@use emptyList()
          val sourceContent = Files.readString(contentFile)
          val sidecarContent = Files.readString(sidecar)
          buildList {
            if (sidecar.fileName.toString().lowercase() in
              reservedGeneratedSidecarNames(manifest)
            ) {
              add(violation(sidecar, "reserved generated sidecar name"))
            }
            if (containsWrapperOrProviderOutput(sidecarContent)) {
              add(violation(sidecar, "wrapper or provider sidecar content"))
            }
            if (!isSpecialistRubric(sidecarContent)) {
              add(violation(sidecar, "specialist rubric sidecar content"))
            }
            if (!sourceContent.contains(sidecar.fileName.toString()) ||
              !Regex("(?i)insufficient").containsMatchIn(sourceContent)
            ) {
              add(violation(contentFile, "authored rubric sidecar rationale"))
            }
          }
        }
      }
}
