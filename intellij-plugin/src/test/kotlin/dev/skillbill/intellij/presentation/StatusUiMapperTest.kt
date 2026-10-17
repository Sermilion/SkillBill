package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.CurrentPhaseExecution
import dev.skillbill.intellij.domain.CurrentPhaseModel
import dev.skillbill.intellij.domain.GoalPlanningInfo
import dev.skillbill.intellij.domain.NO_MATCHING_WORK_REASON_CODE
import dev.skillbill.intellij.domain.PauseReason
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusDiagnostic
import dev.skillbill.intellij.domain.UnavailableReason
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.skillbill.intellij.domain.UnavailableReason.TIMEOUT

class StatusUiMapperTest {
    private val now = Instant.parse("2026-08-06T12:00:00Z")
    private val started = Instant.parse("2026-08-06T10:00:00Z")
    private val subtaskStarted = Instant.parse("2026-08-06T11:00:00Z")

    @Test
    fun `maps every domain outcome exhaustively`() {
        val outcomes = listOf(
            SkillBillStatusOutcome.Idle(now, "idle"),
            active(),
            paused(updatedAt = now),
            SkillBillStatusOutcome.Stale(
                observedAt = now,
                summary = "stale",
                repositoryIdentity = "repo",
                issueKey = "SKILL-148",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                progressCompleted = 1,
                progressTotal = 3,
                startedAt = started,
                currentSubtaskId = "2",
                subtaskStartedAt = subtaskStarted,
                updatedAt = now,
            ),
            SkillBillStatusOutcome.Blocked(
                observedAt = now,
                summary = "blocked",
                repositoryIdentity = "repo",
                issueKey = "SKILL-148",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                startedAt = started,
                currentSubtaskId = "2",
                subtaskStartedAt = subtaskStarted,
                updatedAt = now,
            ),
            SkillBillStatusOutcome.Failed(
                observedAt = now,
                summary = "failed",
                repositoryIdentity = "repo",
                issueKey = null,
                currentStepId = null,
                currentStepLabel = null,
                startedAt = null,
                currentSubtaskId = null,
                subtaskStartedAt = null,
                updatedAt = now,
            ),
            done(),
            SkillBillStatusOutcome.Unavailable(
                observedAt = now,
                summary = "missing cli",
                reasonCode = UnavailableReason.MISSING_EXECUTABLE,
            ),
            SkillBillStatusOutcome.Incompatible(
                observedAt = now,
                summary = "bad contract",
                foundContractVersion = "9.9",
            ),
        )
        val mapped = outcomes.map { StatusUiMapper.map(it, now)::class }
        assertEquals(
            listOf(
                SkillBillStatusUiState.Idle::class,
                SkillBillStatusUiState.Active::class,
                SkillBillStatusUiState.Paused::class,
                SkillBillStatusUiState.Stale::class,
                SkillBillStatusUiState.Blocked::class,
                SkillBillStatusUiState.Failed::class,
                SkillBillStatusUiState.Done::class,
                SkillBillStatusUiState.Unavailable::class,
                SkillBillStatusUiState.Incompatible::class,
            ),
            mapped,
        )
    }

    @Test
    fun `idle lifecycle headline is idle and paused lifecycle uses paused wording`() {
        val idleUi = StatusUiMapper.map(SkillBillStatusOutcome.Idle(now, "Goal SKILL-148 is idle.", repositoryIdentity = "repo"), now)
        assertTrue(idleUi is SkillBillStatusUiState.Idle)
        assertEquals("Skill Bill: idle", (idleUi as SkillBillStatusUiState.Idle).headline)
        assertEquals("Skill Bill · idle", SkillBillStatusBarPresentation.map(idleUi).barText)

        val pausedUi = StatusUiMapper.map(paused(updatedAt = now), now)
        assertTrue(pausedUi is SkillBillStatusUiState.Paused)
        assertEquals("Skill Bill: SKILL-165 · paused", (pausedUi as SkillBillStatusUiState.Paused).headline)
        assertTrue(SkillBillStatusBarPresentation.map(pausedUi).barText.contains("paused"))
    }

    @Test
    fun `an idle carrying the unconfirmed marker renders exactly like a plain idle`() {
        val plain = SkillBillStatusOutcome.Idle(now, "idle", repositoryIdentity = "repo")
        val marked = plain.copy(diagnostic = StatusDiagnostic(reasonCode = NO_MATCHING_WORK_REASON_CODE))
        assertEquals(StatusUiMapper.map(plain, now), StatusUiMapper.map(marked, now))
    }

