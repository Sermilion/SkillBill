package dev.skillbill.tray.core.ui

const val MAX_PENDING_FEEDBACK = 8

enum class FeedbackTone { CONFIRMATION, PROBLEM }

/**
 * Feedback the user must see. It lives in UI state until the screen acknowledges it by id, so it survives a screen that was
 * not attached when it was produced. Ids are monotonic within one ViewModel ([FeedbackIds]).
 */
data class Feedback(val id: Long, val tone: FeedbackTone, val message: String)

/** Hands out the monotonic ids of one ViewModel's feedback. */
class FeedbackIds {
  private var last = 0L

  fun next(): Long = ++last
}

/** Adds [feedback], keeping the newest [MAX_PENDING_FEEDBACK] so an unattended screen cannot accumulate without bound. */
fun List<Feedback>.withFeedback(feedback: Feedback): List<Feedback> = (this + feedback).takeLast(MAX_PENDING_FEEDBACK)

fun List<Feedback>.acknowledged(id: Long): List<Feedback> = filterNot { it.id == id }
