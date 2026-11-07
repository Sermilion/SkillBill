package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.QaPreviewRequest
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import dev.skillbill.desktop.core.domain.repository.QaPreviewRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class QaPreviewRepositoryImpl(diagnostics: Diagnostics) : QaPreviewRepository {
  private val reader = QaPreviewReader(diagnostics)

  override fun observe(request: QaPreviewRequest): Flow<QaPreviewState> = reader.observe(request)
}
