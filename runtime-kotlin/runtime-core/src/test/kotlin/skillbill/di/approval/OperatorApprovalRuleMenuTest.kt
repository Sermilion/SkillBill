package skillbill.di.approval

import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.DENY_OPTION_KIND
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.contracts.approval.ApprovalAuthorityScope
import skillbill.contracts.approval.ApprovalDecisionSource
import skillbill.contracts.approval.ApprovalOption
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.error.core.SkillBillRuntimeException
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import skillbill.ports.approval.model.ApprovalRequestIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorApprovalRuleMenuTest {
  private val home: Path = Files.createTempDirectory("skillbill-approval-rule-menu")
  private val checkout: Path =
    Files.createDirectories(home.resolve("repo")).also { Files.createDirectories(it.resolve(".git")) }
  private val database: Path = home.resolve("approvals.db")
  private val runtime =
    RuntimeComponent::class.create(
      RuntimeContext(
        environment =
          EnvironmentContext(
            environment = mapOf(RuntimeDatabaseEnvironmentKeys.DATABASE_PATH to database.toString()),
            userHome = home,
          ),
        transport = TransportContext(),
        workflowOps = WorkflowOpsContext(),
        callbacks = OptionalCallbacks(executableLookup = ExecutableLookup { false }),
      ),
    )
  private val menu = runtime.approvalOperations
  private val repository = menu.repositoryIdentity(checkout)
  private val delivered = mutableListOf<ApprovalRequestRecord>()
  private var sequence = 0

  @Test
  fun `revoked and nonmatching rules do not authorize and a corrupt request is surfaced instead of allowed`() {
    val consumer = menu.registerConsumer(repository, "desktop").lease.nonce
    val owner = openOwner("profile-a")

    val first = assertIs<ApprovalRegistrationResult.Registered>(owner.register(command(owner, "ls -la")))
    val remembered = menu.decide(first.request.identity(), consumer, ALLOW, alwaysAllow = true)
    val rememberedSettled = assertIs<ApprovalDecisionResult.Settled>(remembered).request
    assertEquals(ApprovalDecisionSource.OPERATOR_RULE, rememberedSettled.decisionSource)
    val rule = menu.listRules(repository).single()
    val reused = assertIs<ApprovalRegistrationResult.AutoAllowed>(owner.register(command(owner, "ls -la")))
    assertEquals(rule.ruleId, reused.request.ruleId)

    assertTrue(menu.revokeRule(rule.ruleId, repository))
    assertEquals(emptyList(), menu.listRules(repository))
    val afterRevoke = assertIs<ApprovalRegistrationResult.Registered>(owner.register(command(owner, "ls -la")))
    assertEquals(ApprovalState.PENDING, afterRevoke.request.state)
    val once = menu.decide(afterRevoke.request.identity(), consumer, ALLOW, alwaysAllow = false)
    val onceSettled = assertIs<ApprovalDecisionResult.Settled>(once).request
    assertEquals(ApprovalDecisionSource.OPERATOR_ONCE, onceSettled.decisionSource)
    assertNull(onceSettled.ruleId)
    assertEquals(emptyList(), menu.listRules(repository))

    val second = assertIs<ApprovalRegistrationResult.Registered>(owner.register(command(owner, "git status")))
    menu.decide(second.request.identity(), consumer, ALLOW, alwaysAllow = true)
    assertEquals(1, menu.listRules(repository).size)
    val otherProfile = openOwner("profile-b")
    val nonmatching =
      assertIs<ApprovalRegistrationResult.Registered>(otherProfile.register(command(otherProfile, "git status")))
    assertEquals(ApprovalState.PENDING, nonmatching.request.state)
    val changedInput =
      assertIs<ApprovalRegistrationResult.Registered>(owner.register(command(owner, "git status --short")))
    assertEquals(ApprovalState.PENDING, changedInput.request.state)

    val corrupt = assertIs<ApprovalRegistrationResult.Registered>(owner.register(command(owner, "rm -rf build")))
    corruptGrantFacts(corrupt.request.approvalId)
    assertFailsWith<SkillBillRuntimeException> {
      menu.decide(corrupt.request.identity(), consumer, ALLOW, alwaysAllow = true)
    }
    assertFailsWith<SkillBillRuntimeException> { menu.views(repository, Instant.EPOCH) }
    assertNull(terminalDecision(corrupt.request.approvalId))
    assertEquals(1, menu.listRules(repository).size)

    val unaffected = menu.decide(nonmatching.request.identity(), consumer, ALLOW, alwaysAllow = false)
    val unaffectedSettled = assertIs<ApprovalDecisionResult.Settled>(unaffected).request
    assertEquals(ApprovalState.ALLOWED, unaffectedSettled.state)
    assertEquals(ApprovalDecisionSource.OPERATOR_ONCE, unaffectedSettled.decisionSource)
    assertTrue(delivered.none { it.approvalId == corrupt.request.approvalId })
    assertEquals(
      listOf(first.request, reused.request, afterRevoke.request, second.request).map { it.approvalId },
      delivered.filter { it.state == ApprovalState.ALLOWED && it.processGeneration == owner.key.processGeneration }
        .map { it.approvalId },
    )
  }

  @Test
  fun `provider clearance cancels pending without delivery and preserves a committed operator decision`() {
    val consumer = menu.registerConsumer(repository, "desktop").lease.nonce
    val owner = openOwner("profile-a")
    try {
      val pending = assertIs<ApprovalRegistrationResult.Registered>(owner.register(command(owner, "first"))).request
      val cleared =
        assertIs<ApprovalDecisionResult.Settled>(
          owner.clearByProvider(
            pending.approvalId,
            pending.providerRequestId,
            ApprovalProviderClearance.RESOLVED,
          ),
        ).request
      assertEquals(ApprovalState.CANCELLED, cleared.state)
      assertNull(owner.pendingWait())
      assertTrue(delivered.isEmpty())
      assertIs<ApprovalDecisionResult.AlreadySettled>(menu.decide(pending.identity(), consumer, ALLOW, false))
      assertTrue(menu.listRules(repository).isEmpty())

      val next = assertIs<ApprovalRegistrationResult.Registered>(owner.register(command(owner, "second"))).request
      val allowed =
        assertIs<ApprovalDecisionResult.Settled>(
          menu.decide(next.identity(), consumer, ALLOW, false),
        ).request
      val terminal = terminalDecision(next.approvalId)
      val observed =
        assertIs<ApprovalDecisionResult.AlreadySettled>(
          owner.clearByProvider(
            next.approvalId,
            next.providerRequestId,
            ApprovalProviderClearance.DENIED,
          ),
        ).request
      assertEquals(allowed, observed)
      assertEquals(terminal, terminalDecision(next.approvalId))
      assertEquals(listOf(next.approvalId), delivered.map { it.approvalId })
      assertIs<ApprovalDecisionResult.AlreadySettled>(
        owner.clearByProvider(
          next.approvalId,
          next.providerRequestId,
          ApprovalProviderClearance.RESOLVED,
        ),
      )
      assertEquals(1, delivered.size)
    } finally {
      owner.release()
      menu.releaseConsumer(repository, consumer)
    }
  }

  private fun openOwner(profile: String): ApprovalOwnerSession {
    val opened =
      runtime.approvalOwners.open(
        ApprovalOwnerOpenRequest(checkout, "run-1", PROVIDER, ApprovalExecutionMode.ACT, profile, null),
        object : ApprovalOwnerEffects {
          override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState {
            delivered += settled
            return ApprovalDeliveryState.WRITTEN
          }

          override fun stop(reason: String) = Unit
        },
      )
    return assertIs<ApprovalOwnerOpenResult.Opened>(opened).session
  }

  private fun command(
    owner: ApprovalOwnerSession,
    input: String,
  ): ApprovalRegistrationCommand {
    sequence += 1
    val canonical = """{"command":"$input"}"""
    return ApprovalRegistrationCommand(
      request =
        ApprovalRequestRecord(
          contractVersion = OPERATOR_APPROVAL_CONTRACT_VERSION,
          approvalId = "approval-$sequence",
          repositoryIdentity = owner.key.repositoryIdentity,
          checkoutRoot = checkout.toString(),
          runId = owner.key.runId,
          processGeneration = owner.key.processGeneration,
          provider = PROVIDER,
          providerRequestId = ApprovalProviderRequestId.Text("request-$sequence"),
          title = "Allow Bash?",
          subject = "Bash",
          inputText = canonical,
          options =
            listOf(
              ApprovalOption(ALLOW, "Allow once", ONE_TIME_ALLOW_OPTION_KIND),
              ApprovalOption("deny", "Deny", DENY_OPTION_KIND),
            ),
          authorityScope = ApprovalAuthorityScope("", "stream-json-1", "Bash", "", "pending"),
          createdAt = Instant.now().toString(),
          deadlineAt = "",
          state = ApprovalState.PENDING,
          providerPayload = canonical,
        ),
      canonicalInput = canonical,
      actionClass = ApprovalActionClass.ORDINARY_COMMAND,
      writeCapable = true,
      providerDisposition = ApprovalProviderDisposition.NONE,
      enclosingDeadline = null,
    )
  }

  private fun corruptGrantFacts(approvalId: String) {
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      val sql =
        "UPDATE approval_requests SET action_class = 'unknown_class' WHERE " +
          "approval_id = ?"
      connection.prepareStatement(sql).use {
        it.setString(1, approvalId)
        assertEquals(1, it.executeUpdate())
      }
    }
  }

  private fun terminalDecision(approvalId: String): String? =
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      connection.prepareStatement("SELECT terminal_decision_json FROM approval_requests WHERE approval_id = ?").use {
        it.setString(1, approvalId)
        it.executeQuery().use { rows ->
          assertTrue(rows.next())
          rows.getString(1)
        }
      }
    }

  private fun ApprovalRequestRecord.identity() =
    ApprovalRequestIdentity(approvalId, repositoryIdentity, runId, processGeneration, provider, providerRequestId)

  private companion object {
    const val PROVIDER = "claude"
    const val ALLOW = "allow_once"
  }
}
