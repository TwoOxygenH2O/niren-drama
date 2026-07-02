import request from './request'

export interface DashboardMetric {
  id: string
  title: string
  status: string
  value: string
  total?: string
  description: string
  progress: number
  footerLabel: string
  footerValue: string
  tone?: 'default' | 'violet' | 'review'
}

export interface DashboardRecentRow {
  scene: string
  project: string
  status: string
  statusTone: 'cyan' | 'violet' | 'green' | 'red'
  progress: string
  detail: string
}

export interface DashboardOverview {
  projectSummary: Record<string, number>
  taskSummary: Record<string, number>
  latestProject: {
    id: number | string
    name: string
    status: string
    updatedAt: string
  } | null
  productionSummary: Record<string, number | boolean>
  productionStages: Array<Record<string, unknown>>
  healthSummary: {
    ok: number
    total: number
    items: Record<string, unknown>
  }
  metrics: DashboardMetric[]
  recentRows: DashboardRecentRow[]
  generatedAt: string
}

export const dashboardApi = {
  getOverview: () =>
    request.get<{ data: DashboardOverview }>('/dashboard/overview'),
}
