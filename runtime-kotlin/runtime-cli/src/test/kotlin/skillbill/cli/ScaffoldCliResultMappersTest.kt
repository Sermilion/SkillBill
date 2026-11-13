package skillbill.cli

import skillbill.cli.scaffold.commands.toCliMap
import skillbill.contracts.JsonCodec
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainSkill
import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.catalog.model.ScaffoldShowResult
import skillbill.ports.scaffold.model.ScaffoldBaselineLayer
import skillbill.ports.scaffold.model.ScaffoldCompletionStatus
import skillbill.ports.scaffold.model.ScaffoldReviewComposition
import skillbill.ports.scaffold.model.ScaffoldSectionCompletionStatus
import skillbill.ports.scaffold.model.ScaffoldSectionStatus
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidationMode
import skillbill.ports.scaffold.repo.model.ScaffoldValidationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaffoldCliResultMappersTest {
  private fun sampleStatus(
    reviewComposition: ScaffoldReviewComposition? = null,
    contentPreview: String? = null,
    content: String? = null,
    issues: List<String>? = null,
  ) = ScaffoldSkillStatus(
    skillName = "bill-kotlin-code-review",
    packageName = "code-review",
    platform = "kotlin",
    family = "code-review",
    area = "architecture",
    contentFile = "skills/bill-kotlin-code-review/content.md",
    renderCommand = "sona render bill-kotlin-code-review",
    completionStatus = ScaffoldCompletionStatus.COMPLETE,
    sectionCount = 2,
    sections =
      listOf(
        ScaffoldSectionStatus(
          heading = "Overview",
          status = ScaffoldSectionCompletionStatus.FILLED,
          lineCount = 3,
          preview = "Intro text",
        ),
        ScaffoldSectionStatus(
          heading = "Guidance",
          status = ScaffoldSectionCompletionStatus.TODO,
          lineCount = 0,
          preview = "",
        ),
      ),
    recommendedCommands = listOf("sona fill bill-kotlin-code-review"),
    reviewComposition = reviewComposition,
    contentPreview = contentPreview,
    content = content,
    issues = issues,
  )

  private val sectionKeys = listOf("heading", "status", "line_count", "preview")

  private val statusBaseKeys =
    listOf(
      "skill_name",
      "package",
      "platform",
      "family",
      "area",
      "content_file",
      "render_command",
      "completion_status",
      "section_count",
      "sections",
      "recommended_commands",
    )

  @Test
  fun `show emits status keys in producer order without optional tail keys`() {
    val map = ScaffoldShowResult(status = sampleStatus()).toCliMap()

    assertEquals(statusBaseKeys, map.keys.toList())
    assertEquals("bill-kotlin-code-review", map["skill_name"])
    assertEquals("code-review", map["package"])
    assertEquals(2, map["section_count"])
    val sections = requireNotNull(JsonCodec.anyToStringAnyMapList(map["sections"]))
    assertEquals(sectionKeys, sections.first().keys.toList())
    assertEquals("Overview", sections.first()["heading"])
    assertEquals(3, sections.first()["line_count"])
  }

  @Test
  fun `show appends optional tail keys in producer order when present`() {
    val composition =
      ScaffoldReviewComposition(
        source = "platform.yaml",
        summary = "Run 1 baseline layer(s) before pack-local specialists.",
        baselineLayers =
          listOf(
            ScaffoldBaselineLayer(
              platform = "kotlin",
              skill = "bill-kotlin-code-review",
              scope = "same-review-scope",
              required = true,
              mode = "kmp-baseline",
            ),
          ),
      )
    val map =
      ScaffoldShowResult(
        status =
          sampleStatus(
            reviewComposition = composition,
            contentPreview = "preview body",
            content = "full body",
            issues = listOf("missing section"),
          ),
      ).toCliMap()

    assertEquals(
      statusBaseKeys + listOf("review_composition", "content_preview", "content", "issues"),
      map.keys.toList(),
    )
    val reviewComposition = requireNotNull(JsonCodec.anyToStringAnyMap(map["review_composition"]))
    assertEquals(listOf("source", "summary", "baseline_layers"), reviewComposition.keys.toList())
    val baselineLayers = requireNotNull(JsonCodec.anyToStringAnyMapList(reviewComposition["baseline_layers"]))
    assertEquals(
      listOf("platform", "skill", "scope", "required", "mode"),
      baselineLayers.single().keys.toList(),
    )
    assertEquals("preview body", map["content_preview"])
    assertEquals("full body", map["content"])
    assertEquals(listOf("missing section"), map["issues"])
  }

  @Test
  fun `list emits top-level keys and per-skill status keys in producer order`() {
    val map =
      ScaffoldListResult(
        repoRoot = "/repo",
        skillCount = 1,
        skills = listOf(sampleStatus()),
      ).toCliMap()

    assertEquals(listOf("repo_root", "skill_count", "skills"), map.keys.toList())
    assertEquals("/repo", map["repo_root"])
    assertEquals(1, map["skill_count"])
    val skills = requireNotNull(JsonCodec.anyToStringAnyMapList(map["skills"]))
    assertEquals(statusBaseKeys, skills.single().keys.toList())
  }

  @Test
  fun `validate repo mode emits only repo_root mode status issues`() {
    val map =
      ScaffoldValidateResult(
        repoRoot = "/repo",
        mode = ScaffoldValidationMode.REPOSITORY,
        status = ScaffoldValidationStatus.PASS,
        issues = emptyList(),
      ).toCliMap()

    assertEquals(listOf("repo_root", "mode", "status", "issues"), map.keys.toList())
    assertEquals("repo", map["mode"])
    assertEquals("pass", map["status"])
  }

  @Test
  fun `validate selected mode inserts skill_names and suggested_commands around repo keys`() {
    val map =
      ScaffoldValidateResult(
        repoRoot = "/repo",
        mode = ScaffoldValidationMode.SELECTED,
        status = ScaffoldValidationStatus.FAIL,
        issues = listOf("missing frontmatter"),
        skillNames = listOf("bill-kotlin-code-review"),
        suggestedCommands = listOf("sona fill bill-kotlin-code-review"),
      ).toCliMap()

    assertEquals(
      listOf("repo_root", "mode", "skill_names", "status", "issues", "suggested_commands"),
      map.keys.toList(),
    )
    assertEquals(listOf("bill-kotlin-code-review"), map["skill_names"])
    assertEquals(listOf("missing frontmatter"), map["issues"])
    assertEquals(listOf("sona fill bill-kotlin-code-review"), map["suggested_commands"])
  }

  @Test
  fun `explain emits base keys without the nested skill block when absent`() {
    val map = sampleExplain(skill = null).toCliMap()

    assertEquals(
      listOf(
        "explanation",
        "editable_surface",
        "generated_surface",
        "governed_sidecars",
        "normal_workflow",
        "notes",
      ),
      map.keys.toList(),
    )
  }

  @Test
  fun `explain appends nested skill block in producer order when present`() {
    val map =
      sampleExplain(
        skill =
          ScaffoldExplainSkill(
            skillName = "bill-kotlin-code-review",
            contentFile = "skills/bill-kotlin-code-review/content.md",
            renderCommand = "sona render bill-kotlin-code-review",
            recommendedCommands = listOf("sona fill bill-kotlin-code-review"),
          ),
      ).toCliMap()

    assertEquals(
      listOf(
        "explanation",
        "editable_surface",
        "generated_surface",
        "governed_sidecars",
        "normal_workflow",
        "notes",
        "skill",
      ),
      map.keys.toList(),
    )
    val skill = requireNotNull(JsonCodec.anyToStringAnyMap(map["skill"]))
    assertEquals(
      listOf("skill_name", "content_file", "render_command", "recommended_commands"),
      skill.keys.toList(),
    )
    assertEquals("bill-kotlin-code-review", skill["skill_name"])
  }

  private fun sampleExplain(skill: ScaffoldExplainSkill?) =
    ScaffoldExplainResult(
      explanation = "Authoring boundary explanation.",
      editableSurface = listOf("content.md"),
      generatedSurface = listOf("SKILL.md"),
      governedSidecars = listOf("agent/history.md"),
      normalWorkflow = listOf("fill"),
      notes = listOf("note"),
      skill = skill,
    )
}
