package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.domain.model.RuntimeBuildProblem

/** Decides whether the installed runtime is this desktop's build, before any database is opened through it. */
fun interface RuntimeBuildGate {
  /** Reads both build records afresh: null when they are the same build, else why the installed runtime is refused. */
  fun check(): RuntimeBuildProblem?
}
