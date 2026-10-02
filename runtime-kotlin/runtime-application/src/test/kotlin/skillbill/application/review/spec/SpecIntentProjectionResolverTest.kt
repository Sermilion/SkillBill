package skillbill.application.review.spec

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.branchName
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.packet.toLaunchEnvelope
import skillbill.application.review.parallel.planning.ParallelReviewPreparationCompiler
import skillbill.application.review.parallel.planning.ParallelReviewPreparationInput
import skillbill.application.review.parallel.planning.PlannedReviewRubric
import skillbill.application.review.parallel.planning.criteriaReferences
import skillbill.application.reviewevidence.ResolvedCommitSequence
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.application.testDecompositionManifestValidator
import skillbill.error.shellcontent.UnreadableSpecIntentProjectionError
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.execution.SpecIntentAbsenceReason
import skillbill.review.context.model.execution.SpecIntentProjection
import skillbill.review.context.model.execution.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.execution.SpecIntentProvenance
import skillbill.review.context.model.execution.SpecIntentResolution
import skillbill.review.context.model.launch.GovernedReviewLaunch
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence
import skillbill.workflow.decomposition.model.DecompositionManifestRepairOperation
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFormat
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.DecompositionManifestValidationSourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpecIntentProjectionResolverTest {
  @Test
  fun `a spec the runtime invariants reader accepts still extracts without Intended Outcome`() {
    val repo = tempRepo()
    val spec =
      writeSpec(
        repo,
        "spec.md",
        """
        # SKILL-650 runtime spec

        Feature size: SMALL

        ## Acceptance Criteria

        1. The runtime drives every ordered phase to a validated output.
        2. The CLI delegates to the application runner without owning orchestration.

        ## Mandates and Overrides

        - Stay on the experimental path only when explicitly requested.
        """.trimIndent(),
      )
    val projection = extractProjection(repo, spec)
    assertEquals("SKILL-650 runtime spec", projection.intendedOutcome)
    assertEquals(
      listOf(
        "The runtime drives every ordered phase to a validated output.",
        "The CLI delegates to the application runner without owning orchestration.",
      ),
      projection.acceptanceCriteria,
    )
  }

  @Test
  fun `extraction captures five sections provenance digest and empty optional lists`() {
    val repo = tempRepo()
    val spec =
      writeSpec(
        repo,
        "spec.md",
        """
        # Feature

        ## Intended Outcome
        Ship the resolver.

        ## Acceptance Criteria
        1. First criterion.
        2. Second criterion.

        ## Constraints
        - Stay contract-first.

        ## Deferred items
        - Stage two.
        """.trimIndent(),
      )
    val projection = extractProjection(repo, spec)
    assertEquals("Ship the resolver.", projection.intendedOutcome)
    assertEquals(listOf("First criterion.", "Second criterion."), projection.acceptanceCriteria)
    assertEquals(listOf("Stay contract-first."), projection.constraints)
    assertEquals(emptyList(), projection.nonGoals)
    assertEquals(listOf("Stage two."), projection.deferredItems)
    assertEquals("spec.md", projection.provenance.specPath)
    assertEquals(sha256File(spec), projection.provenance.contentDigest)
  }

  @Test
  fun `a subtask spec using Scope instead of Intended Outcome still extracts`() {
    val repo = tempRepo()
    val spec =
      writeSpec(
        repo,
        "spec_subtask_9.md",
        """
        # SKILL-191 · Subtask 9 — Feature-task review phase delegation

        ## Scope
        Make the feature-task review phase delegate to the same driver.

        ## Acceptance Criteria
        1. The feature-task review phase executes through the same driver.
        2. The runtime records produced_outputs.findings from the driver.

        ## Non-Goals
        - Changing phase ordering.
        """.trimIndent(),
      )
    val projection = extractProjection(repo, spec)
    assertEquals(
      "Make the feature-task review phase delegate to the same driver.",
      projection.intendedOutcome,
    )
    assertEquals(
      listOf(
        "The feature-task review phase executes through the same driver.",
        "The runtime records produced_outputs.findings from the driver.",
      ),
      projection.acceptanceCriteria,
    )
    assertEquals(listOf("Changing phase ordering."), projection.nonGoals)
  }

  @Test
  fun `an explicit path wins over a manifest and a glob match`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    val explicit = repo.resolve(".feature-specs/SKILL-191-other/spec.md")
    Files.createDirectories(explicit.parent)
    Files.writeString(explicit, governedSpec("Explicit outcome", "Explicit criterion."))
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = repo.toFileLocation(),
          explicitSpecPath = explicit.toFileLocation(),
          branchName = "feat/SKILL-191-runtime",
        ),
      )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals("Explicit outcome", projection.intendedOutcome)
    assertEquals("Explicit criterion.", projection.acceptanceCriteria.single())
  }

  @Test
  fun `a readable manifest wins over a glob match`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = repo.toFileLocation(),
          branchName = "feat/SKILL-191-runtime",
        ),
      )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals("Subtask outcome", projection.intendedOutcome)
    assertEquals(
      ".feature-specs/SKILL-191-runtime/spec_subtask_2.md",
      projection.provenance.specPath,
    )
  }

  @Test
  fun `a subtask-owned delta uses the subtask spec as primary and the parent as surrounding context`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = repo.toFileLocation(),
          branchName = "feat/SKILL-191-runtime",
          changedPaths = listOf("src/Main.kt"),
        ),
      )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals(".feature-specs/SKILL-191-runtime/spec_subtask_2.md", projection.provenance.specPath)
    assertEquals(".feature-specs/SKILL-191-runtime/spec.md", projection.surroundingContext?.specPath)
    assertEquals(
      sha256File(repo.resolve(".feature-specs/SKILL-191-runtime/spec.md")),
      projection.surroundingContext?.contentDigest,
    )
  }

  @Test
  fun `two glob matches resolve to none with ambiguous_match`() {
    val repo = tempRepo()
    writeSpec(repo, ".feature-specs/SKILL-191-one/spec.md", governedSpec("One", "AC one."))
    writeSpec(repo, ".feature-specs/SKILL-191-two/spec.md", governedSpec("Two", "AC two."))
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "feat/SKILL-191-runtime"),
      )
    val none = assertIs<SpecIntentResolution.None>(resolved)
    assertEquals(SpecIntentAbsenceReason.AMBIGUOUS_MATCH, none.reason)
  }

  @Test
  fun `no glob match resolves to none with no_spec_found`() {
    val repo = tempRepo()
    Files.createDirectories(repo.resolve(".feature-specs"))
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "feat/SKILL-191-runtime"),
      )
    assertEquals(SpecIntentAbsenceReason.NO_SPEC_FOUND, assertIs<SpecIntentResolution.None>(resolved).reason)
  }

  @Test
  fun `a branch without an issue key resolves to none with not_applicable_scope`() {
    val repo = tempRepo()
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "main"),
      )
    assertEquals(SpecIntentAbsenceReason.NOT_APPLICABLE_SCOPE, assertIs<SpecIntentResolution.None>(resolved).reason)
  }

  @Test
  fun `an explicit missing spec path loud-fails rather than degrading`() {
    val repo = tempRepo()
    val missing = repo.resolve("missing-spec.md")
    val error =
      assertFailsWith<UnreadableSpecIntentProjectionError> {
        resolver().resolve(
          SpecIntentProjectionResolveRequest(
            repoRoot = repo.toFileLocation(),
            explicitSpecPath = missing.toFileLocation(),
            branchName = "feat/SKILL-191-runtime",
          ),
        )
      }
    assertTrue("spec_intent_projection" in error.message.orEmpty())
    assertTrue(missing.toString() in error.specPath)
  }

  @Test
  fun `an over-budget projection is delivered intact and is not truncated`() {
    val repo = tempRepo()
    val spec = writeSpec(repo, "spec.md", governedSpec("Outcome", "A criterion."))
    val projection =
      extractProjection(repo, spec, ReviewContextBudgetPolicy.DEFAULT.copy(maxSpecIntentProjectionBytes = 32))
    assertTrue(specIntentProjectionUtf8Bytes(projection) > 32)
    assertEquals(32, projection.declaredByteBudget)
  }

  @Test
  fun `each closed-vocabulary none reason emits an observability record`() {
    val repo = tempRepo()
    val notApplicable =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "main"),
      )
    assertTrue(
      assertIs<SpecIntentResolution.None>(notApplicable).degradations.any { "not_applicable_scope" == it.reason },
    )
    Files.createDirectories(repo.resolve(".feature-specs"))
    val missing =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "feat/SKILL-191-runtime"),
      )
    assertTrue(assertIs<SpecIntentResolution.None>(missing).degradations.any { "no_spec_found" == it.reason })
    writeSpec(repo, ".feature-specs/SKILL-191-one/spec.md", governedSpec("One", "AC one."))
    writeSpec(repo, ".feature-specs/SKILL-191-two/spec.md", governedSpec("Two", "AC two."))
    val ambiguous =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "feat/SKILL-191-runtime"),
      )
    assertTrue(assertIs<SpecIntentResolution.None>(ambiguous).degradations.any { "ambiguous_match" == it.reason })
  }

  @Test
  fun `a repaired manifest still wins over a glob match`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    val resolved =
      resolver(repairedManifestValidator()).resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = repo.toFileLocation(),
          branchName = "feat/SKILL-191-runtime",
        ),
      )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals("Subtask outcome", projection.intendedOutcome)
    assertEquals(
      ".feature-specs/SKILL-191-runtime/spec_subtask_2.md",
      projection.provenance.specPath,
    )
  }

  @Test
  fun `a missing parent spec degrades surrounding context and keeps the subtask primary`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    Files.delete(repo.resolve(".feature-specs/SKILL-191-runtime/spec.md"))
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = repo.toFileLocation(),
          branchName = "feat/SKILL-191-runtime",
        ),
      )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals(".feature-specs/SKILL-191-runtime/spec_subtask_2.md", projection.provenance.specPath)
    assertEquals(null, projection.surroundingContext)
    assertTrue(resolved.degradations.any { it.reason == "parent_spec_unavailable" && it.rung == "manifest" })
  }

  @Test
  fun `a missing owning sub-spec records no_spec_found at the manifest rung with its path`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    Files.delete(repo.resolve(".feature-specs/SKILL-191-runtime/spec_subtask_2.md"))
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "feat/SKILL-191-runtime"),
      )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals(".feature-specs/SKILL-191-runtime/spec.md", projection.provenance.specPath)
    val record = resolved.degradations.single { it.reason == "no_spec_found" && it.rung == "manifest" }
    assertTrue(record.resolvedPath.orEmpty().endsWith(".feature-specs/SKILL-191-runtime/spec_subtask_2.md"))
  }

  @Test
  fun `an unreadable manifest emits a record and falls through to glob search`() {
    val repo = featureRepo(includeGlob = true, includeManifest = true)
    Files.writeString(repo.resolve(".feature-specs/SKILL-191-runtime/decomposition-manifest.yaml"), "not: [valid")
    val resolved =
      resolver().resolve(
        SpecIntentProjectionResolveRequest(repoRoot = repo.toFileLocation(), branchName = "feat/SKILL-191-runtime"),
      )
    val projection = assertIs<SpecIntentResolution.Resolved>(resolved).projection
    assertEquals(".feature-specs/SKILL-191-runtime/spec.md", projection.provenance.specPath)
    assertTrue(resolved.degradations.any { it.reason == "manifest_unreadable" && it.rung == "glob" })
  }

  @Test
  fun `an interrupted manifest read propagates instead of falling through to glob search`() {
    val repo = tempRepo()
    val manifestPath = repo.resolve("decomposition-manifest.yaml")
    val store =
      object : DecompositionManifestStore by TestDecompositionManifestStore {
        override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = listOf(manifestPath)

        override fun isRegularFile(path: Path) = true

        override fun readText(path: Path): String = throw InterruptedException("interrupted")
      }
    val projectionResolver =
      SpecIntentProjectionResolver(
        store,
        testDecompositionManifestValidator,
        SpecIntentProjectionExtractor(
          object : ReviewContextEnvelopeValidator {
            override fun validate(
              envelope: ReviewContextWireMap,
              sourceLabel: String,
            ) = Unit

            override fun validateSpecIntentProjection(
              envelope: ReviewContextWireMap,
              sourceLabel: String,
            ) = Unit
          },
          store,
        ),
      )

    assertFailsWith<InterruptedException> {
      projectionResolver.resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = repo.toFileLocation(),
          branchName = "feat/SKILL-191-runtime",
        ),
      )
    }
  }

  @Test
  fun `resolved projection criteria populate assignment and launch envelopes`() {
    val criteria = listOf("First criterion.", "Second criterion.")
    val launch =
      compileCriteria(
        SpecIntentResolution.Resolved(
          SpecIntentProjection(
            intendedOutcome = "Outcome",
            acceptanceCriteria = criteria,
            constraints = emptyList(),
            nonGoals = emptyList(),
            deferredItems = emptyList(),
            provenance = SpecIntentProvenance("spec.md", "a".repeat(64)),
            declaredByteBudget = ReviewContextBudgetPolicy.DEFAULT.maxSpecIntentProjectionBytes.toInt(),
          ),
        ),
      ).single()
    assertEquals(criteria, launch.assignment.criteriaReferences)
    val launchCriteria =
      GovernedReviewLaunch(
        launch.assignment,
        launch.packet,
        launch.specialistContract,
        launch.rubrics.single().body,
        launch.brokerId,
        ReviewContextBudgetPolicy.DEFAULT,
      ).toLaunchEnvelope().asWireMap().let { wireMap ->
        (wireMap["criteria_references"] as? List<*>)?.filterIsInstance<String>().orEmpty()
      }
    assertEquals(criteria, launchCriteria)
    assertTrue(launchCriteria.none { it == "independent branch-diff specialist review" })
  }

  @Test
  fun `no resolved spec leaves criteria_references empty`() {
    val launch =
      compileCriteria(
        SpecIntentResolution.None(SpecIntentAbsenceReason.NOT_APPLICABLE_SCOPE),
      ).single()
    assertEquals(emptyList(), launch.assignment.criteriaReferences)
  }
}

