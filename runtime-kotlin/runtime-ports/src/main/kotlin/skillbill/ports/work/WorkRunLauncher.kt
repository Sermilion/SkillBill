package skillbill.ports.work

import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult

interface WorkRunLauncher {
  fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult

  fun stop(request: WorkRunStopRequest): WorkRunStopResult
}
