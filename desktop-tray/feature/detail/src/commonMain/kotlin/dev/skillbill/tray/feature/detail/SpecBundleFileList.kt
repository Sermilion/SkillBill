package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.domain.model.PlanRunReport
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.ui.LocalFeedbackHost
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation
import kotlinx.coroutines.launch

/** The runtime-supplied bundle files, or the parent and subtask spec paths when an older runtime sent none. Paths are never rewritten. */
internal fun specBundlePaths(report: PlanRunReport): List<String> =
  report.bundleFiles.ifEmpty { listOf(report.parentSpecPath) + report.subtasks.map { it.specPath } }
    .filter { it.isNotBlank() }
    .distinct()

@Composable
internal fun SpecBundleFileList(paths: List<String>) {
  if (paths.isEmpty()) return
  val navigation = LocalRepositoryNavigation.current
  val feedback = LocalFeedbackHost.current
  val scope = rememberCoroutineScope()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Text("Spec bundle", color = muted, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp)
    paths.forEach { path ->
      val name = path.substringAfterLast('/').substringAfterLast('\\')
      if (isAbsolutePath(path)) {
        Column(
          Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
              scope.launch {
                val result = navigation.openFile(path)
                if (result is Outcome.Failure) feedback?.showSnackbar(result.problem.summary)
              }
            }
            .semantics { contentDescription = "Open $name" },
          verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
          Text(name, color = green, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp, textDecoration = TextDecoration.Underline)
          Text(path, color = dim, fontFamily = mono, fontSize = 10.sp, lineHeight = 14.sp)
        }
      } else {
        SelectionContainer {
          Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, color = muted, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp)
            Text(path, color = dim, fontFamily = mono, fontSize = 10.sp, lineHeight = 14.sp)
          }
        }
      }
    }
  }
}

// shortcut: the JVM file API is unavailable in commonMain, so absolute means a leading separator or a Windows drive prefix; use expect/actual if more shapes appear.
private fun isAbsolutePath(path: String): Boolean =
  path.startsWith("/") || path.startsWith("\\\\") || (path.length >= 3 && path[0].isLetter() && path[1] == ':' && (path[2] == '\\' || path[2] == '/'))