private fun extractProjection(
  repo: Path,
  spec: Path,
  budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
): SpecIntentProjection =
  when (val read = extractor().extract(repo, spec, budget)) {
    is SpecIntentSourceRead.Read -> read.value
    is SpecIntentSourceRead.Unavailable -> error("Expected a readable spec but it was ${read.reason}: ${read.specPath}")
  }

private fun extractor() =
  SpecIntentProjectionExtractor(
    object : ReviewContextEnvelopeValidator {
      override fun validate(
        envelope: ReviewContextWireMap,
        sourceLabel: String,
      ) = Unit
    },
    TestDecompositionManifestStore,
  )

internal fun resolver(validator: DecompositionManifestValidator = testDecompositionManifestValidator) =
  SpecIntentProjectionResolver(
    TestDecompositionManifestStore,
    validator,
    extractor(),
  )

private fun repairedManifestValidator(): DecompositionManifestValidator =
  object : DecompositionManifestValidator by testDecompositionManifestValidator {
    override fun validateYamlTextResult(
      yamlText: String,
      sourceLabel: String,
    ): DecompositionManifestValidationResult {
      val manifest = validateYamlText(yamlText, sourceLabel)
      return DecompositionManifestValidationResult.AcceptedAfterRepair(
        manifest,
        yamlText,
        DecompositionManifestRepairEvidence(
          format = DecompositionManifestValidationFormat.YAML,
          originalDigest = "a".repeat(64),
          repairedDigest = "b".repeat(64),
          operation = DecompositionManifestRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
          sourceLocation = DecompositionManifestValidationSourceLocation(sourceLabel, 0, 1, 1),
        ),
      )
    }
  }

