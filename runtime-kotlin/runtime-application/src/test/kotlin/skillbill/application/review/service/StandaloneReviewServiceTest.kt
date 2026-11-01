package skillbill.application.review.service

import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.repository.ReviewRepository
import skillbill.review.model.ImportedReview
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFindingStatus
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StandaloneReviewServiceTest {
  @Test
  fun `preparing an inline review registers its saved IDs once and never replaces feedback-bearing findings`() {
    val harness = PreparationHarness()
    val result = assertNotNull(harness.service.prepareFindings("invocation"))
    assertEquals("run-original", result.preview.reviewRunId)
    assertEquals(listOf("F-007", "F-012"), harness.imports.single().findings.map { it.findingId })
    assertEquals("src/A.kt:9", harness.imports.single().findings.first().location)
    assertNotNull(harness.service.prepareFindings("invocation"))
    assertEquals(1, harness.imports.size)
    assertNull(harness.service.prepareFindings("other-invocation"))
  }

  @Test
  fun `preparation refuses unfinished reviews partial imports and truncated legacy reports`() {
    val harness = PreparationHarness()
    val settled = harness.record
    harness.record = settled.copy(finishedAt = null)
    assertNull(harness.service.prepareFindings("invocation"))
    harness.record = settled.copy(phaseId = "implement")
    assertNull(harness.service.prepareFindings("invocation"))
    harness.record = settled.copy(reviewRunId = null)
    assertNull(harness.service.prepareFindings("invocation"))
    harness.record = settled.copy(acceptedResult = null, terminalResult = "[F-007] Major | src/A.kt:9 | Truncated...")
    assertNull(harness.service.prepareFindings("invocation"))
    harness.record = settled
    harness.existing = listOf(NumberedFinding(1, "F-007", "Major", "high", "src/A.kt:9", "Existing"))
    assertNull(harness.service.prepareFindings("invocation"))
    assertEquals(emptyList(), harness.imports)
  }
}

private class PreparationHarness {
  var record =
    StandalonePhaseStatusRecord(
      repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
      invocationId = "invocation", phaseId = "review", executionId = "execution", statusStoreId = "store",
      runSequence = "1", statusRevision = "1", lifecycleState = "terminal", currentStep = "present_findings",
      currentActivity = null, startedAt = Instant.EPOCH, updatedAt = Instant.EPOCH, finishedAt = Instant.EPOCH,
      activeDurationMs = null, activeDurationAsOf = null, leaseOwner = "owner", leaseGeneration = 1,
      leaseExpiresAt = Instant.EPOCH, terminalResult = null, reviewRunId = "run-original",
      acceptedResult =
        """
        [F-007] Major | high | src/A.kt:9 | Broken boundary
        [F-012] Minor | medium | src/B.kt:2 | Missing recovery
        verdict: changes_requested
        """.trimIndent(),
    )
  val imports = mutableListOf<ImportedReview>()
  var existing = emptyList<NumberedFinding>()
  private val reviews =
    proxy<ReviewRepository> { name, args ->
      when (name) {
        "fetchFindingStatuses" -> existing.associate { it.findingId to ReviewFindingStatus.OPEN }
        "saveImportedReview" -> {
          val review = args[0] as ImportedReview
          imports += review
          existing =
            review.findings.mapIndexed { index, finding ->
              NumberedFinding(
                index + 1,
                finding.findingId,
                finding.severity,
                finding.confidence,
                finding.location,
                finding.description,
              )
            }
          Unit
        }
        else -> error("Unexpected review call $name")
      }
    }
  private val statuses =
    proxy<StandalonePhaseStatusRepository> { name, args ->
      check(name == "readByInvocation")
      record.takeIf { it.invocationId == args[0] }
    }
  private val unit =
    proxy<UnitOfWork> { name, _ ->
      when (name) {
        "getDbPath" -> Path.of("selected.db")
        "getReviews" -> reviews
        "getStandalonePhaseStatuses" -> statuses
        else -> error("Unexpected unit call $name")
      }
    }
  private val database =
    object : DatabaseSessionFactory {
      override fun acquireDatabasePathProtection() = DatabasePathProtectionResult.Unavailable("Not used")

      override fun <T> owningTransaction(block: (UnitOfWork) -> T) =
        OwningTransactionResult.Refused(OwningTransactionResult.Refusal.ACTIVE_SESSION)

      override fun resolveDbPath(): Path = Path.of("selected.db")

      override fun databaseExists(): Boolean = true

      override fun <T> read(block: (UnitOfWork) -> T): T = error("Unexpected read")

      override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit)

      override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = error("Unexpected self-managed write")
    }
  val service = StandaloneReviewService(database)
}

private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
  Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
    invoke(method.name, args.orEmpty())
  } as T
