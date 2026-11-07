package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.qa.QaDeviceTransport

internal data class ParsedAdbDevice(val serial: String, val adbState: String, val model: String?)

internal object AndroidQaDeviceParsing {
  fun devices(output: String): List<ParsedAdbDevice> =
    output.lineSequence().map(String::trim)
      .filter { it.isNotEmpty() && !it.startsWith("List of devices attached") && !it.startsWith("*") && !it.startsWith("daemon") }
      .mapNotNull { line ->
        val fields = line.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (fields.size < 2) {
          null
        } else {
          val model = fields.drop(2).firstNotNullOfOrNull { field ->
            field.takeIf { it.startsWith("model:") }?.removePrefix("model:")?.takeIf(String::isNotBlank)
          }
          ParsedAdbDevice(fields[0], fields[1], model)
        }
      }.toList()

  fun avds(output: String): List<String> = output.lineSequence().map(String::trim)
    .filter { line ->
      line.isNotEmpty() && !line.startsWith("INFO") && !line.startsWith("WARNING") && !line.startsWith("ERROR") && '|' !in line
    }.distinct().toList()

  fun transport(serial: String): QaDeviceTransport = when {
    serial.matches(Regex("emulator-\\d+")) -> QaDeviceTransport.Emulator
    ':' in serial || "._adb-tls-connect._tcp" in serial -> QaDeviceTransport.Wifi
    else -> QaDeviceTransport.Usb
  }
}
