package skillbill.ports.monitor.model

private val URL_USERINFO = Regex("(?i)(https?://)([^\\s/@]+)@")
private const val MAX_MONITOR_DETAIL = 1000

fun redactedMonitorDetail(text: String): String =
  text
    .replace(URL_USERINFO, "$1<redacted>@")
    .take(MAX_MONITOR_DETAIL)
