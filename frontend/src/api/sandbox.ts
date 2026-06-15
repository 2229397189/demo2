import request from '@/utils/request'
import type { Result, SandboxRequest, SandboxResponse } from '@/types'

export function executeCode(req: SandboxRequest): Promise<Result<SandboxResponse>> {
  return request.post('/sandbox/execute', req)
}
