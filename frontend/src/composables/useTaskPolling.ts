import { onUnmounted } from 'vue'
import { taskApi } from '@/api/task'

export type PolledTask = {
  id?: number | string
  status?: string
  message?: string
  progress?: number
  [key: string]: unknown
}

export type TaskPollingOptions = {
  intervalMs?: number
  maxAttempts?: number
  onSuccess?: (task: PolledTask) => void | Promise<void>
  onFailure?: (task: PolledTask) => void | Promise<void>
  onTimeout?: () => void | Promise<void>
}

export function useTaskPolling(defaults: TaskPollingOptions = {}) {
  let timer: ReturnType<typeof window.setTimeout> | null = null
  let attempts = 0
  let activeTaskId: number | string | null = null

  function stop() {
    if (timer !== null) {
      window.clearTimeout(timer)
      timer = null
    }
    activeTaskId = null
    attempts = 0
  }

  function start(taskId: number | string, options: TaskPollingOptions = {}) {
    stop()
    activeTaskId = taskId
    const intervalMs = options.intervalMs ?? defaults.intervalMs ?? 2000
    const maxAttempts = options.maxAttempts ?? defaults.maxAttempts ?? 150

    const poll = async () => {
      if (activeTaskId !== taskId) return
      attempts += 1
      if (attempts > maxAttempts) {
        stop()
        await (options.onTimeout ?? defaults.onTimeout)?.()
        return
      }

      try {
        const res = await taskApi.get(taskId)
        const task = ((res as any).data?.data || {}) as PolledTask
        if (task.status === 'SUCCESS') {
          stop()
          await (options.onSuccess ?? defaults.onSuccess)?.(task)
          return
        }
        if (task.status === 'FAILED') {
          stop()
          await (options.onFailure ?? defaults.onFailure)?.(task)
          return
        }
      } catch {
        // Keep polling through transient API errors; maxAttempts is the safety cap.
      }

      if (activeTaskId === taskId) {
        timer = window.setTimeout(poll, intervalMs)
      }
    }

    timer = window.setTimeout(poll, intervalMs)
  }

  onUnmounted(stop)

  return { start, stop }
}
