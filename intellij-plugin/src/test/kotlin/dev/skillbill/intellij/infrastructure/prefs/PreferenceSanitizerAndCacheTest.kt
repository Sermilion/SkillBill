package dev.skillbill.intellij.infrastructure.prefs

import dev.skillbill.intellij.domain.CachedDisplaySnapshot
import dev.skillbill.intellij.domain.LastKnownDisplayCache
import dev.skillbill.intellij.domain.boundedDisplaySummary
import dev.skillbill.intellij.domain.toCacheSnapshotOrNull
import dev.skillbill.intellij.domain.toStaleOutcome
import dev.skillbill.intellij.presentation.SkillBillStatusBarPresentation
import dev.skillbill.intellij.presentation.StatusUiMapper
import dev.skillbill.intellij.fakes.FakePreferenceCache
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.skillbill.intellij.domain.SkillBillStatusOutcome.Paused
import dev.skillbill.intellij.domain.SkillBillStatusOutcome.Stale

class PreferenceSanitizerAndCacheTest {
    @Test
    fun `cache fallback produces stale UI outcome only`() {
        val cache = LastKnownDisplayCache(
            display = CachedDisplaySnapshot(
                summary = "last known implement",
                issueKey = "SKILL-148",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                startedAt = Instant.parse("2026-08-06T08:00:00Z"),
            ),
            observedAt = Instant.parse("2026-08-06T09:00:00Z"),
        )
        val outcome = cache.toStaleOutcome()
        assertTrue(outcome.fromCache)
        assertTrue(outcome is Stale)
    }

    @Test
    fun `a paused outcome round-trips through the cache and re-emerges as stale`() {
        val paused = Paused(
            observedAt = Instant.parse("2026-08-06T09:00:00Z"),
            summary = "paused by operator",
            repositoryIdentity = "repo",
            issueKey = "SKILL-165",
            workflowId = "wfl-1",
            workflowFamily = "feature-goal",
            currentStepId = "implement",
            currentStepLabel = "Implement",
            progressCompleted = 1,
            progressTotal = 4,
            startedAt = Instant.parse("2026-08-06T08:00:00Z"),
            currentSubtaskId = "2",
            subtaskStartedAt = Instant.parse("2026-08-06T08:30:00Z"),
            updatedAt = Instant.parse("2026-08-06T08:59:00Z"),
        )
        val cached = paused.toCacheSnapshotOrNull()
        assertNotNull(cached)
        val stale = cached!!.toStaleOutcome()
        assertTrue(stale.fromCache)
        assertEquals("SKILL-165", stale.issueKey)
        assertEquals(1, stale.progressCompleted)
        assertEquals(4, stale.progressTotal)
    }

    @Test
    fun `bounded criterion context survives cache restoration as a stale observation`() {
        val gap = "source still misses the required behavior ".repeat(12)
        val summary = "Acceptance criteria: Criterion ac-0001 (S3-004); 3 failures used; " +
            "0 failures remaining; queue position 1 of 1; Criterion exhausted; source repair cannot advance; " +
            "Final full source audit remains required after focused work; " +
            "Source acceptance is separate from later tests, build, and project-quality checks. Latest gap: $gap"
        val cache = LastKnownDisplayCache(
            display = CachedDisplaySnapshot(summary = boundedDisplaySummary(summary)),
            observedAt = Instant.parse("2026-08-06T09:00:00Z"),
        )

        val persisted = SkillBillProjectDisplayCache()
        persisted.write(cache)
        val reloaded = SkillBillProjectDisplayCache()
        reloaded.loadState(persisted.getState().copy())
        val restored = reloaded.read()!!.toStaleOutcome()

        assertTrue(restored.fromCache)
        assertTrue(restored.summary.contains("3 failures used"))
        assertTrue(restored.summary.contains("0 failures remaining"))
        assertTrue(restored.summary.contains("Final full source audit"))
        assertTrue(restored.summary.contains("Summary abbreviated"))
        assertTrue(restored.summary.length <= CachedDisplaySnapshot.MAX_SUMMARY_CHARS)
        val presentation = SkillBillStatusBarPresentation.map(StatusUiMapper.map(restored, cache.observedAt))
        assertTrue(presentation.isStaleMarked)
        assertTrue(presentation.details.staleNote!!.contains("not live"))
    }

