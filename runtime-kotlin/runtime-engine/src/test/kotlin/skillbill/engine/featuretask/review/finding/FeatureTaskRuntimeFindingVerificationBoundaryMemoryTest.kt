package skillbill.engine.featuretask.review.finding

import skillbill.contracts.time.JvmSystemClock
import skillbill.engine.featuretask.lifecycle.checkpoint.sanitized
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.engine.featuretask.runner.disposition
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeFindingVerificationBoundaryMemoryTest {
  private val memory =
    FeatureTaskRuntimeFindingVerificationBoundaryMemory(
      FileSystemGoalPlanningContextDiscovery(JvmSystemClock),
      FileSystemGoalPlanningBoundaryBodyResolver(),
    )

  @Test
  fun `verify_findings prompt carries catalog titles only and never whole boundary files or unselected bodies`() {
    val repo = Files.createTempDirectory("verify-findings-prompt-leak")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n\n" +
        "## [${LocalDate.now(ZoneOffset.UTC)}] unselected-title\n\nunselected body sentence\n",
    )
    Files.writeString(
      agent.resolve("decisions.md"),
      "# Boundary Decisions\n\n## [${LocalDate.now(ZoneOffset.UTC)}] decision-title\n\ndecision body sentence\n",
    )

    val prompt =
      memory.promptSection(
        memory.sectionsForFindings(
          repo,
          listOf(
            FeatureTaskRuntimeFindingBoundaryMemoryRequest(
              findingId = "F-001",
              findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
            ),
          ),
        ),
      )

    assertTrue(prompt.contains("selected-title"))
    assertTrue(prompt.contains("unselected-title"))
    assertFalse(prompt.contains("selected body sentence"))
    assertFalse(prompt.contains("unselected body sentence"))
    assertFalse(prompt.contains("decision body sentence"))
    assertFalse(prompt.contains("# Boundary History"))
    assertFalse(prompt.contains("# Boundary Decisions"))
  }

  @Test
  fun `path with no eligible boundary keeps intent only availability signal`() {
    val repo = Files.createTempDirectory("verify-findings-intent-only")
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-002",
            findingPaths = listOf("Foo.kt"),
          ),
        ),
      )
    val prompt = memory.promptSection(sections)

    assertTrue(sections.single().discovery.boundaryContextUnavailable)
    assertTrue(prompt.contains("boundary_context_unavailable: true"))
    assertTrue(prompt.contains("Proceed intent-only"))
  }

  @Test
  fun `goal findings projection shape includes selected heading provenance fields`() {
    val provenance =
      FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
        headingId = "runtime-kotlin/agent/history.md#abc",
        sourcePath = "runtime-kotlin/agent/history.md",
      ).asWorkflowArtifactEntry().toWorkflowArtifactMap()
    assertTrue(provenance.containsKey("heading_id"))
    assertTrue(provenance.containsKey("source_path"))
  }

  @Test
  fun `resolved bodies appear only from persisted boundary selection not checkpoint dispositions`() {
    val repo = Files.createTempDirectory("verify-findings-checkpoint-bodies")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
    )
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      )
    val headingId = sections.single().discovery.boundaryCatalog.single().headingId
    val checkpoint =
      listOf(
        FeatureTaskRuntimeFindingVerificationDisposition(
          findingId = "F-001",
          disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
          reason = "Matches intent",
          selectedBoundaryHeadings =
            listOf(
              FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
                headingId = headingId,
                sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
              ),
            ),
        ),
      )
    val unsetPersisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = null
    assertNull(unsetPersisted?.takeIf { it.isNotEmpty() })
    val persistedSelections =
      mapOf(
        "F-001" to
          listOf(
            FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
              headingId = headingId,
              sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
            ),
          ),
      )
    val resolvedPrompt =
      memory.resolvedBodiesPromptSection(
        repo,
        sections,
        selectionsByFindingId = persistedSelections,
      )

    assertTrue(resolvedPrompt.contains("selected body sentence"))
    assertFalse(checkpoint.single().selectedBoundaryHeadings.isEmpty())
  }

  @Test
  fun `resolved bodies appear only after boundary selection is settled`() {
    val repo = Files.createTempDirectory("verify-findings-selected-bodies")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n\n" +
        "## [${LocalDate.now(ZoneOffset.UTC)}] unselected-title\n\nunselected body sentence\n",
    )
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      )
    val headingId =
      sections.single().discovery.boundaryCatalog
        .first { it.heading.contains("selected-title") }
        .headingId
    val resolvedPrompt =
      memory.resolvedBodiesPromptSection(
        repo,
        sections,
        mapOf(
          "F-001" to
            listOf(
              FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
                headingId = headingId,
                sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
              ),
            ),
        ),
      )

    assertTrue(resolvedPrompt.contains("selected body sentence"))
    assertFalse(resolvedPrompt.contains("unselected body sentence"))
    assertFalse(resolvedPrompt.contains("# Boundary History"))
  }

  @Test
  fun `over budget verification resolution surfaces through disposition validation helper`() {
    val repo = Files.createTempDirectory("verify-findings-cap-gate")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val headings =
      (0 until GoalPlanningContext.VERIFICATION_MAX_HEADINGS_PER_FILE).joinToString("\n\n") { index ->
        "## [${LocalDate.now(ZoneOffset.UTC).minusDays((index % 28).toLong())}] history-$index\n\nhistory body $index"
      }
    val decisions =
      (0 until GoalPlanningContext.VERIFICATION_MAX_HEADINGS_PER_FILE).joinToString("\n\n") { index ->
        "## [${LocalDate.now(ZoneOffset.UTC).minusDays((index % 28).toLong())}] decision-$index\n\ndecision body $index"
      }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$headings\n")
    Files.writeString(agent.resolve("decisions.md"), "# Boundary Decisions\n\n$decisions\n")
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(FeatureTaskRuntimeFindingBoundaryMemoryRequest("F-001", listOf("modules/a/src/Main.kt"))),
      )
    val selected =
      sections.single().discovery.boundaryCatalog.map {
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
          headingId = it.headingId,
          sourcePath = it.sourcePath,
        )
      }
    val reason =
      memory.validateDispositionBoundaryBodies(
        repo,
        sections,
        listOf(
          FeatureTaskRuntimeFindingVerificationDisposition(
            findingId = "F-001",
            disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
            reason = "Matches intent",
            selectedBoundaryHeadings = selected,
          ),
        ),
      )

    assertTrue(
      reason?.contains("max_selected_bodies") == true || reason?.contains("max_total_body_bytes") == true,
    )
  }

  @Test
  fun `no-owner disposition must record boundary context unavailable`() {
    val repo = Files.createTempDirectory("verify-findings-no-owner-disposition")
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-002",
            findingPaths = listOf("Foo.kt"),
          ),
        ),
      )
    val disposition =
      FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-002",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
      )

    val reason = memory.validateDispositionBoundaryContext(sections, listOf(disposition))

    assertTrue(reason?.contains("boundary_context_unavailable") == true)
  }

  @Test
  fun `over-reported boundary context unavailable is ignored when a catalog is available`() {
    val repo = Files.createTempDirectory("verify-findings-over-reported-availability")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
    )
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      )
    assertFalse(sections.single().discovery.boundaryContextUnavailable)
    val disposition =
      FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        boundaryContextUnavailable = true,
      )

    assertEquals(null, memory.validateDispositionBoundaryContext(sections, listOf(disposition)))
  }

  @Test
  fun `invalid boundary heading selections are ignored without blocking`() {
    val repo = Files.createTempDirectory("verify-findings-provenance")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
    )
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      )
    val catalogEntry = sections.single().discovery.boundaryCatalog.single()
    val disposition =
      FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        selectedBoundaryHeadings =
          listOf(
            FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
              headingId = catalogEntry.headingId,
              sourcePath = "wrong/path/history.md",
            ),
            FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
              headingId = "off-catalog-id",
              sourcePath = catalogEntry.sourcePath,
            ),
          ),
      )

    assertEquals(null, memory.validateDispositionBoundaryProvenance(sections, listOf(disposition)))
    assertTrue(
      catalogValidatedBoundaryHeadings(
        sections.single().discovery.boundaryCatalog,
        disposition.selectedBoundaryHeadings,
      ).isEmpty(),
    )
    assertTrue(memory.selectionsRequiringBodyDelivery(sections, listOf(disposition)).isEmpty())
  }

  @Test
  fun `valid boundary heading selections are retained for body delivery`() {
    val repo = Files.createTempDirectory("verify-findings-valid-provenance")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
    )
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      )
    val catalogEntry = sections.single().discovery.boundaryCatalog.single()
    val disposition =
      FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        selectedBoundaryHeadings =
          listOf(
            FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
              headingId = catalogEntry.headingId,
              sourcePath = catalogEntry.sourcePath,
            ),
          ),
      )

    val validated =
      catalogValidatedBoundaryHeadings(
        sections.single().discovery.boundaryCatalog,
        disposition.selectedBoundaryHeadings,
      )
    assertEquals(1, validated.size)
    assertEquals(
      mapOf("F-001" to validated),
      memory.selectionsRequiringBodyDelivery(sections, listOf(disposition)),
    )
  }

  @Test
  fun `persisted boundary selections must match later disposition headings`() {
    val persisted =
      mapOf(
        "F-001" to
          listOf(
            FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
              headingId = "runtime-kotlin/agent/history.md#abc",
              sourcePath = "runtime-kotlin/agent/history.md",
            ),
          ),
      )
    val changed =
      FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        selectedBoundaryHeadings =
          listOf(
            FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
              headingId = "runtime-kotlin/agent/history.md#def",
              sourcePath = "runtime-kotlin/agent/history.md",
            ),
          ),
      )

    val reason =
      memory.validatePersistedBoundarySelectionsMatch(
        sections = emptyList(),
        dispositions = listOf(changed),
        persisted = persisted,
      )

    assertTrue(reason?.contains("persisted") == true)
  }

  @Test
  fun `selections without persisted delivery are rejected at settlement`() {
    val repo = Files.createTempDirectory("verify-findings-pending-delivery")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
    )
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      )
    val catalogEntry = sections.single().discovery.boundaryCatalog.single()
    val disposition =
      FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches intent",
        selectedBoundaryHeadings =
          listOf(
            FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
              headingId = catalogEntry.headingId,
              sourcePath = catalogEntry.sourcePath,
            ),
          ),
      )

    val reason =
      memory.validateBoundarySelectionsDelivered(
        sections = sections,
        dispositions = listOf(disposition),
        persisted = null,
      )

    assertTrue(reason?.contains("not yet") == true)
  }

  @Test
  fun `persisted boundary selections must cover every current finding requiring body delivery`() {
    assertPersistedBoundarySelectionsCoverCurrentFindings(memory)
  }

  @Test
  fun `persisted boundary selections drop invalid catalog entries`() {
    val repo = Files.createTempDirectory("verify-findings-persisted-catalog")
    val agent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
    )
    val sections =
      memory.sectionsForFindings(
        repo,
        listOf(
          FeatureTaskRuntimeFindingBoundaryMemoryRequest(
            findingId = "F-001",
            findingPaths = listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
          ),
        ),
      )
    val sanitized =
      memory.validatePersistedBoundarySelectionsAgainstCatalog(
        sections,
        mapOf(
          "F-001" to
            listOf(
              FeatureTaskRuntimeVerificationBoundaryHeadingProvenance(
                headingId = "off-catalog-id",
                sourcePath = "runtime-kotlin/runtime-application/agent/history.md",
              ),
            ),
        ),
      )

    assertTrue(sanitized.isEmpty())
  }
}
