import request from '@/utils/request'
import type { Result } from '@/types'

// 后端 ModelController（@RequestMapping("/api/models")）真实端点：
//   GET /api/models/providers → Result<{configuredProvider, activeProvider, providers:[{name,available,model}]}>
//   注意：只返回 model 名，绝不返回 api key。

/** 单个模型 provider 的可用性。 */
export interface ModelProviderInfo {
  name: string
  available: boolean
  model: string | null
}

/** GET /api/models/providers 的响应 data 结构。 */
export interface ModelProviders {
  configuredProvider: string
  activeProvider: string
  providers: ModelProviderInfo[]
}

/** 查询模型 provider 可用性与当前生效 provider。 */
export function listProviders(): Promise<Result<ModelProviders>> {
  return request.get('/models/providers')
}
