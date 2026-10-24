package dev.skillbill.tray.core.data.port

import kotlinx.coroutines.flow.Flow

/** Filesystem changes under one project's spec directory. A missing directory produces no events. */
interface SpecDirectoryChanges {
  fun changes(specsRoot: String): Flow<Unit>
}
