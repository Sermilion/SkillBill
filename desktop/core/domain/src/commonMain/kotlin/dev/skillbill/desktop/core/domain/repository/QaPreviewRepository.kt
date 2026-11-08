package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.QaPreviewRequest
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import kotlinx.coroutines.flow.Flow

interface QaPreviewRepository {
  fun observe(request: QaPreviewRequest): Flow<QaPreviewState>
}
