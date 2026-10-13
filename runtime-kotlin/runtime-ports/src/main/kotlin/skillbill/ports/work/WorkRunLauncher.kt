package skillbill.ports.work

import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult

interface WorkRunLauncher {
  fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult
}
