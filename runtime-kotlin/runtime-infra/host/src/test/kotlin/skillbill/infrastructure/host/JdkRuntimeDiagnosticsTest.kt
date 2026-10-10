package skillbill.infrastructure.host

import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdkRuntimeDiagnosticsTest {
  @Test
  fun `a warning names the caller and summarizes the error without attaching a throwable`() {
    val logger = Logger.getLogger(JdkRuntimeDiagnostics::class.java.name)
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
      }
    val previousUseParent = logger.useParentHandlers
    logger.addHandler(handler)
    logger.useParentHandlers = false
    try {
      JdkRuntimeDiagnostics().warning("x", IllegalStateException("boom"))
      val record = records.single()
      assertEquals(JdkRuntimeDiagnosticsTest::class.java.name, record.sourceClassName)
      assertNull(record.thrown)
      assertTrue(record.message.contains("IllegalStateException: boom"), record.message)
    } finally {
      logger.removeHandler(handler)
      logger.useParentHandlers = previousUseParent
    }
  }
}
