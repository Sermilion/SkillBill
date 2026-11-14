package dev.skillbill.desktop.core.domain.model

fun ChatNewTaskContext?.hasSameConversationIdentity(other: ChatNewTaskContext?): Boolean = when {
  this == null -> other == null
  other == null -> false
  else -> workingDirectory == other.workingDirectory &&
    selectedRepositoryRoot == other.selectedRepositoryRoot && baseBranch == other.baseBranch
}
