package skillbill.engine.featuretask.prepare

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.runloop.planning.PlanBundleAuthorization
import skillbill.engine.goalrunner.manifest
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.decodeManifest
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence
import skillbill.workflow.decomposition.model.DecompositionManifestRepairOperation
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFormat
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.DecompositionManifestValidationSourceLocation
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureSpecPreparationWriterTest {
  private val writer =
    FeatureSpecPreparationWriter(
      decompositionManifestValidator = testDecompositionManifestValidator,
      fileStore = TestDecompositionManifestStore,
      decompositionManifestWriter = testDecompositionManifestWriter,
    )

  @Test
  fun `single_spec metadata writes parent distinct subtask and authoritative manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-single")
    val result =
      writer.write(
        repoRoot = repoRoot,
        request =
          FeatureSpecWriteRequest(
            decision = singleSpecDecision(),
            featureName = "Feature Spec Horizontal Skill",
            parentSpecOverview = "Prepare one executable implementation unit.",
            validationStrategy = "bill-code-check",
            subtasks = listOf(singleSubtask()),
          ),
      )

    val parentSpec = repoRoot.resolve(result.parentSpecPath)
    val manifest = parentSpec.parent.resolve("decomposition-manifest.yaml")
    assertEquals(FeatureSpecPreparationMode.SINGLE_SPEC, result.mode)
    assertEquals(result.parentSpecPath, result.featureImplementPath)
    assertTrue(Files.isRegularFile(repoRoot.resolve(result.decompositionManifestPath)))
    assertEquals(1, result.subtaskSpecPaths.size)
    assertTrue(Files.isRegularFile(repoRoot.resolve(result.subtaskSpecPaths.single())))
    assertTrue(Files.isRegularFile(parentSpec))
    assertTrue(Files.exists(manifest))
    assertContains(Files.readString(parentSpec), "## Acceptance Criteria")
    assertTrue("spec_source:" !in Files.readString(manifest))
  }

  @Test
  fun `preparation loud fails when no executable subtask is provided`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-single-subtasks")
    val error =
      assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
        writer.write(
          repoRoot = repoRoot,
          request =
            FeatureSpecWriteRequest(
              decision = singleSpecDecision(),
              featureName = "feature-spec-horizontal-skill",
              parentSpecOverview = "Every prepared feature needs an executable unit.",
              validationStrategy = "bill-code-check",
            ),
        )
      }

    assertEquals("subtasks", error.fieldPath)
  }

  @Test
  fun `decomposed writes parent and ordered subtask specs then writes manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-decomposed")
    val result =
      writer.write(
        repoRoot = repoRoot,
        request =
          FeatureSpecWriteRequest(
            decision = decomposedDecision(),
            featureName = "feature-spec-horizontal-skill",
            parentSpecOverview = "Prepare decomposition artifacts.",
            validationStrategy = "bill-code-check",
            subtasks =
              listOf(
                FeatureSpecSubtaskPreparation(
                  id = 1,
                  name = "foundation",
                  scope = "Build shared runtime write request/result contracts.",
                  acceptanceCriteria = listOf("Contracts are reusable by implement and goal."),
                  nonGoals = listOf("Do not wire skills yet."),
                  dependencyNotes = "Runs first and has no dependencies.",
                  validationStrategy = "bill-code-check",
                  nextPath = "Run bill-feature-task on spec_subtask_1_foundation.md.",
                  dependsOn = emptyList(),
                ),
                FeatureSpecSubtaskPreparation(
                  id = 2,
                  name = "runtime-writer",
                  scope = "Write parent/subtask specs and decomposition manifest.",
                  acceptanceCriteria = listOf("Manifest validates and can be consumed by goal."),
                  nonGoals = listOf("Do not add feature-spec skill wiring yet."),
                  dependencyNotes = "Depends on the shared preparation contracts from subtask 1.",
                  validationStrategy = "bill-code-check",
                  nextPath = "Run bill-feature-task on spec_subtask_2_runtime-writer.md.",
                  dependsOn = listOf(1),
                ),
              ),
          ),
      )

    val parentSpec = repoRoot.resolve(result.parentSpecPath)
    val manifest = repoRoot.resolve(result.decompositionManifestPath)
    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, result.mode)
    assertTrue(Files.isRegularFile(parentSpec))
    assertTrue(Files.isRegularFile(manifest))
    assertEquals(2, result.subtaskSpecPaths.size)
    result.subtaskSpecPaths.forEach { subtaskPath ->
      val subtaskSpec = repoRoot.resolve(subtaskPath)
      val text = Files.readString(subtaskSpec)
      assertContains(text, "## Scope")
      assertContains(text, "## Acceptance Criteria")
      assertContains(text, "## Non-Goals")
      assertContains(text, "## Dependency Notes")
      assertContains(text, "## Validation Strategy")
      assertContains(text, "## Next Path")
    }
    val loadedManifest = loadTestManifest(manifest)
    assertEquals("SKILL-59", loadedManifest.issueKey)
    assertEquals(2, loadedManifest.subtasks.size)
  }

  @Test
  fun `decomposed metadata accepts exactly one ordered subtask`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-decomposed-invalid")
    val result =
      writer.write(
        repoRoot = repoRoot,
        request =
          FeatureSpecWriteRequest(
            decision = decomposedDecision(),
            featureName = "feature-spec-horizontal-skill",
            parentSpecOverview = "Invalid decomposition request.",
            validationStrategy = "bill-code-check",
            subtasks = listOf(singleSubtask()),
          ),
      )
    assertEquals(1, result.subtaskSpecPaths.size)
    assertEquals(1, loadTestManifest(repoRoot.resolve(result.decompositionManifestPath)).subtasks.size)
  }

  @Test
  fun `linear preparation stamps source and requires every subtask identity`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-linear")
    val missingIdentity =
      assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
        writer.write(
          repoRoot,
          FeatureSpecWriteRequest(
            decision = singleSpecDecision(),
            featureName = "linear-feature",
            parentSpecOverview = "Linear-backed preparation.",
            validationStrategy = "bill-code-check",
            subtasks = listOf(singleSubtask()),
            specSource = SpecSource.LINEAR,
          ),
        )
      }
    assertEquals("subtasks[0].linear_issue_id", missingIdentity.fieldPath)

    val result =
      writer.write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = singleSpecDecision(),
          featureName = "linear-feature",
          parentSpecOverview = "Linear-backed preparation.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask().copy(linearIssueId = "linear-subtask-1")),
          specSource = SpecSource.LINEAR,
        ),
      )
    val manifest = loadTestManifest(repoRoot.resolve(result.decompositionManifestPath))
    assertEquals(SpecSource.LINEAR, manifest.specSource)
    assertEquals("linear-subtask-1", manifest.subtasks.single().linearIssueId)
    assertContains(Files.readString(repoRoot.resolve(result.decompositionManifestPath)), "spec_source: \"linear\"")
  }

  @Test
  fun `invalid dependency leaves no partial prepared feature files`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-prevalidate")
    assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      writer.write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = decomposedDecision(),
          featureName = "prevalidated-feature",
          parentSpecOverview = "No partial files.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask().copy(id = 2, dependsOn = listOf(1))),
        ),
      )
    }
    val directory = repoRoot.resolve(".feature-specs/SKILL-59-prevalidated-feature")
    assertTrue(!Files.exists(directory) || Files.list(directory).use { it.findAny().isEmpty })
  }

  @Test
  fun `manifest validation failure occurs before the first bundle write`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-manifest-prevalidate")
    val store = PreparationCountingManifestFileStore()
    val rejectingValidator =
      object : DecompositionManifestValidator {
        override fun validate(
          manifest: DecompositionManifestWireMap,
          sourceLabel: String,
        ): Unit =
          throw InvalidDecompositionManifestSchemaError(sourceLabel, "typed manifest rejection", "schema_invalid")

        override fun validateYamlText(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifest =
          throw InvalidDecompositionManifestSchemaError(sourceLabel, "typed YAML rejection", "schema_invalid")

        override fun validateYamlTextResult(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifestValidationResult =
          DecompositionManifestValidationResult.AcceptedUnchanged(validateYamlText(yamlText, sourceLabel), yamlText)
      }

    assertFailsWith<InvalidDecompositionManifestSchemaError> {
      FeatureSpecPreparationWriter(rejectingValidator, store, testDecompositionManifestWriter).write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = decomposedDecision(),
          featureName = "manifest-prevalidated",
          parentSpecOverview = "Manifest validation must precede artifact writes.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask()),
        ),
      )
    }

    assertEquals(0, store.writeCount)
  }

  @Test
  fun `read back validation restores the complete bundle after a manifest failure`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-readback-rollback")
    val readbackRejectingValidator =
      object : DecompositionManifestValidator {
        private var yamlValidationCount = 0

        override fun validate(
          manifest: DecompositionManifestWireMap,
          sourceLabel: String,
        ): Unit = Unit

        override fun validateYamlText(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifest {
          yamlValidationCount += 1
          if (yamlValidationCount == 2) {
            throw InvalidDecompositionManifestSchemaError(sourceLabel, "read-back rejection", "schema_invalid")
          }
          return decodeManifest(
            DecompositionManifestWireMap.from(
              requireNotNull(JsonCodec.anyToStringAnyMap(YAMLMapper().readValue(yamlText, Map::class.java))),
            ),
            sourceLabel,
          )
        }

        override fun validateYamlTextResult(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifestValidationResult =
          DecompositionManifestValidationResult.AcceptedUnchanged(validateYamlText(yamlText, sourceLabel), yamlText)
      }

    assertFailsWith<InvalidDecompositionManifestSchemaError> {
      FeatureSpecPreparationWriter(
        readbackRejectingValidator,
        TestDecompositionManifestStore,
        testDecompositionManifestWriter,
      ).write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = decomposedDecision(),
          featureName = "readback-rollback",
          parentSpecOverview = "Restore all artifacts when manifest read-back fails.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask()),
        ),
      )
    }

    val directory = repoRoot.resolve(".feature-specs/SKILL-59-readback-rollback")
    assertTrue(!Files.exists(directory) || Files.list(directory).use { it.findAny().isEmpty })
  }

  @Test
  fun `read back validation restores an overwritten bundle byte for byte`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-readback-overwrite")
    val request =
      FeatureSpecWriteRequest(
        decision = decomposedDecision(),
        featureName = "readback-overwrite",
        parentSpecOverview = "Restore the prior prepared bundle after read-back failure.",
        validationStrategy = "bill-code-check",
        subtasks = listOf(singleSubtask()),
      )
    val initial = writer.write(repoRoot, request)
    val originalFiles =
      (listOf(initial.parentSpecPath, initial.decompositionManifestPath) + initial.subtaskSpecPaths)
        .associateWith { relativePath -> Files.readString(repoRoot.resolve(relativePath)) }
    val readbackRejectingValidator =
      object : DecompositionManifestValidator {
        private var yamlValidationCount = 0

        override fun validate(
          manifest: DecompositionManifestWireMap,
          sourceLabel: String,
        ): Unit = Unit

        override fun validateYamlText(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifest {
          yamlValidationCount += 1
          if (yamlValidationCount == 4) {
            throw InvalidDecompositionManifestSchemaError(sourceLabel, "read-back rejection", "schema_invalid")
          }
          return decodeManifest(
            DecompositionManifestWireMap.from(
              requireNotNull(JsonCodec.anyToStringAnyMap(YAMLMapper().readValue(yamlText, Map::class.java))),
            ),
            sourceLabel,
          )
        }

        override fun validateYamlTextResult(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifestValidationResult =
          DecompositionManifestValidationResult.AcceptedUnchanged(validateYamlText(yamlText, sourceLabel), yamlText)
      }

    assertFailsWith<InvalidDecompositionManifestSchemaError> {
      FeatureSpecPreparationWriter(
        readbackRejectingValidator,
        TestDecompositionManifestStore,
        testDecompositionManifestWriter,
      ).write(
        repoRoot,
        request,
      )
    }

    originalFiles.forEach { (relativePath, content) ->
      assertEquals(content, Files.readString(repoRoot.resolve(relativePath)))
    }
  }

  @Test
  fun `preparation result retains manifest repair evidence`() {
    val evidence =
      DecompositionManifestRepairEvidence(
        format = DecompositionManifestValidationFormat.YAML,
        originalDigest = "a".repeat(64),
        repairedDigest = "b".repeat(64),
        operation = DecompositionManifestRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
        sourceLocation =
          DecompositionManifestValidationSourceLocation(
            sourceLabel = "manifest.yaml",
            offset = 10,
            line = 1,
            column = 11,
          ),
      )
    val repairingValidator =
      object : DecompositionManifestValidator {
        private var yamlValidationCount = 0

        override fun validate(
          manifest: DecompositionManifestWireMap,
          sourceLabel: String,
        ): Unit = Unit

        override fun validateYamlText(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifest =
          decodeManifest(
            DecompositionManifestWireMap.from(
              requireNotNull(JsonCodec.anyToStringAnyMap(YAMLMapper().readValue(yamlText, Map::class.java))),
            ),
            sourceLabel,
          )

        override fun validateYamlTextResult(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifestValidationResult {
          yamlValidationCount += 1
          val manifest = validateYamlText(yamlText, sourceLabel)
          return if (yamlValidationCount == 1) {
            DecompositionManifestValidationResult.AcceptedAfterRepair(manifest, yamlText, evidence)
          } else {
            DecompositionManifestValidationResult.AcceptedUnchanged(manifest, yamlText)
          }
        }
      }

    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-evidence")
    val result =
      FeatureSpecPreparationWriter(
        repairingValidator,
        TestDecompositionManifestStore,
        testDecompositionManifestWriter,
      ).write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = decomposedDecision(),
          featureName = "repair-evidence",
          parentSpecOverview = "Retain structured manifest repair evidence.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask()),
        ),
      )

    assertEquals(listOf(evidence), result.repairEvidence)
  }

  @Test
  fun `preparation result retains read back repair evidence`() {
    val evidence =
      DecompositionManifestRepairEvidence(
        format = DecompositionManifestValidationFormat.YAML,
        originalDigest = "c".repeat(64),
        repairedDigest = "d".repeat(64),
        operation = DecompositionManifestRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
        sourceLocation =
          DecompositionManifestValidationSourceLocation(
            sourceLabel = "manifest.yaml",
            offset = 12,
            line = 1,
            column = 13,
          ),
      )
    val repairingValidator =
      object : DecompositionManifestValidator {
        private var yamlValidationCount = 0
        var repairedYaml: String? = null

        override fun validate(
          manifest: DecompositionManifestWireMap,
          sourceLabel: String,
        ): Unit = Unit

        override fun validateYamlText(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifest =
          decodeManifest(
            DecompositionManifestWireMap.from(
              requireNotNull(JsonCodec.anyToStringAnyMap(YAMLMapper().readValue(yamlText, Map::class.java))),
            ),
            sourceLabel,
          )

        override fun validateYamlTextResult(
          yamlText: String,
          sourceLabel: String,
        ): DecompositionManifestValidationResult {
          yamlValidationCount += 1
          val manifest = validateYamlText(yamlText, sourceLabel)
          return if (yamlValidationCount == 2) {
            val repaired = "$yamlText# repaired read-back\n"
            repairedYaml = repaired
            DecompositionManifestValidationResult.AcceptedAfterRepair(manifest, repaired, evidence)
          } else {
            DecompositionManifestValidationResult.AcceptedUnchanged(manifest, yamlText)
          }
        }
      }

    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-readback-evidence")
    val result =
      FeatureSpecPreparationWriter(
        repairingValidator,
        TestDecompositionManifestStore,
        testDecompositionManifestWriter,
      ).write(
        repoRoot,
        FeatureSpecWriteRequest(
          decision = decomposedDecision(),
          featureName = "readback-evidence",
          parentSpecOverview = "Retain read-back repair evidence.",
          validationStrategy = "bill-code-check",
          subtasks = listOf(singleSubtask()),
        ),
      )

    assertEquals(listOf(evidence), result.repairEvidence)
    val manifestPath = repoRoot.resolve(result.decompositionManifestPath)
    assertEquals(repairingValidator.repairedYaml, Files.readString(manifestPath))
    assertEquals("SKILL-59", loadTestManifest(manifestPath).issueKey)
  }

  @Test
  fun `rewriting prepared artifacts keeps runtime status only in the manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-status")
    val request =
      FeatureSpecWriteRequest(
        decision = singleSpecDecision(),
        featureName = "preserved-status",
        parentSpecOverview = "Preserve runtime authority.",
        validationStrategy = "bill-code-check",
        subtasks = listOf(singleSubtask()),
      )
    val first = writer.write(repoRoot, request)
    val manifestPath = repoRoot.resolve(first.decompositionManifestPath)
    val blocked =
      loadTestManifest(manifestPath).copy(
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
        subtasks =
          loadTestManifest(manifestPath).subtasks.map { subtask ->
            subtask.copy(status = "blocked", blockedReason = "operator action required")
          },
      )
    TestDecompositionManifestStore.writeTextAtomically(
      manifestPath,
      encodeDecompositionManifestYaml(
        blocked,
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )

    val rewritten = writer.write(repoRoot, request)

    assertTrue("status:" !in Files.readString(repoRoot.resolve(rewritten.parentSpecPath)))
    assertTrue("status:" !in Files.readString(repoRoot.resolve(rewritten.subtaskSpecPaths.single())))
    assertEquals("blocked", loadTestManifest(manifestPath).status)
  }

  @Test
  fun `a complete authored bundle verifies and returns its parent path and reloadable manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-authored")
    val written = writer.write(repoRoot, authoredBundleRequest())

    val verified = writer.verifyAuthored(repoRoot, repoRoot.resolve(written.parentSpecPath))

    assertEquals(written.parentSpecPath, verified.parentSpecPath)
    assertEquals(written.subtaskSpecPaths, verified.subtaskSpecPaths)
    assertEquals(2, loadTestManifest(repoRoot.resolve(verified.decompositionManifestPath)).subtasks.size)
    assertEquals(
      null,
      PlanBundleAuthorization.violation(repoRoot, "SKILL-59", listOf(written.parentSpecPath), writer::listTree),
    )
  }

  @Test
  fun `an authored subtask without an acceptance list is refused with the typed readiness failure`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-authored-missing")
    val written = writer.write(repoRoot, authoredBundleRequest())
    val subtask = repoRoot.resolve(written.subtaskSpecPaths.first())
    Files.writeString(subtask, Files.readString(subtask).substringBefore("## Acceptance Criteria"))

    val error =
      assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
        writer.verifyAuthored(repoRoot, repoRoot.resolve(written.parentSpecPath))
      }

    assertEquals("subtasks[0].acceptance_criteria", error.fieldPath)
  }

  @Test
  fun `bundle authorization refuses outside edits and symlinks that escape the repository`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-authorization")
    val outside = Files.createTempDirectory("skillbill-feature-spec-outside")
    val written = writer.write(repoRoot, authoredBundleRequest())
    val bundleDirectory = repoRoot.resolve(written.parentSpecPath).parent

    assertTrue(PlanBundleAuthorization.violation(repoRoot, "SKILL-59", listOf("src/Main.kt"), writer::listTree) != null)
    Files.createSymbolicLink(bundleDirectory.resolve("escape.md"), outside.resolve("target.md"))
    assertTrue(
      PlanBundleAuthorization.violation(repoRoot, "SKILL-59", listOf(written.parentSpecPath), writer::listTree) != null,
    )
  }

  private fun authoredBundleRequest() =
    FeatureSpecWriteRequest(
      decision = decomposedDecision(),
      featureName = "authored-bundle",
      parentSpecOverview = "Verify an authored bundle.",
      validationStrategy = "bill-code-check",
      subtasks =
        listOf(
          singleSubtask(),
          singleSubtask().copy(id = 2, name = "follow-up", dependsOn = listOf(1)),
        ),
    )

  private fun loadTestManifest(path: Path) =
    loadDecompositionManifest(
      path,
      TestDecompositionManifestStore,
      testDecompositionManifestValidator,
    )

  private fun singleSubtask() =
    FeatureSpecSubtaskPreparation(
      id = 1,
      name = "implementation",
      scope = "Implement the complete prepared feature.",
      acceptanceCriteria = listOf("The prepared contract is satisfied."),
      nonGoals = emptyList(),
      dependencyNotes = "No dependencies.",
      validationStrategy = "bill-code-check",
      nextPath = "Run bill-feature-task on spec_subtask_1_implementation.md.",
    )

  private fun singleSpecDecision(): FeatureSpecPreparationDecision =
    FeatureSpecPreparationDecision(
      issueKey = "SKILL-59",
      intendedOutcome = "single_spec",
      acceptanceCriteria = listOf("Write parent spec."),
      constraints = listOf("Represent one implementation unit as exactly one manifest subtask."),
      nonGoals = listOf("Do not fabricate additional subtasks."),
      mode = FeatureSpecPreparationMode.SINGLE_SPEC,
    )

  private fun decomposedDecision(): FeatureSpecPreparationDecision =
    FeatureSpecPreparationDecision(
      issueKey = "SKILL-59",
      intendedOutcome = "decomposed",
      acceptanceCriteria = listOf("Write parent spec and decomposition artifacts."),
      constraints = listOf("Reuse manifest writer."),
      nonGoals = listOf("No skill wiring in this subtask."),
      mode = FeatureSpecPreparationMode.DECOMPOSED,
    )
}

private class PreparationCountingManifestFileStore :
  DecompositionManifestStore by TestDecompositionManifestStore {
  var writeCount: Int = 0

  override fun writeTextAtomically(
    target: Path,
    content: String,
  ) {
    writeCount += 1
    TestDecompositionManifestStore.writeTextAtomically(target, content)
  }
}

private fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
  sourceLabel: String = "<in-memory>",
): String =
  skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml(
    manifest,
    validator,
    fileStore,
    sourceLabel,
  ).yamlText
