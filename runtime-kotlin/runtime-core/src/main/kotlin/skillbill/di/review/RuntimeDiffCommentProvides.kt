package skillbill.di.review

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.workflow.filesystem.FileSystemDiffCommentWorktreeFiles
import skillbill.ports.review.diffcomment.DiffCommentWorktreeFiles

internal interface RuntimeDiffCommentProvides {
  @Provides
  fun diffCommentWorktreeFiles(adapter: FileSystemDiffCommentWorktreeFiles): DiffCommentWorktreeFiles = adapter
}