private fun compileCriteria(resolution: SpecIntentResolution): List<ReviewSpecialistLaunchRequest> {
  val repo = tempRepo()
  val diff =
    """
    diff --git a/src/A.kt b/src/A.kt
    --- a/src/A.kt
    +++ b/src/A.kt
    @@ -1,1 +1,2 @@
     line
    +alpha
    """.trimIndent()
  val evidence = ReviewDiffEvidence.parse(diff)
  val lane =
    ReviewLaunchLane(
      skillName = "bill-kotlin-code-review-security",
      packSlug = "kotlin",
      area = "security",
      depth = 0,
      originLayerChain = listOf("kotlin"),
      required = true,
      addOns = emptyList(),
      orderIndex = 0,
      inclusionReason = "security surface changed",
      ownedPaths = listOf("src/A.kt"),
    )
  return ParallelReviewPreparationCompiler.compile(
    ParallelReviewPreparationInput(
      diff = diff,
      evidence = evidence,
      commitSequence =
        ResolvedCommitSequence(
          listOf(
            ReviewCommitUnit("head", "base", "one commit", 0, evidence.hunks, ReviewCommitSource.COMMIT_RANGE),
          ),
          ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
        ),
      stack = "kotlin",
      agents = listOf("claude"),
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      repoRoot = repo,
      routedPacks = listOf("kotlin"),
      lanes = listOf(PlannedReviewRubric(lane, ReviewRubricProjection(lane.skillName, "rubric body", "security"))),
      baseRevision = "base",
      headRevision = "head",
      specIntentResolution = resolution,
    ),
    ReviewContextBudgetPolicy.DEFAULT,
    object : ReviewContextEnvelopeValidator {
      override fun validate(
        envelope: ReviewContextWireMap,
        sourceLabel: String,
      ) = Unit
    },
    "contract",
  )
}

