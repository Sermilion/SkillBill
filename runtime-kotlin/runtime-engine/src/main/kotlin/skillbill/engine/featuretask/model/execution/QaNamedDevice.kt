package skillbill.engine.featuretask.model.execution

import skillbill.contracts.qa.QaDeviceLaunchTokens.AVD_PREFIX
import skillbill.contracts.qa.QaDeviceLaunchTokens.QA_DEVICE_FLAG
import skillbill.contracts.qa.QaDeviceLaunchTokens.SERIAL_PREFIX

public enum class QaNamedDeviceKind {
  SERIAL,
  AVD,
}

public data class QaNamedDevice(
  public val kind: QaNamedDeviceKind,
  public val value: String,
) {
  init {
    require(isValidValue(value)) { "QA device value is invalid." }
  }

  public val wireValue: String
    get() = (if (kind == QaNamedDeviceKind.SERIAL) SERIAL_PREFIX else AVD_PREFIX) + value

  public companion object {
    private const val MAX_VALUE_LENGTH = 256

    public fun of(
      kind: QaNamedDeviceKind,
      value: String,
    ): QaNamedDevice? = value.takeIf(::isValidValue)?.let { QaNamedDevice(kind, it) }

    public fun fromWireValue(raw: String): QaNamedDevice? =
      when {
        raw.startsWith(SERIAL_PREFIX) -> of(QaNamedDeviceKind.SERIAL, raw.removePrefix(SERIAL_PREFIX))
        raw.startsWith(AVD_PREFIX) -> of(QaNamedDeviceKind.AVD, raw.removePrefix(AVD_PREFIX))
        else -> null
      }

    private fun isValidValue(value: String): Boolean =
      value.isNotBlank() && value.length <= MAX_VALUE_LENGTH && !value.startsWith("-") &&
        value.none { it.isWhitespace() || Character.isISOControl(it) }
  }
}

public fun QaNamedDevice?.launchArguments(): List<String> =
  this?.let { listOf(QA_DEVICE_FLAG, it.wireValue) } ?: emptyList()