    @Test
    fun `done keeps issue key final progress and a duration frozen at the last update`() {
        val updated = Instant.parse("2026-08-06T11:30:00Z")
        val ui = StatusUiMapper.map(done(updatedAt = updated), now)
        assertTrue(ui is SkillBillStatusUiState.Done)
        ui as SkillBillStatusUiState.Done
        assertEquals("Skill Bill: SKILL-148 · done", ui.headline)
        assertEquals("SKILL-148", ui.issueKey)
        assertEquals(3, ui.progressCompleted)
        assertEquals(3, ui.progressTotal)

        assertEquals(Duration.ofMinutes(90), ui.goalElapsed)
    }

    @Test
    fun `a stale done reading keeps the stale marker`() {
        val ui = StatusUiMapper.map(done(stale = true), now) as SkillBillStatusUiState.Done
        assertTrue(ui.stale)
    }

    
    @Test
    fun `a finished goal reports execution time not wall clock since it started`() {
        val openedAt = Instant.parse("2026-08-08T21:32:57Z")
        val finishedAt = Instant.parse("2026-08-09T05:36:49Z")
        val ui = StatusUiMapper.map(
            done(updatedAt = finishedAt).copy(
                activeDurationMs = Duration.ofMinutes(144).toMillis(),
                activeDurationAsOf = null,
                startedAt = openedAt,
            ),
            finishedAt.plusSeconds(3600),
        ) as SkillBillStatusUiState.Done

        assertEquals(Duration.ofMinutes(144), ui.goalElapsed)
        assertEquals(Duration.ofHours(8).plusMinutes(3).plusSeconds(52), StatusUiMapper.elapsed(openedAt, finishedAt))
    }

    @Test
    fun `a finished goal without an accumulated total keeps the wall-clock fallback`() {
        val updated = Instant.parse("2026-08-06T11:30:00Z")
        val ui = StatusUiMapper.map(done(updatedAt = updated), now) as SkillBillStatusUiState.Done
        assertEquals(Duration.ofMinutes(90), ui.goalElapsed)
    }

    @Test
    fun `a settled goal never adds a tail from a stale live anchor`() {
        val finishedAt = Instant.parse("2026-08-09T05:36:49Z")
        val ui = StatusUiMapper.map(
            done(updatedAt = finishedAt).copy(
                activeDurationMs = Duration.ofMinutes(144).toMillis(),
                activeDurationAsOf = finishedAt,
            ),
            finishedAt.plusSeconds(7200),
        ) as SkillBillStatusUiState.Done
        assertEquals(Duration.ofMinutes(144), ui.goalElapsed)
    }

    private fun done(
        updatedAt: Instant = now,
        stale: Boolean = false,
    ): SkillBillStatusOutcome.Done = SkillBillStatusOutcome.Done(
        observedAt = now,
        summary = "Goal SKILL-148 is complete.",
        repositoryIdentity = "repo",
        issueKey = "SKILL-148",
        progressCompleted = 3,
        progressTotal = 3,
        startedAt = started,
        updatedAt = updatedAt,
        stale = stale,
    )

