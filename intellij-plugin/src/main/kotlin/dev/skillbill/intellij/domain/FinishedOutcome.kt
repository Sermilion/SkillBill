package dev.skillbill.intellij.domain

enum class FinishedOutcome(val label: String) {
    COMPLETE("complete"),
    PASSED("passed"),
    FINDINGS("findings"),
    SKIPPED("skipped"),
    INCOMPLETE("incomplete"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun of(lifecycle: String, qaOutcome: String?): FinishedOutcome =
            if (lifecycle == "canceled") {
                CANCELLED
            } else {
                when (qaOutcome) {
                    "passed" -> PASSED
                    "findings" -> FINDINGS
                    "skipped" -> SKIPPED
                    "incomplete" -> INCOMPLETE
                    else -> COMPLETE
                }
            }
    }
}
