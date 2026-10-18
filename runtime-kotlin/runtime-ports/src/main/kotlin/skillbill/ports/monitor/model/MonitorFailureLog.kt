package skillbill.ports.monitor.model

sealed interface MonitorFailureLog {
  data class Available(val text: String) : MonitorFailureLog

  data class Unavailable(val reason: String) : MonitorFailureLog
}
