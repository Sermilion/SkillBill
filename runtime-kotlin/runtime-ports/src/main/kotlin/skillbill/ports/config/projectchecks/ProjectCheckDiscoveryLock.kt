package skillbill.ports.config.projectchecks

interface ProjectCheckDiscoveryLock {
  fun <T> withProjectOwnership(
    projectId: String,
    action: () -> T,
  ): T
}
