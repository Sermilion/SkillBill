package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ActivityResult
import kotlinx.coroutines.flow.Flow

interface WorkActivityRepository {
  fun observe(goalWorkflowId: String, running: Boolean): Flow<ActivityResult>
}
