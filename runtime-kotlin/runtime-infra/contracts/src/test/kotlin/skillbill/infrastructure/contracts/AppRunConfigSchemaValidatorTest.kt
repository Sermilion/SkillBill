package skillbill.infrastructure.contracts

import skillbill.error.apprun.AppRunFailureCode
import skillbill.error.core.SkillBillRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppRunConfigSchemaValidatorTest {
  private val validator = AppRunConfigSchemaValidator()

  @Test
  fun `unknown fields and invalid module values fail canonical validation`() {
    listOf(
      "contract_version: '0.1'\nflavor: demo\n",
      "contract_version: '0.1'\nmodule: 'bad module'\n",
    ).forEach { document ->
      assertEquals(
        AppRunFailureCode.INVALID_APP_RUN_CONFIG,
        assertFailsWith<SkillBillRuntimeException> { validator.validate(document, "fixture") }.code,
      )
    }
  }
}
