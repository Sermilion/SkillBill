package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ActivityResult
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.engine.work.timeline.model.WorkActivityResult
import skillbill.model.EnvironmentContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivityObservationsTest {
  private val root = Files.createTempDirectory("activity-observation")
  private val runtime = RuntimeComponent::class.create(RuntimeContext(
    EnvironmentContext(root.resolve("work.db").toString(), environment = emptyMap(), userHome = root, repositoryRoot = root),
    TransportContext(), WorkflowOpsContext(), OptionalCallbacks(),
  ))
  private val diagnostics = object : Diagnostics {
    override fun record(category: String) = Unit
    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }

  @Test
  fun `a stopped goal reads once and does not leave a polling job`() = runTest {
    var reads = 0
    val observations = ActivityObservations(runtime, diagnostics) { reads++; WorkActivityResult.Listed(emptyList()) }
    val values = observations.observe("goal", false).toList()
    assertEquals(listOf(ActivityResult.Loading, ActivityResult.Listed(emptyList())), values)
    assertEquals(1, reads)
  }

  @Test
  fun `cancelling running activity stops scheduled reads`() = runTest {
    var reads = 0
    val observations = ActivityObservations(runtime, diagnostics) { reads++; WorkActivityResult.Listed(emptyList()) }
    val job = launch { observations.observe("goal", true).collect() }
    runCurrent()
    advanceTimeBy(CHANGE_REFRESH_INTERVAL)
    runCurrent()
    assertEquals(2, reads)
    job.cancel()
    advanceTimeBy(CHANGE_REFRESH_INTERVAL * 2)
    runCurrent()
    assertEquals(2, reads)
  }
}
