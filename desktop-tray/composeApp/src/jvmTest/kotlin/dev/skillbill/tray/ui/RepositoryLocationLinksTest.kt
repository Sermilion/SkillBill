package dev.skillbill.tray.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import dev.skillbill.tray.core.ui.FeedbackHost
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.RepositoryNavigation
import dev.skillbill.tray.feature.detail.DetailContent
import dev.skillbill.tray.feature.detail.DetailLoad
import dev.skillbill.tray.feature.detail.DetailUiState
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class RepositoryLocationLinksTest {
  @Test
  fun `header links open the displayed repository branch and checkout and report an opener failure`() = runComposeUiTest {
    val calls = mutableListOf<String>()
    val navigation = object : RepositoryNavigation {
      override suspend fun openRepository(root: String, branch: String?): Outcome<Unit> {
        calls += "web:$root:$branch"
        return Outcome.Value(Unit)
      }

      override suspend fun openDirectory(root: String): Outcome<Unit> {
        calls += "directory:$root"
        return Outcome.Failure(Problem(ProblemCode.UNKNOWN, "The checkout no longer exists."))
      }

      override suspend fun openFile(path: String): Outcome<Unit> = Outcome.Value(Unit)
    }
    val item = workItem("links").copy(repositoryName = "ZeroAccount", repositoryRoot = "/tmp/a repo", branch = "fix/links")
    setContent {
      CompositionLocalProvider(LocalRepositoryNavigation provides navigation) {
        DesignSystemTheme {
          FeedbackHost {
            DetailContent(DetailUiState(item.identity, DetailLoad.Loaded(detail(item))), fixtureInstant, {})
          }
        }
      }
    }
    for (label in listOf("ZeroAccount", "fix/links", "/tmp/a repo")) {
      val node = onNodeWithText(label)
      val layouts = mutableListOf<TextLayoutResult>()
      node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      node.performTouchInput { click(layouts.single().getBoundingBox(0).center) }
      waitForIdle()
    }
    assertEquals(listOf("web:/tmp/a repo:null", "web:/tmp/a repo:fix/links", "directory:/tmp/a repo"), calls)
    onNodeWithText("The checkout no longer exists.").assertExists()
  }
}
