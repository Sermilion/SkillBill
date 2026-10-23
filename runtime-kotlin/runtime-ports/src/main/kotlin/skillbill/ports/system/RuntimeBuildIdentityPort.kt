package skillbill.ports.system

interface RuntimeBuildIdentityPort {
  fun readIdentity(): String?
}
