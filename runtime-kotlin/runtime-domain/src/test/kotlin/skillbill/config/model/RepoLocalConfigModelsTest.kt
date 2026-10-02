package skillbill.config.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepoLocalConfigModelsTest {
  @Test
  fun `resolution precedence prefers explicit over config over builtin default`() {
    assertEquals("explicit", RepoLocalConfigResolution.resolve("explicit", "config", "builtin"))
    assertEquals("config", RepoLocalConfigResolution.resolve(null, "config", "builtin"))
    assertEquals("builtin", RepoLocalConfigResolution.resolve(null, null, "builtin"))
  }

  @Test
  fun `parseSpecType accepts known values case-insensitively and trims`() {
    assertEquals(SpecType.LOCAL, parseSpecType("local"))
    assertEquals(SpecType.LINEAR, parseSpecType("  LINEAR  "))
  }

  @Test
  fun `parseSpecType rejects unknown or absent values`() {
    assertNull(parseSpecType("remote"))
    assertNull(parseSpecType(null))
  }

  @Test
  fun `builtin defaults match the known-key registry`() {
    val defaults = RepoLocalConfig.defaults()
    assertEquals(SpecType.LOCAL.id, RepoLocalConfigKey.SPEC_TYPE.builtinDefault)
    assertEquals(defaults.specType.id, RepoLocalConfigKey.SPEC_TYPE.builtinDefault)
  }
}
