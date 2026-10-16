package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import dev.skillbill.tray.core.ui.abbreviateHomeDirectory
import dev.skillbill.tray.core.ui.repositoryTitle
import kotlinx.coroutines.launch

@Composable
internal fun RepositoryLocationLinks(item: WorkItem) {
  val navigation = LocalRepositoryNavigation.current
  val scope = rememberCoroutineScope()
  var error by remember(item.repositoryRoot, item.branch) { mutableStateOf<String?>(null) }
  val styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
  fun open(action: suspend () -> Outcome<Unit>) {
    scope.launch {
      error = null
      val result = action()
      if (result is Outcome.Failure) error = result.problem.summary
    }
  }
  Column {
    Text(
      buildAnnotatedString {
        withLink(LinkAnnotation.Clickable("repository", styles) { open { navigation.openRepository(item.repositoryRoot) } }) {
          append(item.repositoryTitle())
        }
        append(" · ")
        val branch = item.branch
        if (!branch.isNullOrBlank()) {
          withLink(LinkAnnotation.Clickable("branch", styles) { open { navigation.openRepository(item.repositoryRoot, branch) } }) {
            append(branch)
          }
        } else append("no branch")
        append(" · ")
        withLink(LinkAnnotation.Clickable("directory", styles) { open { navigation.openDirectory(item.repositoryRoot) } }) {
          append(abbreviateHomeDirectory(item.repositoryRoot))
        }
      },
      color = muted,
      fontFamily = mono,
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    error?.let { Text(it, color = orange, fontSize = 12.sp) }
  }
}
