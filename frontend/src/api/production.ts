import request from './request'

export type ProductionRepairPayload = {
  action: string
  shotIds?: Array<number | string>
  mode?: 'preview' | 'publish'
  workflowPreset?: 'ltx' | 'wan' | 'custom'
  retryPolicy?: Record<string, any>
  platformProfile?: 'douyin' | 'hongguo'
}

export const productionApi = {
  getWorkspace: (projectId: number | string) =>
    request.get(`/production/${projectId}/workspace`),

  repair: (projectId: number | string, data: ProductionRepairPayload) =>
    request.post(`/production/${projectId}/repair`, data),

  runQualityCheck: (projectId: number | string, data?: { shotIds?: Array<number | string> }) =>
    request.post(`/production/${projectId}/quality-check`, data || {}),

  createSnapshot: (projectId: number | string, data?: { shotIds?: Array<number | string> }) =>
    request.post(`/production/${projectId}/snapshots`, data || {}),

  restoreSnapshot: (projectId: number | string, snapshotId: number | string) =>
    request.post(`/production/${projectId}/snapshots/${snapshotId}/restore`),

  exportPackage: (projectId: number | string, data: { platformProfile: 'douyin' | 'hongguo' }) =>
    request.post(`/production/${projectId}/export-package`, data),

  upsertBible: (projectId: number | string, data: Record<string, any>) =>
    request.put(`/production/${projectId}/bible`, data),
}
