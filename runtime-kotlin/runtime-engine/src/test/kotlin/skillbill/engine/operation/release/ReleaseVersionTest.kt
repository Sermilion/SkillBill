package skillbill.engine.operation.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseVersionTest {
  @Test
  fun `each bump advances its own component and resets the lower ones`() {
    assertEquals("v1.2.4", nextReleaseVersion("v1.2.3", ReleaseBump.PATCH))
    assertEquals("v1.3.0", nextReleaseVersion("v1.2.3", ReleaseBump.MINOR))
    assertEquals("v2.0.0", nextReleaseVersion("v1.2.3", ReleaseBump.MAJOR))
    assertEquals("v0.1.0", nextReleaseVersion(null, ReleaseBump.MINOR))
  }

  @Test
  fun `a missing or unknown bump does not parse`() {
    listOf(null, "huge").forEach { raw -> assertNull(ReleaseBump.parse(raw), "bump=$raw") }
  }
}
