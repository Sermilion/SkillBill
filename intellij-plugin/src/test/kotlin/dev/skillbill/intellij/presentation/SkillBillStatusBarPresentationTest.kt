package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.CurrentPhaseExecution
import dev.skillbill.intellij.domain.CurrentPhaseModel
import dev.skillbill.intellij.domain.FEATURE_GOAL_WORKFLOW_FAMILY
import dev.skillbill.intellij.domain.MODEL_TEXT_MAX_LENGTH
import dev.skillbill.intellij.domain.GoalPlanningInfo
import dev.skillbill.intellij.domain.POLL_FAILED_REASON_CODE
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusDiagnostic
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillBillStatusBarPresentationTest {
    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val started = Instant.parse("2026-08-07T10:00:00Z")
    private val subtaskStarted = Instant.parse("2026-08-07T11:30:00Z")

    @Test
    fun `active bar includes step dual elapsed and valid progress`() {
        val mapped = SkillBillStatusBarPresentation.map(active(), now)
        assertTrue(mapped.barText.startsWith("Skill Bill · Implement"))
        assertTrue(mapped.barText.contains("2h 00m"))
        assertTrue(mapped.barText.contains("30m 00s"))
        assertTrue(mapped.barText.contains("4/9"))
        assertTrue(mapped.showActivityAnimation)
        assertFalse(mapped.isStaleMarked)
    }

    @Test
    fun `active bar omits progress when total is invalid`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(progressCompleted = 2, progressTotal = 0),
            now,
        )
        assertFalse(mapped.barText.contains("/"))
        assertEquals(null, mapped.details.progressText)
    }

    @Test
    fun `active bar omits progress when completed exceeds total`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(progressCompleted = 5, progressTotal = 3),
            now,
        )
        assertEquals(null, mapped.details.progressText)
    }

    @Test
    fun `missing timestamps render unavailable not zero`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(goalElapsed = null, subtaskElapsed = null, startedAt = null, subtaskStartedAt = null),
            now,
        )
        assertTrue(mapped.barText.contains(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED))
        assertEquals(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED, mapped.details.goalElapsedText)
        assertEquals(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED, mapped.details.subtaskElapsedText)
        assertFalse(mapped.barText.contains("0s"))
    }

    @Test
    fun `clock skew clamp to zero renders as zero duration not negative`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(
                startedAt = now.plusSeconds(30),
                subtaskStartedAt = now.plusSeconds(30),
            ),
            now,
        )
        assertEquals("0s", mapped.details.goalElapsedText)
        assertEquals("0s", mapped.details.subtaskElapsedText)
        assertFalse(mapped.details.goalElapsedText.contains("-"))
    }

    @Test
    fun `truncates long unsafe labels on bar and keeps full context in tooltip`() {
        val longStep = "Implement ".repeat(20) + "\u0007secret"
        val state = active(stepLabel = longStep, headline = "Skill Bill: $longStep")
        val mapped = SkillBillStatusBarPresentation.map(state, now)
        assertTrue(mapped.barText.length <= SkillBillStatusBarPresentation.BAR_TEXT_MAX_LENGTH)
        assertTrue(mapped.barText.endsWith("…"))
        assertFalse(mapped.barText.contains("\u0007"))
        assertTrue(mapped.tooltipText.contains("Implement"))
        assertTrue(mapped.tooltipText.length > mapped.barText.length)
    }

    @Test
    fun `distinct concise text for every non-active lifecycle`() {
        val cases = listOf(
            SkillBillStatusUiState.Idle() to "Skill Bill · idle",
            SkillBillStatusUiState.Blocked(
                headline = "Skill Bill: blocked",
                detail = "needs input",
                goalElapsed = null,
                subtaskElapsed = null,
                problemSummary = "needs input",
            ) to "Skill Bill · blocked",
            SkillBillStatusUiState.Failed(
                headline = "Skill Bill: failed",
                detail = "boom",
                goalElapsed = null,
                subtaskElapsed = null,
                problemSummary = "boom",
            ) to "Skill Bill · failed",
            SkillBillStatusUiState.Unavailable(
                headline = "Skill Bill: unavailable",
                detail = "missing cli",
                reasonCode = "MISSING_EXECUTABLE",
                problemSummary = "MISSING_EXECUTABLE: missing cli",
            ) to "Skill Bill · unavailable",
            SkillBillStatusUiState.Incompatible(
                headline = "Skill Bill: incompatible",
                detail = "bad contract",
                foundContractVersion = "9.9",
                problemSummary = "Contract mismatch",
            ) to "Skill Bill · incompatible",
            SkillBillStatusUiState.Stale(
                headline = "Skill Bill: Implement (stale)",
                detail = "cached",
                goalElapsed = Duration.ofMinutes(5),
                subtaskElapsed = null,
                progressCompleted = null,
                progressTotal = null,
                stepLabel = "Implement",
            ) to "Skill Bill · stale",
        )
        for ((state, expectedPrefix) in cases) {
            val mapped = SkillBillStatusBarPresentation.map(state, now)
            assertTrue("$expectedPrefix in ${mapped.barText}", mapped.barText.startsWith(expectedPrefix))
            assertFalse(mapped.showActivityAnimation)
            assertTrue(mapped.tooltipText.contains("Skill Bill"))
        }
    }

    @Test
    fun `stale is visibly marked and never animates`() {
        val mapped = SkillBillStatusBarPresentation.map(
            SkillBillStatusUiState.Stale(
                headline = "Skill Bill: Implement (stale)",
                detail = "cached",
                goalElapsed = Duration.ofMinutes(1),
                subtaskElapsed = Duration.ofSeconds(30),
                progressCompleted = null,
                progressTotal = null,
                stepLabel = "Implement",
            ),
            now,
        )
        assertTrue(mapped.isStaleMarked)
        assertFalse(mapped.showActivityAnimation)
        assertTrue(mapped.tooltipText.contains("Stale"))
    }

    @Test
    fun `a stale caveat reaches every surface that renders the frozen numbers`() {
        val staleBlocked = SkillBillStatusUiState.Blocked(
            headline = "Skill Bill: blocked",
            detail = "Goal SKILL-161 is blocked (1 subtask).",
            goalElapsed = Duration.ofMinutes(14),
            subtaskElapsed = Duration.ofSeconds(5),
            stepLabel = "Implement",
            stale = true,
        )
        val mapped = SkillBillStatusBarPresentation.map(staleBlocked, now)
        assertTrue(mapped.isStaleMarked)
        assertTrue(mapped.tooltipText.contains(SkillBillStatusBarPresentation.STALE_NOTE))
        assertEquals(SkillBillStatusBarPresentation.STALE_NOTE, mapped.details.staleNote)

        val live = SkillBillStatusBarPresentation.map(active(), now)
        assertFalse(live.isStaleMarked)
        assertEquals(null, live.details.staleNote)
    }

    @Test
    fun `done bar shows the settled run without claiming liveness`() {
        val done = SkillBillStatusUiState.Done(
            headline = "Skill Bill: SKILL-167 · complete",
            detail = "Goal SKILL-167 is complete.",
            goalElapsed = Duration.ofMinutes(86),
            progressCompleted = 3,
            progressTotal = 3,
            issueKey = "SKILL-167",
            lastUpdated = now,
        )
        val mapped = SkillBillStatusBarPresentation.map(done, now)
        assertTrue(mapped.barText.startsWith("Skill Bill · complete"))

        assertTrue(mapped.barText.contains("3/3"))
        assertFalse(mapped.showActivityAnimation)
        assertFalse(mapped.isStaleMarked)
        assertEquals("complete", mapped.details.lifecycleState)
        assertEquals("ran", mapped.details.elapsedNoun)
        assertTrue(mapped.tooltipText.contains("Issue: SKILL-167"))
    }

    @Test
    fun `a stale terminal reading still carries the caveat`() {
        val mapped = SkillBillStatusBarPresentation.map(SkillBillStatusUiState.Idle(stale = true), now)
        assertTrue(mapped.isStaleMarked)
        assertTrue(mapped.tooltipText.contains(SkillBillStatusBarPresentation.STALE_NOTE))
        assertEquals(SkillBillStatusBarPresentation.STALE_NOTE, mapped.details.staleNote)
    }

    @Test
    fun `states describing no run never claim one ran`() {


        val neutral = listOf(
            SkillBillStatusUiState.Idle(),
            SkillBillStatusUiState.Unavailable(
                headline = "Skill Bill: unavailable",
                detail = null,
                reasonCode = "MISSING_EXECUTABLE",
            ),
            SkillBillStatusUiState.Incompatible(
                headline = "Skill Bill: incompatible",
                detail = null,
                foundContractVersion = "9.9",
            ),
        )
        for (state in neutral) {
            val mapped = SkillBillStatusBarPresentation.map(state, now)
            assertEquals("elapsed", mapped.details.elapsedNoun)
            assertFalse(mapped.tooltipText.contains("Goal ran"))
        }

        val settled = SkillBillStatusBarPresentation.map(
            SkillBillStatusUiState.Failed(
                headline = "Skill Bill: failed",
                detail = null,
                goalElapsed = Duration.ofMinutes(3),
                subtaskElapsed = null,
            ),
            now,
        )
        assertEquals("ran", settled.details.elapsedNoun)
    }

    @Test
    fun `accessibility includes Skill Bill state step both clocks and progress`() {
        val mapped = SkillBillStatusBarPresentation.map(active(), now)
        assertTrue(mapped.accessibleName.contains("Skill Bill"))
        assertTrue(mapped.accessibleName.contains("active"))
        assertTrue(mapped.accessibleDescription.contains("Skill Bill"))
        assertTrue(mapped.accessibleDescription.contains("active"))
        assertTrue(mapped.accessibleDescription.contains("Implement"))
        assertTrue(mapped.accessibleDescription.contains("Goal elapsed"))
        assertTrue(mapped.accessibleDescription.contains("Subtask elapsed"))
        assertTrue(mapped.accessibleDescription.contains("4/9"))
    }

    @Test
    fun `details carry safe issue workflow state step progress clocks last update and problem`() {
        val mapped = SkillBillStatusBarPresentation.map(active(), now)
        val d = mapped.details
        assertEquals("SKILL-148", d.issueKey)
        assertEquals("wfl-1", d.workflowId)
        assertEquals("active", d.lifecycleState)
        assertEquals("Implement", d.stepLabel)
        assertEquals("4/9", d.progressText)
        assertEquals("2h 00m", d.goalElapsedText)
        assertEquals("30m 00s", d.subtaskElapsedText)
        assertTrue(d.lastUpdateText!!.contains("2026-08-07"))
        assertEquals("working", d.problemSummary)
    }

    @Test
    fun `validProgress rejects null negative and inverted bounds`() {
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(null, 3))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(1, null))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(-1, 3))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(1, 0))
        assertEquals(null, SkillBillStatusBarPresentation.validProgress(4, 3))
        assertEquals(1 to 3, SkillBillStatusBarPresentation.validProgress(1, 3))
    }

    @Test
    fun `re-anchor via now advances elapsed from startedAt without synthesizing`() {
        val state = active(
            goalElapsed = Duration.ofSeconds(1),
            subtaskElapsed = null,
            startedAt = started,
            subtaskStartedAt = null,
        )
        val later = started.plusSeconds(90)
        val mapped = SkillBillStatusBarPresentation.map(state, later)
        assertEquals("1m 30s", mapped.details.goalElapsedText)
        assertEquals(SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED, mapped.details.subtaskElapsedText)
    }

    @Test
    fun `planning renders the full line and planning progress without the execution offset`() {
        val partiallyPlanned = SkillBillStatusBarPresentation.map(
            active(
                progressCompleted = 0,
                progressTotal = 15,
                planning = GoalPlanningInfo(
                    state = "partially_planned",
                    sharedPreplanPrepared = true,
                    plannedSubtaskCount = 10,
                    totalSubtaskCount = 15,
                ),
            ),
            now,
        )
        val fullLine = "Planning: partially planned, 10/15 plans saved"
        assertTrue(partiallyPlanned.barText.contains("Planning 10/15"))
        assertTrue(
            partiallyPlanned.barText.length <= SkillBillStatusBarPresentation.BAR_TEXT_MAX_LENGTH,
        )
        assertTrue(partiallyPlanned.tooltipText.contains(fullLine))
        assertTrue(partiallyPlanned.accessibleDescription.contains(fullLine))
        assertEquals("Planning", partiallyPlanned.details.selectedSlotLabel)
        assertEquals("partially planned, 10/15 plans saved", partiallyPlanned.details.selectedSlotText)
        assertEquals("10/15", partiallyPlanned.details.progressText)


        val oneSaved = SkillBillStatusBarPresentation.map(
            active(
                progressCompleted = 0,
                progressTotal = 15,
                planning = GoalPlanningInfo(
                    state = "partially_planned",
                    sharedPreplanPrepared = true,
                    plannedSubtaskCount = 1,
                    totalSubtaskCount = 15,
                ),
            ),
            now,
        )
        assertTrue(oneSaved.barText.contains("Planning 1/15"))
        assertEquals("1/15", oneSaved.details.progressText)
        assertTrue(oneSaved.tooltipText.contains("Planning: partially planned, 1/15 plans saved"))
    }

    @Test
    fun `absent planning leaves bar and tooltip byte-for-byte unchanged`() {
        val withoutPlanning = SkillBillStatusBarPresentation.map(active(), now)
        assertEquals(
            "Skill Bill · Implement · 2h 00m · 30m 00s · 4/9",
            withoutPlanning.barText,
        )
        assertEquals(
            "Skill Bill — active\nIssue: SKILL-148\nWorkflow: wfl-1\nStep: Implement" +
                "\nGoal elapsed: 2h 00m\nSubtask elapsed: 30m 00s\nProgress: 4/9" +
                "\nLast update: 2026-08-07 12:00:00Z\nworking",
            withoutPlanning.tooltipText,
        )
        assertNull(withoutPlanning.details.selectedSlotLabel)
        assertNull(withoutPlanning.details.selectedSlotText)
    }

    @Test
    fun `a non-goal family snapshot renders no planning segment or planning line`() {

        val mapped = SkillBillStatusBarPresentation.map(active(planning = null), now)
        assertFalse(mapped.barText.contains("Planning"))
        assertFalse(mapped.tooltipText.contains("Planning:"))
        assertFalse(mapped.accessibleDescription.contains("Planning:"))
        assertNull(mapped.details.selectedSlotLabel)
    }

    @Test
    fun `planning disappears once implementation starts and execution fills the slot`() {


        val prepared = StatusUiMapper.map(
            activeOutcome(planning = domainPlanning("prepared"), currentSubtaskId = null, completed = 0),
            now,
        )
        val executing = StatusUiMapper.map(
            activeOutcome(
                planning = domainPlanning("partially_planned"),
                currentSubtaskId = "2",
                completed = 0,
                currentPhaseExecution = CurrentPhaseExecution(
                    phaseId = "implement",
                    kind = "attempt",
                    count = 1,
                ),
            ),
            now,
        )
        val progressed = StatusUiMapper.map(
            activeOutcome(planning = domainPlanning("partially_planned"), currentSubtaskId = null, completed = 1),
            now,
        )
        for (state in listOf(prepared, progressed)) {
            val mapped = SkillBillStatusBarPresentation.map(state, now)
            assertFalse(mapped.barText, mapped.barText.contains("Planning"))
            assertFalse(mapped.tooltipText, mapped.tooltipText.contains("Planning:"))
            assertNull(mapped.details.selectedSlotLabel)
        }
        val executingMapped = SkillBillStatusBarPresentation.map(executing, now)
        assertFalse(executingMapped.tooltipText.contains("Planning:"))
        assertEquals("Current phase", executingMapped.details.selectedSlotLabel)
        assertEquals("Implement attempt 1", executingMapped.details.selectedSlotText)
        assertTrue(executingMapped.barText.contains("Implement attempt 1"))
    }

    @Test
    fun `stale mid-planning keeps the stale treatment and the full planning line`() {
        val mapped = SkillBillStatusBarPresentation.map(
            SkillBillStatusUiState.Stale(
                headline = "Skill Bill: Plan (stale)",
                detail = "cached",
                goalElapsed = Duration.ofMinutes(5),
                subtaskElapsed = null,
                progressCompleted = 0,
                progressTotal = 4,
                stepLabel = "Plan",
                planning = planning(sharedPreplanPrepared = false),
            ),
            now,
        )
        assertTrue(mapped.isStaleMarked)
        assertTrue(mapped.tooltipText.contains(SkillBillStatusBarPresentation.STALE_NOTE))
        assertEquals("Skill Bill · stale · Plan · Planning 1/4", mapped.barText)
        assertTrue(mapped.tooltipText.contains("Planning: partially planned, 1/4 plans saved"))
        assertEquals("Planning", mapped.details.selectedSlotLabel)
    }

    @Test
    fun `authoritative audit and review counts render honest labels without inventing totals`() {
        val audit = SkillBillStatusBarPresentation.map(
            active().copy(
                currentPhaseExecution = CurrentPhaseExecution(
                    phaseId = "audit",
                    kind = "semantic_loop",
                    count = 2,
                ),
            ),
            now,
        )
        assertTrue(audit.barText.contains("Audit loop 2"))
        assertTrue(audit.tooltipText.contains("Audit loop 2"))
        assertTrue(audit.accessibleDescription.contains("Audit loop 2"))
        assertEquals("Current phase", audit.details.selectedSlotLabel)
        assertEquals("Audit loop 2", audit.details.selectedSlotText)

        val review = SkillBillStatusBarPresentation.map(
            active().copy(
                currentPhaseExecution = CurrentPhaseExecution(
                    phaseId = "review",
                    kind = "pass",
                    count = 3,
                ),
            ),
            now,
        )
        assertTrue(review.barText.contains("Review pass 3"))
        assertEquals("Review pass 3", review.details.selectedSlotText)

        val attempt = SkillBillStatusBarPresentation.map(
            active().copy(
                currentPhaseExecution = CurrentPhaseExecution(
                    phaseId = "implement",
                    kind = "attempt",
                    count = 2,
                ),
            ),
            now,
        )
        assertEquals("Implement attempt 2", attempt.details.selectedSlotText)
        assertFalse(attempt.details.selectedSlotText!!.contains("/"))

        val gate = SkillBillStatusBarPresentation.map(
            active().copy(
                currentPhaseExecution = CurrentPhaseExecution(
                    phaseId = "validate",
                    kind = "gate_run",
                    count = 1,
                ),
            ),
            now,
        )
        assertEquals("Validate gate 1", gate.details.selectedSlotText)
        assertFalse(gate.details.selectedSlotText!!.contains("/"))
    }

    @Test
    fun `control characters in phase id do not reach tooltip accessibility or popup text`() {

        val mapped = SkillBillStatusBarPresentation.map(
            active().copy(
                currentPhaseExecution = CurrentPhaseExecution(
                    phaseId = "audit\u0000fix",
                    kind = "semantic_loop",
                    count = 2,
                ),
            ),
            now,
        )
        assertFalse(mapped.barText.contains('\u0000'))
        assertFalse(mapped.tooltipText.contains('\u0000'))
        assertFalse(mapped.accessibleDescription.contains('\u0000'))
        assertFalse(mapped.details.selectedSlotText!!.contains('\u0000'))
        assertEquals("Audit fix loop 2", mapped.details.selectedSlotText)
    }

    @Test
    fun `long execution labels stay within the bar budget while tooltip keeps the full wording`() {
        val mapped = SkillBillStatusBarPresentation.map(
            active(stepLabel = "Implement ".repeat(8)).copy(
                currentPhaseExecution = CurrentPhaseExecution(
                    phaseId = "write_history",
                    kind = "bounded_edge",
                    count = 2,
                    total = 3,
                ),
            ),
            now,
        )
        assertTrue(mapped.barText.length <= SkillBillStatusBarPresentation.BAR_TEXT_MAX_LENGTH)
        assertTrue(mapped.tooltipText.contains("Write History 2/3"))
        assertTrue(mapped.accessibleDescription.contains("Write History 2/3"))
    }

    @Test
    fun `execution progress renders the current position never one past the total`() {
        val inFlight = SkillBillStatusBarPresentation.map(
            active(progressCompleted = 1, progressTotal = 4),
            now,
        )
        assertTrue(inFlight.barText, inFlight.barText.contains("2/4"))
        assertEquals("2/4", inFlight.details.progressText)
        assertTrue(inFlight.tooltipText.contains("\nProgress: 2/4"))

        val zeroCompleted = SkillBillStatusBarPresentation.map(
            active(progressCompleted = 0, progressTotal = 4),
            now,
        )
        assertEquals("1/4", zeroCompleted.details.progressText)
        assertTrue(zeroCompleted.tooltipText.contains("\nProgress: 1/4"))

        val allComplete = SkillBillStatusBarPresentation.map(
            active(progressCompleted = 4, progressTotal = 4),
            now,
        )
        assertEquals("4/4", allComplete.details.progressText)
        assertFalse(allComplete.barText.contains("5/4"))

        val stale = SkillBillStatusBarPresentation.map(
            SkillBillStatusUiState.Stale(
                headline = "Skill Bill: Implement (stale)",
                detail = "cached",
                goalElapsed = Duration.ofMinutes(5),
                subtaskElapsed = null,
                progressCompleted = 1,
                progressTotal = 4,
                stepLabel = "Implement",
            ),
            now,
        )
        assertEquals("2/4", stale.details.progressText)
    }

    @Test
    fun `paused names the state keeps the tooltip anchors and never animates`() {
        val mapped = SkillBillStatusBarPresentation.map(
            SkillBillStatusUiState.Paused(
                headline = "Skill Bill: SKILL-165 · paused",
                detail = "paused by operator",
                goalElapsed = Duration.ofHours(2),
                subtaskElapsed = Duration.ofMinutes(30),
                progressCompleted = 1,
                progressTotal = 4,
                issueKey = "SKILL-165",
                workflowId = "wfl-1",
                stepLabel = "Implement",
                startedAt = started,
                subtaskStartedAt = subtaskStarted,
                lastUpdated = now,
            ),
            now,
        )
        assertEquals("Skill Bill · paused · Implement · 2h 00m · 2/4", mapped.barText)
        assertTrue(mapped.barText.length <= SkillBillStatusBarPresentation.BAR_TEXT_MAX_LENGTH)
        assertEquals("paused", mapped.details.lifecycleState)
        assertTrue(mapped.tooltipText.contains("\nStep: Implement"))
        assertTrue(mapped.tooltipText.contains("\nProgress: 2/4"))
        assertTrue(mapped.tooltipText.contains("Goal ran: 2h 00m"))
        assertEquals("ran", mapped.details.elapsedNoun)
        assertFalse(mapped.showActivityAnimation)
        assertFalse(mapped.isStaleMarked)
    }

    private fun planning(sharedPreplanPrepared: Boolean) = GoalPlanningInfo(
        state = "partially_planned",
        sharedPreplanPrepared = sharedPreplanPrepared,
        plannedSubtaskCount = 1,
        totalSubtaskCount = 4,
    )

    private fun domainPlanning(state: String) = GoalPlanningInfo(
        state = state,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 1,
        totalSubtaskCount = 4,
    )

    @Test
    fun `controls are populated for an eligible active state and empty otherwise`() {
        val eligible = SkillBillStatusBarPresentation.map(
            active().copy(workflowFamily = "feature-goal", issueKey = "SKILL-168"),
        )
        assertEquals(
            listOf(GoalControlKind.STOP, GoalControlKind.PAUSE),
            eligible.controls.map { it.kind },
        )

        val wrongFamily = SkillBillStatusBarPresentation.map(
            active().copy(workflowFamily = "feature-task-runtime", issueKey = "SKILL-168"),
        )
        assertTrue(wrongFamily.controls.isEmpty())
        assertTrue(SkillBillStatusBarPresentation.map(SkillBillStatusUiState.Idle()).controls.isEmpty())
    }

    @Test
    fun `adding controls leaves bar text tooltip and detail lines unchanged`() {


        val base = active().copy(issueKey = "SKILL-168", workflowFamily = "feature-task-runtime")
        val eligible = base.copy(workflowFamily = "feature-goal")
        val withoutControls = SkillBillStatusBarPresentation.map(base)
        val withControls = SkillBillStatusBarPresentation.map(eligible)

        assertEquals(withoutControls.barText, withControls.barText)
        assertEquals(withoutControls.tooltipText, withControls.tooltipText)
        assertEquals(withoutControls.accessibleName, withControls.accessibleName)
        assertEquals(withoutControls.accessibleDescription, withControls.accessibleDescription)
        assertEquals(withoutControls.details, withControls.details)
        assertTrue(withoutControls.controls.isEmpty())
        assertFalse(withControls.controls.isEmpty())
    }

    @Test
    fun `a carried current model leaves bar text tooltip and accessibility untouched`() {
        val withoutModel = SkillBillStatusBarPresentation.map(active())
        val withModel = SkillBillStatusBarPresentation.map(
            active().copy(currentModel = CurrentPhaseModel(model = "opus-5", effort = "high")),
        )

        assertEquals(withoutModel.barText, withModel.barText)
        assertEquals(withoutModel.tooltipText, withModel.tooltipText)
        assertEquals(withoutModel.accessibleName, withModel.accessibleName)
        assertEquals(withoutModel.accessibleDescription, withModel.accessibleDescription)

        assertEquals("opus-5 (effort: high)", withModel.details.modelText)
        assertTrue("no model text without a model", withoutModel.details.modelText == null)
    }

    @Test
    fun `the model row names its phase only for a goal and stays bounded for the popup`() {
        val model = CurrentPhaseModel(model = "opus-5", effort = "high", phaseId = "implement")


        val runtime = SkillBillStatusBarPresentation.map(active().copy(currentModel = model))


        val goal = SkillBillStatusBarPresentation.map(
            active().copy(workflowFamily = FEATURE_GOAL_WORKFLOW_FAMILY, currentModel = model),
        )

        assertEquals("opus-5 (effort: high)", runtime.details.modelText)
        assertEquals("opus-5 (implement, effort: high)", goal.details.modelText)



        val long = SkillBillStatusBarPresentation.map(
            active().copy(currentModel = CurrentPhaseModel(model = "m".repeat(119))),
        )
        assertTrue(
            "model row must stay bounded, was ${long.details.modelText?.length}",
            (long.details.modelText?.length ?: 0) <= MODEL_TEXT_MAX_LENGTH,
        )
    }

    @Test
    fun `a live active retained across a timed-out poll keeps active and says the poll timed out`() {
        val retained = StatusUiMapper.map(
            activeOutcome(planning = null, currentSubtaskId = "subtask-1", completed = 1)
                .copy(
                    summary = "Focused source assessment selected. Tests and build remain pending.",
                    currentStepId = "audit",
                    currentStepLabel = "Audit",
                    diagnostic = StatusDiagnostic(timedOut = true, reasonCode = POLL_FAILED_REASON_CODE),
                ),
            now,
        )
        val mapped = SkillBillStatusBarPresentation.map(retained, now)
        assertEquals("active", mapped.details.lifecycleState)
        assertEquals("Audit", mapped.details.stepLabel)
        assertFalse(mapped.isStaleMarked)
        assertNull(mapped.details.staleNote)
        assertFalse(mapped.tooltipText.contains(SkillBillStatusBarPresentation.STALE_NOTE))
        assertTrue(mapped.details.problemSummary!!.contains(StatusUiMapper.POLL_TIMEOUT_NOTE))
        assertTrue(mapped.details.problemSummary!!.contains("Focused source assessment selected"))
        assertTrue(mapped.accessibleDescription.contains("pending"))
        assertTrue(mapped.accessibleDescription.contains(StatusUiMapper.POLL_TIMEOUT_NOTE))
        assertTrue(mapped.tooltipText.contains(StatusUiMapper.POLL_TIMEOUT_NOTE))
    }

    @Test
    fun `a poll failure never replaces the reason a run is blocked failed or stale`() {
        val marker = StatusDiagnostic(timedOut = true, reasonCode = POLL_FAILED_REASON_CODE)
        val cases = listOf(
            "blocked" to SkillBillStatusOutcome.Blocked(
                observedAt = now,
                summary = "Waiting on operator decision",
                repositoryIdentity = "repo",
                issueKey = "SKILL-165",
                currentStepId = "audit",
                currentStepLabel = "Audit",
                startedAt = started,
                currentSubtaskId = "subtask-1",
                subtaskStartedAt = subtaskStarted,
                updatedAt = now,
                diagnostic = marker,
            ),
            "failed" to SkillBillStatusOutcome.Failed(
                observedAt = now,
                summary = "Validate gate did not converge",
                repositoryIdentity = "repo",
                issueKey = "SKILL-165",
                currentStepId = "validate",
                currentStepLabel = "Validate",
                startedAt = started,
                currentSubtaskId = "subtask-1",
                subtaskStartedAt = subtaskStarted,
                updatedAt = now,
                diagnostic = marker,
            ),
            "stale" to SkillBillStatusOutcome.Stale(
                observedAt = now,
                summary = "working",
                repositoryIdentity = "repo",
                issueKey = "SKILL-165",
                currentStepId = "audit",
                currentStepLabel = "Audit",
                progressCompleted = 1,
                progressTotal = 4,
                startedAt = started,
                currentSubtaskId = "subtask-1",
                subtaskStartedAt = subtaskStarted,
                updatedAt = now,
                fromCache = false,
                diagnostic = marker,
            ),
        )
        val expectedBase = mapOf(
            "blocked" to "Waiting on operator decision",
            "failed" to "Validate gate did not converge",
            "stale" to "Status is stale",
        )
        for ((label, outcome) in cases) {
            val mapped = SkillBillStatusBarPresentation.map(StatusUiMapper.map(outcome, now), now)
            val problem = mapped.details.problemSummary
            assertTrue(
                "$label must keep its own reason, was: $problem",
                problem != null && problem.contains(expectedBase.getValue(label)),
            )
            assertTrue(
                "$label must also surface the poll-failure note, was: $problem",
                problem != null && problem.contains(StatusUiMapper.POLL_TIMEOUT_NOTE),
            )
        }
    }

    @Test
    fun `contract freshness stale keeps the stale label instead of poll-failed copy`() {
        val mapped = SkillBillStatusBarPresentation.map(
            StatusUiMapper.map(
                SkillBillStatusOutcome.Stale(
                    observedAt = now,
                    summary = "working",
                    repositoryIdentity = "repo",
                    issueKey = "SKILL-165",
                    currentStepId = "audit",
                    currentStepLabel = "Audit",
                    progressCompleted = 1,
                    progressTotal = 4,
                    startedAt = started,
                    currentSubtaskId = "subtask-1",
                    subtaskStartedAt = subtaskStarted,
                    updatedAt = now,
                    fromCache = false,
                ),
                now,
            ),
            now,
        )
        assertEquals(SkillBillStatusBarPresentation.STALE_NOTE, mapped.details.staleNote)
        assertTrue(mapped.details.problemSummary.orEmpty().contains("Status is stale"))
        assertTrue(mapped.details.problemSummary.orEmpty().contains("working"))
    }

    private fun activeOutcome(
        planning: GoalPlanningInfo?,
        currentSubtaskId: String?,
        completed: Int,
        currentPhaseExecution: CurrentPhaseExecution? = null,
    ) = SkillBillStatusOutcome.Active(
        observedAt = now,
        summary = "working",
        repositoryIdentity = "repo",
        issueKey = "SKILL-165",
        workflowId = "wfl-1",
        workflowFamily = "feature-goal",
        currentStepId = "implement",
        currentStepLabel = "Implement",
        progressCompleted = completed,
        progressTotal = 4,
        startedAt = started,
        currentSubtaskId = currentSubtaskId,
        subtaskStartedAt = subtaskStarted,
        updatedAt = now,
        planning = planning,
        currentPhaseExecution = currentPhaseExecution,
    )

    private fun active(
        stepLabel: String = "Implement",
        headline: String = "Skill Bill: SKILL-148 · Implement",
        goalElapsed: Duration? = Duration.ofHours(2),
        subtaskElapsed: Duration? = Duration.ofMinutes(30),
        progressCompleted: Int? = 3,
        progressTotal: Int? = 9,
        startedAt: Instant? = started,
        subtaskStartedAt: Instant? = subtaskStarted,
        planning: GoalPlanningInfo? = null,
    ) = SkillBillStatusUiState.Active(
        headline = headline,
        detail = "working",
        goalElapsed = goalElapsed,
        subtaskElapsed = subtaskElapsed,
        progressCompleted = progressCompleted,
        progressTotal = progressTotal,
        issueKey = "SKILL-148",
        workflowId = "wfl-1",
        stepLabel = stepLabel,
        startedAt = startedAt,
        subtaskStartedAt = subtaskStartedAt,
        lastUpdated = now,
        planning = planning,
    )
}
