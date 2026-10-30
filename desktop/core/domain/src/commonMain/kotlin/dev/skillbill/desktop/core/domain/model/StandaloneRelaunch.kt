package dev.skillbill.desktop.core.domain.model

class StandaloneRelaunch(private val source: WorkItem, private val knownIds: Set<String>) {
  fun successor(items: List<WorkItem>): WorkItem? = items.singleOrNull { candidate ->
    source.standaloneExecution && candidate.standaloneExecution && candidate.id != source.id && candidate.id !in knownIds &&
      candidate.kind == source.kind && candidate.control.sessionId == source.control.sessionId &&
      candidate.control.repositoryIdentity == source.control.repositoryIdentity &&
      source.control.phaseId != null && candidate.control.phaseId == source.control.phaseId &&
      candidate.issueKey == source.issueKey && candidate.branch == source.branch
  }
}
