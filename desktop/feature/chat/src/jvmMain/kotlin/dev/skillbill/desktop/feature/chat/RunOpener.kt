package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailResolution
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Opens a run's detail page from chat by its work item id. */
interface RunOpener {
  /** Opens run [id] and returns null, or returns why it could not be opened. */
  suspend fun open(id: String): String?
}

@Inject
@SingleIn(WindowScope::class)
@ContributesBinding(WindowScope::class)
class NavigatingRunOpener(private val details: WorkDetailRepository, private val navigator: AppNavigator) : RunOpener {
  override suspend fun open(id: String): String? =
    when (val resolution = details.resolve(id)) {
      is WorkDetailResolution.Resolved -> {
        navigator.navigate(DetailRoute(resolution.item))
        null
      }
      WorkDetailResolution.Missing -> RUN_MISSING_TEXT
      is WorkDetailResolution.Failed -> readProblemText(resolution.problem)
    }
}

internal object UnavailableRunOpener : RunOpener {
  override suspend fun open(id: String): String = RUN_UNAVAILABLE_TEXT
}

private const val RUN_MISSING_TEXT = "This run is no longer in the database."
private const val RUN_UNAVAILABLE_TEXT = "Runs cannot be opened from this chat."
