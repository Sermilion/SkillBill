package dev.skillbill.desktop.core.domain.model

/** What moving a removed worktree's agent sessions did: Claude files moved, Codex sessions rewritten, and sessions that failed. */
data class SessionMoveResult(val claudeMoved: Int, val codexMoved: Int, val failed: Int)
