package skillbill.ports.process

import skillbill.ports.process.model.InstallerProcessRequest
import skillbill.ports.process.model.InstallerProcessResult

interface InstallerProcessPort {
  fun run(request: InstallerProcessRequest): InstallerProcessResult
}
