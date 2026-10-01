package skillbill.ports.process

import skillbill.ports.process.model.InstallerProcessRequest
import skillbill.ports.process.model.InstallerProcessResult

interface InstallerProcessPort {
  fun run(request: InstallerProcessRequest): InstallerProcessResult
}

const val DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS: Long = 600L
