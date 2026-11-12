package skillbill.engine.featuretask.qa.lease

import java.security.MessageDigest

public object QaDeviceFingerprint {
  public fun of(serial: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(serial.trim().toByteArray())
      .joinToString("") { "%02x".format(it) }
}
