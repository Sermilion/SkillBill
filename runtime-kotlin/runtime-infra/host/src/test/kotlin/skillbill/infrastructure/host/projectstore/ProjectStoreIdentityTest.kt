package skillbill.infrastructure.host.projectstore

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectStoreIdentityTest {
  @Test
  fun `remote URLs of one repository match in every form git accepts and other repositories never match`() {
    val scp = "git@github.com:Sermilion/SkillBill.git"

    assertTrue(sameRemote(scp, "https://github.com/Sermilion/SkillBill.git"))
    assertTrue(sameRemote(scp, "https://GitHub.com/Sermilion/SkillBill/"))
    assertTrue(sameRemote(scp, "ssh://git@github.com:22/Sermilion/SkillBill"))
    assertFalse(sameRemote(scp, "git@github.com:fork/SkillBill.git"))
    assertFalse(sameRemote(scp, "git@gitlab.com:Sermilion/SkillBill.git"))
    assertTrue(sameRemote("/Users/me/skill-bill", "/Users/me/skill-bill"))
    assertFalse(sameRemote("/Users/me/skill-bill", "/Users/me/other/skill-bill"))
    assertFalse(sameRemote(scp, null))
  }
}
