package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.data.RecordingDiagnostics
import dev.skillbill.tray.core.data.port.CommitSignalSource
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.port.RuntimeSpecGateway
import dev.skillbill.tray.core.data.port.SignalTopics
import dev.skillbill.tray.core.data.port.SpecDirectoryChanges
import dev.skillbill.tray.core.data.port.SpecPurgeOutcome
import dev.skillbill.tray.core.data.port.StoreChange
import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuntimeSpecBrowserRepositoryTest {
  private class Gateway : RuntimeSpecGateway {
    var start: ControlResult = ControlResult.Accepted
    var purge = SpecPurgeOutcome(null, emptyList())

    override fun databasePath(): String? = null

    override fun projects(): List<SpecProject> = error("unused")

    override fun projectFor(repositoryRoot: String): SpecProject = error("unused")

    override fun snapshot(projectId: String, preferredRepoRoot: String?): SpecSnapshot = error("unused")

    override fun control(projectId: String, bundlePath: String, preferredRepoRoot: String?): SpecBundleControl = error("unused")

    override fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlResult = start

    override fun purgeGoal(repoRoot: String, issueKey: String): SpecPurgeOutcome = purge
  }

  private val gateway = Gateway()
  private val repository = RuntimeSpecBrowserRepository(
    gateway,
    object : SpecDirectoryChanges {
      override fun changes(specsRoot: String): Flow<Unit> = emptyFlow()
    },
    object : CommitSignalSource {
      override val topics = SignalTopics(setOf("goals"), "goals", "phases", "activity")

      override fun changes(databasePath: String, ready: CompletableDeferred<Unit>): Flow<StoreChange> = emptyFlow()
    },
    DispatcherProvider(io = Dispatchers.IO, ui = Dispatchers.IO),
    RecordingDiagnostics(),
  )

  private fun summary(outcome: Outcome<Unit>) = assertIs<Outcome.Failure>(outcome).problem.summary

  @Test
  fun `a purge reports a refusal and leftover run data as failures and only a clean purge as success`() = runBlocking {
    gateway.purge = SpecPurgeOutcome("Goal is running.", emptyList())
    assertEquals("Goal is running.", summary(repository.purgeGoal("/repo", "SKILL-1")))

    gateway.purge = SpecPurgeOutcome(null, listOf("a", "b"))
    assertEquals("Run data is still present: a, b", summary(repository.purgeGoal("/repo", "SKILL-1")))

    gateway.purge = SpecPurgeOutcome(null, emptyList())
    assertEquals(Outcome.Value(Unit), repository.purgeGoal("/repo", "SKILL-1"))
  }

  @Test
  fun `a launch failure keeps the runtime's reason unprefixed`() = runBlocking {
    gateway.start = ControlResult.LaunchFailed("x")
    assertEquals("x", summary(repository.startGoal("/repo", "SKILL-1", null)))
  }
}
