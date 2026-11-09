package skillbill.infrastructure.contracts.locator

object InstallPlanSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/install-plan-schema.yaml"

  const val CLASSPATH_RESOURCE: String =
    "skillbill/infrastructure/contracts/install-plan-schema.yaml"

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/install-plan-schema.yaml"
}

object PreviousInstallPlanSchemaPaths {
  const val REPO_RELATIVE_PATH: String = "orchestration/contracts/install-plan-v0.3-schema.yaml"
  const val CLASSPATH_RESOURCE: String = "skillbill/infrastructure/contracts/install-plan-v0.3-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String = "https://skill-bill.dev/contracts/install-plan-v0.3-schema.yaml"
}

object NativeAgentLinkInventorySchemaPaths {
  const val REPO_RELATIVE_PATH = "orchestration/contracts/native-agent-link-inventory-schema.yaml"
  const val CLASSPATH_RESOURCE = "/skillbill/infrastructure/contracts/native-agent-link-inventory-schema.yaml"
  const val EXPECTED_SCHEMA_ID = "https://skill-bill.dev/contracts/native-agent-link-inventory-schema.yaml"
}

object AgentAddonSchemaPaths {
  const val REPOSITORY_PATH: String = "orchestration/contracts/agent-addon-schema.yaml"
  const val CLASSPATH_RESOURCE: String = "skillbill/infrastructure/contracts/agent-addon-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String = "https://skill-bill.dev/contracts/agent-addon-schema.yaml"
}
