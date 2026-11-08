package skillbill.infrastructure.sqlite.operation

import skillbill.contracts.operationcard.OperationCardDetails
import skillbill.contracts.operationcard.OperationCardOption
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationCardThread
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.infrastructure.sqlite.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.operation.model.OperationAnchors
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqliteOperationCardRepositoryTest {
  @Test
  fun `a published card round trips whole and starts awaiting confirmation`() {
    val repo = repository()
    repo.saveLaunchContext(launch("opl-1"))
    repo.publish(card("opl-1", "opt-1"), null)

    val stored = assertNotNull(repo.find("opt-1"))
    assertEquals(OperationCardStatus.AWAITING_CONFIRMATION, stored.status)
    assertEquals(card("opl-1", "opt-1"), stored)
    assertEquals(stored, repo.findByLaunch("opl-1"))
    assertNull(repo.find("opt-missing"))
  }

  @Test
  fun `a card is admitted for confirmation only once`() {
    val repo = repository()
    repo.saveLaunchContext(launch("opl-1"))
    repo.publish(card("opl-1", "opt-1"), null)
    val owner = FeatureTaskRuntimeProcessIdentity("host", "boot", 42, "birth")

    val first = repo.admitConfirmation("opt-1", "T1=1", owner)
    val second = repo.admitConfirmation("opt-1", "T1=2", owner)

    assertNotNull(first)
    assertNull(second)
    assertEquals(first, repo.activeExecution("opt-1"))
    val admitted = assertNotNull(repo.find("opt-1"))
    assertEquals(OperationCardStatus.CONFIRMING, admitted.status)
    assertEquals("T1=1", admitted.selection)
    assertFalse(repo.dismiss("opt-1", "2026-11-05T10:00:00Z"))
  }

  @Test
  fun `a replacement card supersedes the open card of the same operation and returns its launch to release`() {
    val repo = repository()
    repo.saveLaunchContext(launch("opl-1"))
    repo.saveLaunchContext(launch("opl-2"))
    repo.publish(card("opl-1", "opt-1"), null)

    val superseded = repo.publish(card("opl-2", "opt-2"), null)

    assertEquals(listOf("opl-1"), superseded.map { it.launchId })
    assertEquals(OperationCardStatus.SUPERSEDED, repo.find("opt-1")?.status)
    assertEquals(OperationCardStatus.AWAITING_CONFIRMATION, repo.find("opt-2")?.status)
  }

  @Test
  fun `a bound authority proposal is stored with the card in one step`() {
    val repo = repository()
    repo.saveLaunchContext(launch("opl-1"))
    val authority =
      OperationProposal("opt-1", "verify", "/repo", OperationAnchors("abc", "main"), "opt-1", "2026-11-05T09:00:00Z")

    repo.publish(card("opl-1", "opt-1", operationId = "verify"), authority)

    assertEquals(authority, SqliteOperationProposalRepository(factory).find("opt-1"))
  }

  private lateinit var factory: DatabaseSessionFactory

  private fun repository(): SqliteOperationCardRepository {
    val tempDir = Files.createTempDirectory("operation-cards")
    val dbPath = tempDir.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    factory =
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )
    return SqliteOperationCardRepository(factory).also { storeIdentity = it.databaseIdentity() }
  }

  private var storeIdentity = ""

  private fun launch(launchId: String) =
    OperationLaunchContext(
      launchId = launchId,
      operationId = "pr-review-fix",
      repositoryIdentity = "repo-id",
      databaseIdentity = storeIdentity,
      checkoutRoot = "/repo",
      branch = "main",
      originalArguments = listOf("mode:local"),
      selectedAgent = "claude",
      holderId = "pr-review-fix:$launchId",
    )

  private fun card(
    launchId: String,
    token: String,
    operationId: String = "pr-review-fix",
  ) = OperationCardProjection(
    launchId = launchId,
    operationId = operationId,
    invocationId = "inv-$token",
    repositoryIdentity = "repo-id",
    databaseIdentity = storeIdentity,
    checkoutRoot = "/repo",
    branch = "main",
    sourceAnchors = mapOf("HEAD" to "abc", "branch" to "main"),
    originalArguments = listOf("mode:local"),
    selectedAgent = "claude",
    token = token,
    fullSummary = "matrix for $token",
    proposalDetails =
      OperationCardDetails.PrReviewFix(
        mode = "local",
        pullRequest = null,
        push = null,
        replies = null,
        threads = listOf(OperationCardThread("T1", "node-1", listOf(OperationCardOption("1", "Fix", true)))),
      ),
  )
}
