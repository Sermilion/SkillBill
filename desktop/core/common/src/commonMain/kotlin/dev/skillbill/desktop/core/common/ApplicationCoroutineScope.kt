package dev.skillbill.desktop.core.common

import kotlinx.coroutines.CoroutineScope

/** The scope that lives as long as the process; database sessions are children of it. */
class ApplicationCoroutineScope(val scope: CoroutineScope)
