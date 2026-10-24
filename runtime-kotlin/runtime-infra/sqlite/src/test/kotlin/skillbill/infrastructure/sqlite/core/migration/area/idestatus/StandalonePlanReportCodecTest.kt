package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import kotlin.test.Test
import kotlin.test.assertEquals

class StandalonePlanReportCodecTest {
  @Test
  fun `a report stored without bundle files decodes with an empty list`() {
    val stored =
      """{"feature_name":"Move state","parent_spec_path":"/s/spec.md","subtasks":""" +
        """[{"id":1,"name":"One","spec_path":"/s/01.md","depends_on":[]}]}"""

    val report = decodeStandalonePlanReport(stored) { error(it) }

    assertEquals(emptyList(), report.bundleFiles)
    assertEquals("/s/01.md", report.subtasks.single().specPath)
  }
}