private fun tempRepo(): Path = Files.createTempDirectory("spec-intent-repo")

private fun writeSpec(
  repo: Path,
  relative: String,
  text: String,
): Path {
  val path = repo.resolve(relative)
  Files.createDirectories(path.parent)
  Files.writeString(path, text)
  return path
}

private fun governedSpec(
  outcome: String,
  vararg criteria: String,
): String =
  buildString {
    appendLine("# Feature")
    appendLine()
    appendLine("## Intended Outcome")
    appendLine(outcome)
    appendLine()
    appendLine("## Acceptance Criteria")
    criteria.forEachIndexed { index, criterion -> appendLine("${index + 1}. $criterion") }
  }

private fun featureRepo(
  includeGlob: Boolean,
  includeManifest: Boolean,
): Path {
  val repo = tempRepo()
  val dir = repo.resolve(".feature-specs/SKILL-191-runtime")
  Files.createDirectories(dir)
  Files.writeString(dir.resolve("spec.md"), governedSpec("Parent outcome", "Parent criterion."))
  Files.writeString(dir.resolve("spec_subtask_2.md"), governedSpec("Subtask outcome", "Subtask criterion."))
  if (includeManifest) {
    Files.writeString(
      dir.resolve("decomposition-manifest.yaml"),
      """
      contract_version: "0.5"
      issue_key: "SKILL-191"
      feature_name: "runtime"
      parent_spec_path: ".feature-specs/SKILL-191-runtime/spec.md"
      spec_source: local
      status: in_progress
      execution_model: same_branch_commit_per_subtask
      base_branch: main
      feature_branch: feat/SKILL-191-runtime
      stack_branches: []
      current_subtask_intent:
        subtask_id: 2
        action: resume
      subtasks:
      - id: 1
        name: first
        spec_path: ".feature-specs/SKILL-191-runtime/spec_subtask_1.md"
        status: complete
        dependencies: []
      - id: 2
        name: second
        spec_path: ".feature-specs/SKILL-191-runtime/spec_subtask_2.md"
        status: pending
        dependencies: []
      """.trimIndent(),
    )
  }
  if (!includeGlob) {
    Files.deleteIfExists(dir.resolve("spec.md"))
  }
  return repo
}

private fun sha256File(path: Path): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
  return digest.joinToString("") { "%02x".format(it) }
}
