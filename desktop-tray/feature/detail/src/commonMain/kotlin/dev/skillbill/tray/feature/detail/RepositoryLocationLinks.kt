package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      LocationLabel(
        item.repositoryTitle(),
        Modifier.weight(1f, fill = false),
        LinkAnnotation.Clickable("repository", styles) { open { navigation.openRepository(item.repositoryRoot) } },
      )
      LocationLabel(" · ")
      val branch = item.branch
      LocationLabel(
        branch?.takeIf { it.isNotBlank() } ?: "no branch",
        Modifier.weight(1f, fill = false),
        branch?.takeIf { it.isNotBlank() }?.let {
          LinkAnnotation.Clickable("branch", styles) { open { navigation.openRepository(item.repositoryRoot, branch) } }
        },
      )
      LocationLabel(" · ")
      LocationLabel(
        abbreviateHomeDirectory(item.repositoryRoot),
        Modifier.weight(1f, fill = false),
        LinkAnnotation.Clickable("directory", styles) { open { navigation.openDirectory(item.repositoryRoot) } },
      )
    }
    error?.let { Text(it, color = orange, fontSize = 12.sp) }
  }
}

@Composable
private fun LocationLabel(label: String, modifier: Modifier = Modifier, link: LinkAnnotation.Clickable? = null) {
  Text(
    buildAnnotatedString {
      if (link == null) append(label) else withLink(link) { append(label) }
    },
    modifier = modifier,
    color = muted,
    fontFamily = mono,
    fontSize = 12.sp,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}
