package skillbill.agent.buildrun

import skillbill.agent.model.BuildCommandEvent
import skillbill.agent.model.BuildCommandEventKind
import skillbill.agent.model.BuildMatch
import skillbill.agent.model.BuildOutcome
import skillbill.agent.model.BuildTrackerEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildTrackerTest {
  private val t0: Instant = Instant.parse("2026-11-07T10:00:00Z")
  private val clock: Clock = Clock.fixed(t0, ZoneOffset.UTC)

  @Test
  fun `a completed call gives the ended build its outcome and exit code`() {
    val cases =
      listOf(
        Triple(BuildCommandEventKind.COMPLETED, 101, BuildOutcome.FAILED to 101),
        Triple(BuildCommandEventKind.COMPLETED, null, BuildOutcome.PASSED to 0),
        Triple(BuildCommandEventKind.FAILED, null, BuildOutcome.FAILED to null),
      )

    cases.forEach { (kind, exitCode, expected) ->
      val tracker = BuildTracker(clock)
      tracker.command(call("c1", BuildCommandEventKind.STARTED, second = 0))
      tracker.observe(listOf(match(7)), at(1))
      assertEquals(emptyList(), tracker.observe(emptyList(), at(5)))

      val settled = tracker.command(call("c1", kind, exitCode, second = 6)).single() as BuildTrackerEvent.Settled

      assertEquals(expected, settled.outcome to settled.exitCode, "kind=$kind exit=$exitCode")
      assertEquals(4_000L, settled.durationMs)
    }
  }

  @Test
  fun `two builds in one failing call settle the last one failed and the first unknown`() {
    val tracker = BuildTracker(clock)
    tracker.command(call("c1", BuildCommandEventKind.STARTED, second = 0, text = "./gradlew build && ./gradlew test"))
    tracker.observe(listOf(match(1)), at(1))
    tracker.observe(emptyList(), at(3))
    tracker.observe(listOf(match(2)), at(4))
    tracker.observe(emptyList(), at(9))

    val settled =
      tracker.command(call("c1", BuildCommandEventKind.FAILED, second = 10)).map { it as BuildTrackerEvent.Settled }

    assertEquals(
      mapOf(1L to (BuildOutcome.UNKNOWN to null), 2L to (BuildOutcome.FAILED to null)),
      settled.associate { it.pid to (it.outcome to it.exitCode) },
    )
  }

  @Test
  fun `a call that completes while its build is alive leaves the build unknown`() {
    val tracker = BuildTracker(clock)
    tracker.command(call("c1", BuildCommandEventKind.STARTED, second = 0))
    tracker.observe(listOf(match(7)), at(1))
    assertEquals(emptyList(), tracker.command(call("c1", BuildCommandEventKind.COMPLETED, 0, second = 2)))

    val settled = tracker.observe(emptyList(), at(8)).single() as BuildTrackerEvent.Settled

    assertEquals(BuildOutcome.UNKNOWN to null, settled.outcome to settled.exitCode)
  }

  @Test
  fun `finishing the launch ends an open build at the finish instant as unknown`() {
    val tracker = BuildTracker(clock)
    tracker.command(call("c1", BuildCommandEventKind.STARTED, second = 0, text = "cargo build --release"))
    tracker.observe(listOf(match(7, executable = "cargo")), at(1))

    val settled = tracker.finish(at(30)).single() as BuildTrackerEvent.Settled

    assertEquals(BuildOutcome.UNKNOWN to null, settled.outcome to settled.exitCode)
    assertEquals(at(30), settled.endedAt)
    assertEquals(emptyList(), tracker.finish(at(40)))
  }

  @Test
  fun `a build correlates with the call naming its executable as a word, not a later call containing it`() {
    val tracker = BuildTracker(clock)
    tracker.command(call("go", BuildCommandEventKind.STARTED, second = 0, text = "go test ./..."))
    tracker.command(call("cat", BuildCommandEventKind.STARTED, second = 1, text = "cat docs/algorithm.md"))
    tracker.observe(listOf(match(7, executable = "go")), at(2))
    tracker.observe(emptyList(), at(5))
    assertEquals(emptyList(), tracker.command(call("cat", BuildCommandEventKind.COMPLETED, 0, second = 6)))

    val settled = tracker.command(call("go", BuildCommandEventKind.COMPLETED, 1, second = 7)).single()

    assertEquals(BuildOutcome.FAILED to 1, (settled as BuildTrackerEvent.Settled).let { it.outcome to it.exitCode })
  }

  private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

  private fun call(
    id: String,
    kind: BuildCommandEventKind,
    exitCode: Int? = null,
    second: Long,
    text: String = "./gradlew check",
  ) = BuildCommandEvent(id, text, kind, exitCode, at(second))

  private fun match(
    pid: Long,
    executable: String = "gradlew",
  ) = BuildMatch(
    pid = pid,
    startedAt = null,
    executable = executable,
    displayExecutable = if (executable == "gradlew") "./gradlew" else executable,
    displayCommand = "$executable build",
    shortCommand = "$executable build",
    toolLabel = executable,
    projectKind = "Kotlin/JVM",
  )
}