    @Test
    fun `elapsed ticking is deterministic from injected clock`() {
        val t0 = Instant.parse("2026-08-06T10:00:00Z")
        val t1 = Instant.parse("2026-08-06T10:00:30Z")
        assertEquals(Duration.ofSeconds(30), StatusUiMapper.elapsed(t0, t1))
        val ui = StatusUiMapper.map(active(startedAt = t0, subtaskStartedAt = t0), t1)
        assertTrue(ui is SkillBillStatusUiState.Active)
        ui as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(30), ui.goalElapsed)
        assertEquals(Duration.ofSeconds(30), ui.subtaskElapsed)
    }

    @Test
    fun `wall-clock rollback yields zero elapsed not negative`() {
        val start = Instant.parse("2026-08-06T12:00:00Z")
        val earlier = Instant.parse("2026-08-06T11:00:00Z")
        assertEquals(Duration.ZERO, StatusUiMapper.elapsed(start, earlier))
    }

    @Test
    fun `absent legacy start timestamps stay absent`() {
        val ui = StatusUiMapper.map(
            active(startedAt = null, subtaskStartedAt = null),
            now,
        ) as SkillBillStatusUiState.Active
        assertNull(ui.goalElapsed)
        assertNull(ui.subtaskElapsed)
    }

    
    @Test
    fun `the goal clock counts execution time and excludes the gap a goal spent blocked`() {
        val openedAt = Instant.parse("2026-08-07T18:22:00Z")
        val observedAt = Instant.parse("2026-08-08T06:31:00Z")
        val ui = StatusUiMapper.map(
            active(startedAt = openedAt, subtaskStartedAt = observedAt).copy(
                activeDurationMs = Duration.ofMinutes(23).toMillis(),
                activeDurationAsOf = observedAt,
            ),
            observedAt,
        ) as SkillBillStatusUiState.Active

        assertEquals(Duration.ofMinutes(23), ui.goalElapsed)

        assertEquals(Duration.ofHours(12).plusMinutes(9), StatusUiMapper.elapsed(openedAt, observedAt))
    }

    @Test
    fun `the live tail ticks between polls and the next heartbeat does not double count it`() {
        val asOf = Instant.parse("2026-08-08T06:31:00Z")
        val tenSecondsLater = asOf.plusSeconds(10)
        val accumulated = Duration.ofMinutes(5)
        val ui = StatusUiMapper.map(
            active(startedAt = asOf, subtaskStartedAt = asOf).copy(
                activeDurationMs = accumulated.toMillis(),
                activeDurationAsOf = asOf,
            ),
            tenSecondsLater,
        ) as SkillBillStatusUiState.Active
        assertEquals(accumulated.plusSeconds(10), ui.goalElapsed)


        val afterHeartbeat = StatusUiMapper.map(
            active(startedAt = asOf, subtaskStartedAt = asOf).copy(
                activeDurationMs = accumulated.plusSeconds(10).toMillis(),
                activeDurationAsOf = tenSecondsLater,
            ),
            tenSecondsLater,
        ) as SkillBillStatusUiState.Active
        assertEquals(accumulated.plusSeconds(10), afterHeartbeat.goalElapsed)
    }

    @Test
    fun `a released lease freezes the goal clock at the accumulated total`() {
        val asOf = Instant.parse("2026-08-08T06:31:00Z")
        val ui = StatusUiMapper.map(
            active(startedAt = asOf, subtaskStartedAt = asOf).copy(
                activeDurationMs = Duration.ofMinutes(7).toMillis(),
                activeDurationAsOf = null,
            ),
            asOf.plusSeconds(3600),
        ) as SkillBillStatusUiState.Active
        assertEquals(Duration.ofMinutes(7), ui.goalElapsed)
    }

    @Test
    fun `a snapshot without an accumulated total keeps the previous wall-clock behaviour`() {
        val start = Instant.parse("2026-08-06T10:00:00Z")
        val ui = StatusUiMapper.map(
            active(startedAt = start, subtaskStartedAt = start),
            start.plusSeconds(45),
        ) as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(45), ui.goalElapsed)
    }

    @Test
    fun `withElapsed ticks the active clock from the accumulated total not from startedAt`() {
        val openedAt = Instant.parse("2026-08-07T18:22:00Z")
        val asOf = Instant.parse("2026-08-08T06:31:00Z")
        val active = StatusUiMapper.map(
            active(startedAt = openedAt, subtaskStartedAt = asOf).copy(
                activeDurationMs = Duration.ofMinutes(23).toMillis(),
                activeDurationAsOf = asOf,
            ),
            asOf,
        ) as SkillBillStatusUiState.Active

        val ticked = StatusUiMapper.withElapsed(active, asOf.plusSeconds(5)) as SkillBillStatusUiState.Active

        assertEquals(Duration.ofMinutes(23).plusSeconds(5), ticked.goalElapsed)
    }

    @Test
    fun `withElapsed re-anchors from startedAt and leaves absent subtask absent`() {
        val start = Instant.parse("2026-08-06T10:00:00Z")
        val t1 = Instant.parse("2026-08-06T10:01:00Z")
        val active = StatusUiMapper.map(active(startedAt = start, subtaskStartedAt = null), start)
            as SkillBillStatusUiState.Active
        val reanchored = StatusUiMapper.withElapsed(active, t1) as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(60), reanchored.goalElapsed)
        assertNull(reanchored.subtaskElapsed)
        assertEquals(start, reanchored.startedAt)
    }

    @Test
    fun `settled elapsed is frozen at last update and never ticks with wall clock`() {
        val lastUpdate = Instant.parse("2026-08-06T10:15:00Z")
        val muchLater = Instant.parse("2026-08-09T10:15:00Z")
        val blocked = SkillBillStatusOutcome.Blocked(
            observedAt = muchLater,
            summary = "blocked",
            repositoryIdentity = "repo",
            issueKey = "SKILL-148",
            currentStepId = "implement",
            currentStepLabel = "Implement",
            startedAt = started,
            currentSubtaskId = "2",
            subtaskStartedAt = subtaskStarted,
            updatedAt = lastUpdate,
        )
        val ui = StatusUiMapper.map(blocked, muchLater) as SkillBillStatusUiState.Blocked

        assertEquals(Duration.ofMinutes(15), ui.goalElapsed)
        assertEquals(ui, StatusUiMapper.withElapsed(ui, muchLater.plusSeconds(3_600)))
    }

    @Test
    fun `blocked outcome carries pause reason into presentation details`() {
        val reason = "Configure GITHUB_REGISTRY_AUTH credential"
        val blocked = SkillBillStatusOutcome.Blocked(
            observedAt = now,
            summary = "Goal SKILL-228 is blocked: $reason",
            repositoryIdentity = "repo",
            issueKey = "SKILL-228",
            currentStepId = "validate",
            currentStepLabel = "Validate",
            startedAt = started,
            currentSubtaskId = "2",
            subtaskStartedAt = subtaskStarted,
            updatedAt = now,
            pauseReason = PauseReason("awaiting_operator_decision", reason),
        )
        val ui = StatusUiMapper.map(blocked, now) as SkillBillStatusUiState.Blocked
        assertEquals(reason, ui.pauseReason?.label)
        val presentation = SkillBillStatusBarPresentation.map(ui, now)
        assertEquals(reason, presentation.details.pauseReasonText)
    }

    @Test
    fun `stale elapsed is frozen at last update`() {
        val lastUpdate = Instant.parse("2026-08-06T10:20:00Z")
        val muchLater = Instant.parse("2026-08-09T10:20:00Z")
        val stale = SkillBillStatusOutcome.Stale(
            observedAt = muchLater,
            summary = "stale",
            repositoryIdentity = "repo",
            issueKey = "SKILL-148",
            currentStepId = "implement",
            currentStepLabel = "Implement",
            progressCompleted = 0,
            progressTotal = 1,
            startedAt = started,
            currentSubtaskId = "2",
            subtaskStartedAt = null,
            updatedAt = lastUpdate,
        )
        val ui = StatusUiMapper.map(stale, muchLater) as SkillBillStatusUiState.Stale
        assertEquals(Duration.ofMinutes(20), ui.goalElapsed)
        assertEquals(ui, StatusUiMapper.withElapsed(ui, muchLater.plusSeconds(3_600)))
    }

    @Test
    fun `paused maps to Paused with elapsed anchored to updatedAt not now`() {
        val lastUpdate = Instant.parse("2026-08-06T10:30:00Z")
        val muchLater = Instant.parse("2026-08-09T10:30:00Z")
        val ui = StatusUiMapper.map(paused(updatedAt = lastUpdate), muchLater)
        assertTrue(ui is SkillBillStatusUiState.Paused)
        ui as SkillBillStatusUiState.Paused

        assertEquals(Duration.ofMinutes(30), ui.goalElapsed)
        assertTrue(ui.headline.contains("paused"))
        assertEquals(false, ui.stale)
    }

    @Test
    fun `withElapsed does not tick a paused state but still re-anchors active`() {
        val lastUpdate = Instant.parse("2026-08-06T10:30:00Z")
        val paused = StatusUiMapper.map(paused(updatedAt = lastUpdate), lastUpdate)
        assertEquals(paused, StatusUiMapper.withElapsed(paused, lastUpdate.plusSeconds(3_600)))

        val active = StatusUiMapper.map(active(startedAt = started), started)
        val ticked = StatusUiMapper.withElapsed(active, started.plusSeconds(45))
            as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(45), ticked.goalElapsed)
    }

    @Test
    fun `planning is carried only while it is still relevant`() {
        val planning = GoalPlanningInfo(
            state = "partially_planned",
            sharedPreplanPrepared = false,
            plannedSubtaskCount = 1,
            totalSubtaskCount = 4,
        )
        val relevant = StatusUiMapper.map(
            active(
                planning = planning,
                currentSubtaskId = null,
                progressCompleted = 0,
                currentStepId = "plan",
                currentStepLabel = "Plan",
            ),
            now,
        ) as SkillBillStatusUiState.Active
        assertEquals(planning, relevant.planning)

        val prepared = StatusUiMapper.map(
            active(planning = planning.copy(state = "prepared"), currentSubtaskId = null, progressCompleted = 0),
            now,
        ) as SkillBillStatusUiState.Active
        assertNull(prepared.planning)

        val executing = StatusUiMapper.map(
            active(planning = planning, currentSubtaskId = "2", progressCompleted = 0),
            now,
        ) as SkillBillStatusUiState.Active
        assertNull(executing.planning)

        val progressed = StatusUiMapper.map(
            active(planning = planning, currentSubtaskId = null, progressCompleted = 1),
            now,
        ) as SkillBillStatusUiState.Active
        assertNull(progressed.planning)
    }

    @Test
    fun `stale mid-planning retains the planning value`() {
        val planning = GoalPlanningInfo(
            state = "preplanned",
            sharedPreplanPrepared = true,
            plannedSubtaskCount = 0,
            totalSubtaskCount = 4,
        )
        val ui = StatusUiMapper.map(
            SkillBillStatusOutcome.Stale(
                observedAt = now,
                summary = "stale",
                repositoryIdentity = "repo",
                issueKey = "SKILL-165",
                currentStepId = "plan",
                currentStepLabel = "Plan",
                progressCompleted = 0,
                progressTotal = 4,
                startedAt = started,
                currentSubtaskId = null,
                subtaskStartedAt = null,
                updatedAt = now,
                planning = planning,
            ),
            now,
        ) as SkillBillStatusUiState.Stale
        assertEquals(planning, ui.planning)
        assertNull(ui.currentPhaseExecution)
    }

    @Test
    fun `active execution clears planning and retains the typed current-phase value`() {
        val planning = GoalPlanningInfo(
            state = "partially_planned",
            sharedPreplanPrepared = true,
            plannedSubtaskCount = 10,
            totalSubtaskCount = 15,
        )
        val execution = CurrentPhaseExecution(
            phaseId = "audit",
            kind = "semantic_loop",
            count = 2,
        )
        val ui = StatusUiMapper.map(
            active(
                planning = planning,
                currentSubtaskId = "2",
                progressCompleted = 0,
            ).copy(currentPhaseExecution = execution),
            now,
        ) as SkillBillStatusUiState.Active
        assertNull(ui.planning)
        assertEquals(execution, ui.currentPhaseExecution)
    }

    @Test
    fun `stale mid-planning does not synthesize an execution value`() {
        val planning = GoalPlanningInfo(
            state = "partially_planned",
            sharedPreplanPrepared = false,
            plannedSubtaskCount = 1,
            totalSubtaskCount = 15,
        )
        val ui = StatusUiMapper.map(
            SkillBillStatusOutcome.Stale(
                observedAt = now,
                summary = "stale",
                repositoryIdentity = "repo",
                issueKey = "SKILL-184",
                currentStepId = "plan",
                currentStepLabel = "Plan",
                progressCompleted = 0,
                progressTotal = 15,
                startedAt = started,
                currentSubtaskId = null,
                subtaskStartedAt = null,
                updatedAt = now,
                planning = planning,
                currentPhaseExecution = null,
            ),
            now,
        ) as SkillBillStatusUiState.Stale
        assertEquals(planning, ui.planning)
        assertNull(ui.currentPhaseExecution)
    }

    @Test
    fun `workflow family and pause flag survive Active and Paused mapping`() {
        val activeUi = StatusUiMapper.map(
            active().copy(workflowFamily = "feature-goal", pauseRequested = true),
            now,
        ) as SkillBillStatusUiState.Active
        assertEquals("feature-goal", activeUi.workflowFamily)
        assertEquals(true, activeUi.pauseRequested)

        val pausedUi = StatusUiMapper.map(
            paused(now).copy(pauseRequested = false),
            now,
        ) as SkillBillStatusUiState.Paused
        assertEquals("feature-goal", pausedUi.workflowFamily)
        assertEquals(false, pausedUi.pauseRequested)


        val absent = StatusUiMapper.map(active(), now) as SkillBillStatusUiState.Active
        assertNull(absent.pauseRequested)
    }

    @Test
    fun `every other outcome type carries no goal-control inputs`() {
        val outcomes = listOf(
            SkillBillStatusOutcome.Idle(now, "idle"),
            SkillBillStatusOutcome.Unavailable(now, "gone", TIMEOUT),
            SkillBillStatusOutcome.Incompatible(now, "mismatch", "0.9"),
        )
        for (outcome in outcomes) {
            val ui = StatusUiMapper.map(outcome, now)
            assertNull(ui.workflowFamily)
            assertNull(ui.pauseRequested)
        }
    }

    @Test
    fun `the current phase model survives the mapping on every outcome that carries it`() {
        val model = CurrentPhaseModel(model = "opus-5", effort = "high", phaseId = "implement")




        val carriers = listOf<Pair<String, SkillBillStatusOutcome>>(
            "Active" to active().copy(currentModel = model),
            "Paused" to paused(now).copy(currentModel = model),
            "Stale" to staleOutcome().copy(currentModel = model),
            "Blocked" to blockedOutcome().copy(currentModel = model),
            "Failed" to failedOutcome().copy(currentModel = model),
        )

        carriers.forEach { (case, outcome) ->
            assertEquals("$case must carry the model", model, StatusUiMapper.map(outcome, now).currentModel)
        }
        assertNull(StatusUiMapper.map(active(), now).currentModel)
    }

    private fun staleOutcome() = SkillBillStatusOutcome.Stale(
        observedAt = now,
        summary = "stale",
        repositoryIdentity = "repo",
        issueKey = "SKILL-148",
        currentStepId = "implement",
        currentStepLabel = "Implement",
        progressCompleted = 1,
        progressTotal = 4,
        startedAt = started,
        currentSubtaskId = "2",
        subtaskStartedAt = subtaskStarted,
        updatedAt = now,
    )

    private fun blockedOutcome() = SkillBillStatusOutcome.Blocked(
        observedAt = now,
        summary = "blocked",
        repositoryIdentity = "repo",
        issueKey = "SKILL-148",
        currentStepId = "implement",
        currentStepLabel = "Implement",
        startedAt = started,
        currentSubtaskId = "2",
        subtaskStartedAt = subtaskStarted,
        updatedAt = now,
    )

    private fun failedOutcome() = SkillBillStatusOutcome.Failed(
        observedAt = now,
        summary = "failed",
        repositoryIdentity = "repo",
        issueKey = "SKILL-148",
        currentStepId = "implement",
        currentStepLabel = "Implement",
        startedAt = started,
        currentSubtaskId = "2",
        subtaskStartedAt = subtaskStarted,
        updatedAt = now,
    )

    @Test
    fun `subtask uses accumulator across pause gap while started_at would overcount`() {
        val openedAt = Instant.parse("2026-08-07T18:22:00Z")
        val subtaskOpenedAt = Instant.parse("2026-08-07T19:00:00Z")
        val observedAt = Instant.parse("2026-08-08T06:31:00Z")
        val ui = StatusUiMapper.map(
            active(startedAt = openedAt, subtaskStartedAt = subtaskOpenedAt).copy(
                activeDurationMs = Duration.ofMinutes(23).toMillis(),
                activeDurationAsOf = observedAt,
                subtaskActiveDurationMs = Duration.ofMinutes(20).toMillis(),
                subtaskActiveDurationAsOf = observedAt,
            ),
            observedAt,
        ) as SkillBillStatusUiState.Active

        assertEquals(Duration.ofMinutes(23), ui.goalElapsed)
        assertEquals(Duration.ofMinutes(20), ui.subtaskElapsed)
        assertTrue(ui.subtaskElapsed!! < StatusUiMapper.elapsed(subtaskOpenedAt, observedAt)!!)
    }

    @Test
    fun `resume continues from frozen totals without adding downtime gap`() {
        val asOf = Instant.parse("2026-08-08T06:31:00Z")
        val resumedAt = asOf.plus(Duration.ofMinutes(5))
        val ui = StatusUiMapper.map(
            active().copy(
                activeDurationMs = Duration.ofMinutes(23).toMillis(),
                activeDurationAsOf = asOf,
                subtaskActiveDurationMs = Duration.ofMinutes(20).toMillis(),
                subtaskActiveDurationAsOf = asOf,
            ),
            resumedAt,
        ) as SkillBillStatusUiState.Active

        assertEquals(Duration.ofMinutes(28), ui.goalElapsed)
        assertEquals(Duration.ofMinutes(25), ui.subtaskElapsed)
    }

    @Test
    fun `subtask elapsed never exceeds goal elapsed`() {
        val asOf = Instant.parse("2026-08-08T06:31:00Z")
        val ui = StatusUiMapper.map(
            active().copy(
                activeDurationMs = Duration.ofMinutes(23).toMillis(),
                activeDurationAsOf = asOf,
                subtaskActiveDurationMs = Duration.ofMinutes(30).toMillis(),
                subtaskActiveDurationAsOf = asOf,
            ),
            asOf,
        ) as SkillBillStatusUiState.Active

        assertEquals(Duration.ofMinutes(23), ui.goalElapsed)
        assertEquals(Duration.ofMinutes(23), ui.subtaskElapsed)
    }

    @Test
    fun `withElapsed does not advance Paused or Blocked`() {
        val lastUpdate = Instant.parse("2026-08-06T10:30:00Z")
        val paused = StatusUiMapper.map(
            paused(updatedAt = lastUpdate).copy(
                activeDurationMs = Duration.ofMinutes(10).toMillis(),
                subtaskActiveDurationMs = Duration.ofMinutes(8).toMillis(),
            ),
            lastUpdate,
        )
        assertEquals(paused, StatusUiMapper.withElapsed(paused, lastUpdate.plusSeconds(3_600)))

        val blocked = StatusUiMapper.map(
            blockedOutcome().copy(
                activeDurationMs = Duration.ofMinutes(10).toMillis(),
                subtaskActiveDurationMs = Duration.ofMinutes(8).toMillis(),
            ),
            lastUpdate,
        )
        assertEquals(blocked, StatusUiMapper.withElapsed(blocked, lastUpdate.plusSeconds(3_600)))
    }

    @Test
    fun `Active with pauseRequested still ticks on withElapsed`() {
        val asOf = Instant.parse("2026-08-08T06:31:00Z")
        val active = StatusUiMapper.map(
            active().copy(
                pauseRequested = true,
                activeDurationMs = Duration.ofMinutes(5).toMillis(),
                activeDurationAsOf = asOf,
                subtaskActiveDurationMs = Duration.ofMinutes(4).toMillis(),
                subtaskActiveDurationAsOf = asOf,
            ),
            asOf,
        ) as SkillBillStatusUiState.Active

        val ticked = StatusUiMapper.withElapsed(active, asOf.plusSeconds(10)) as SkillBillStatusUiState.Active
        assertEquals(Duration.ofMinutes(5).plusSeconds(10), ticked.goalElapsed)
        assertEquals(Duration.ofMinutes(4).plusSeconds(10), ticked.subtaskElapsed)
    }

    private fun paused(updatedAt: Instant) = SkillBillStatusOutcome.Paused(
        observedAt = now,
        summary = "paused",
        repositoryIdentity = "repo-root-realpath-v1:/repo",
        issueKey = "SKILL-165",
        workflowId = "wfl-1",
        workflowFamily = "feature-goal",
        currentStepId = "implement",
        currentStepLabel = "Implement",
        progressCompleted = 1,
        progressTotal = 4,
        startedAt = started,
        currentSubtaskId = "2",
        subtaskStartedAt = subtaskStarted,
        updatedAt = updatedAt,
    )

    private fun active(
        startedAt: Instant? = started,
        subtaskStartedAt: Instant? = subtaskStarted,
        planning: GoalPlanningInfo? = null,
        currentSubtaskId: String? = "2",
        progressCompleted: Int? = 3,
        currentStepId: String = "implement",
        currentStepLabel: String = "Implement",
    ) = SkillBillStatusOutcome.Active(
        observedAt = now,
        summary = "active",
        repositoryIdentity = "repo-root-realpath-v1:/repo",
        issueKey = "SKILL-148",
        workflowId = "wfl-1",
        workflowFamily = "feature-task-runtime",
        currentStepId = currentStepId,
        currentStepLabel = currentStepLabel,
        progressCompleted = progressCompleted,
        progressTotal = 9,
        startedAt = startedAt,
        currentSubtaskId = currentSubtaskId,
        subtaskStartedAt = subtaskStartedAt,
        updatedAt = now,
        planning = planning,
    )
}
