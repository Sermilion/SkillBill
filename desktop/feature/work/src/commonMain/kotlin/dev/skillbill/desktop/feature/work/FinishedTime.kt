package dev.skillbill.desktop.feature.work

import kotlin.time.Instant

/** "HH:mm" on [now]'s local day, "Yesterday HH:mm" on the day before, else "MMM d HH:mm", in the system time zone. */
internal expect fun finishedTimeText(finishedAt: Instant, now: Instant): String