    @Test
    fun `preference sanitizer rejects tokens prompts stderr and unbounded blobs`() {
        assertNull(PreferenceSanitizer.sanitizeExecutablePath("token=abc123"))
        assertNull(PreferenceSanitizer.sanitizeExecutablePath("Bearer secret-value"))
        assertTrue(PreferenceSanitizer.looksLikeSecret("api_key=xyz"))
        assertTrue(PreferenceSanitizer.looksLikeSecret("stderr dump"))
        assertTrue(PreferenceSanitizer.looksLikeUnboundedBlob("x".repeat(5_000)))
        assertNull(
            PreferenceSanitizer.sanitizeCache(
                LastKnownDisplayCache(
                    display = CachedDisplaySnapshot(summary = "token=leak"),
                    observedAt = Instant.parse("2026-08-06T09:00:00Z"),
                ),
            ),
        )


        val cleared = PreferenceSanitizer.sanitizeCacheState(
            SkillBillProjectDisplayCache.State(
                summary = "phase artifact\n".repeat(40),
                observedAt = "2026-08-06T09:00:00Z",
            ),
        )
        assertNull(cleared.summary)
        assertNull(cleared.observedAt)
    }

    @Test
    fun `sanitizeCache omits repositoryIdentity that embeds absolute paths`() {
        val withHomePath = PreferenceSanitizer.sanitizeCache(
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = "cached implement",
                    repositoryIdentity = "repo-root-realpath-v1:/home/user/StudioProjects/skill-bill",
                ),
                observedAt = Instant.parse("2026-08-06T09:00:00Z"),
            ),
        )
        assertNotNull(withHomePath)
        assertNull(withHomePath!!.display.repositoryIdentity)

        val withFixturePath = PreferenceSanitizer.sanitizeCache(
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = "cached implement",
                    repositoryIdentity = "repo-root-realpath-v1:/repo",
                ),
                observedAt = Instant.parse("2026-08-06T09:00:00Z"),
            ),
        )
        assertNotNull(withFixturePath)
        assertNull(withFixturePath!!.display.repositoryIdentity)

        val opaque = PreferenceSanitizer.sanitizeCache(
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(
                    summary = "cached implement",
                    repositoryIdentity = "opaque-repo-id",
                ),
                observedAt = Instant.parse("2026-08-06T09:00:00Z"),
            ),
        )
        assertEquals("opaque-repo-id", opaque!!.display.repositoryIdentity)
    }

    @Test
    fun `sanitizeCacheState omits absolute-path repositoryIdentity on load`() {
        val state = PreferenceSanitizer.sanitizeCacheState(
            SkillBillProjectDisplayCache.State(
                summary = "cached implement",
                repositoryIdentity = "repo-root-realpath-v1:/Users/me/proj",
                observedAt = "2026-08-06T09:00:00Z",
            ),
        )
        assertEquals("cached implement", state.summary)
        assertNull(state.repositoryIdentity)
    }

    @Test
    fun `CLI executable absolute path remains allowed as settings exception`() {
        val path = "/home/user/bin/skill-bill"
        assertEquals(path, PreferenceSanitizer.sanitizeExecutablePath(path))
    }

    @Test
    fun `fake preference port rejects forbidden preference writes`() {
        val prefs = FakePreferenceCache()
        prefs.setCliExecutableOverride("token=abc")
        prefs.setLastKnownDisplayCache(
            LastKnownDisplayCache(
                display = CachedDisplaySnapshot(summary = "stderr dump"),
                observedAt = Instant.parse("2026-08-06T09:00:00Z"),
            ),
        )
        assertNull(prefs.getCliExecutableOverride())
        assertNull(prefs.getLastKnownDisplayCache())
        assertTrue(prefs.rejectedWrites.isNotEmpty())
    }
}
