import { onScopeDispose } from 'vue'
import { taskApi } from '@/api/task'

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export type PolledTask = {
  id?: number | string
  status?: TaskStatus | string
  message?: string
  progress?: number
  [key: string]: unknown
}

export type TaskPollingOptions = {
  intervalMs?: number
  maxDurationMs?: number
  onSuccess?: (task: PolledTask) => void | Promise<void>
  onFailure?: (task: PolledTask) => void | Promise<void>
  onProgress?: (task: PolledTask) => void | Promise<void>
  onTimeout?: () => void | Promise<void>
}

export function useTaskPolling(defaults: TaskPollingOptions = {}) {
  type PollState = {
    taskId: number | string
    timer: ReturnType<typeof window.setTimeout> | null
    startedAt: number
    failureDelayMs: number
    stopped: boolean
    options: TaskPollingOptions
  }

  const active = new Map<string, PollState>()

  function taskKey(taskId: number | string) {
    return String(taskId)
  }

  function stop(taskId: number | string) {
    const key = taskKey(taskId)
    const state = active.get(key)
    if (!state) {
      return
    }
    state.stopped = true
    if (state.timer !== null) {
      window.clearTimeout(state.timer)
      state.timer = null
    }
    active.delete(key)
  }

  function stopAll() {
    Array.from(active.keys()).forEach((key) => stop(key))
  }

  function start(taskId: number | string, options: TaskPollingOptions = {}) {
    stop(taskId)
    const intervalMs = options.intervalMs ?? defaults.intervalMs ?? 2000
    const maxDurationMs = options.maxDurationMs ?? defaults.maxDurationMs ?? 600000
    const key = taskKey(taskId)
    const state: PollState = {
      taskId,
      timer: null,
      startedAt: Date.now(),
      failureDelayMs: intervalMs,
      stopped: false,
      options,
    }
    active.set(key, state)

    const resolveHandler = <K extends keyof TaskPollingOptions>(handler: K) =>
      state.options[handler] ?? defaults[handler]

    const poll = async () => {
      if (state.stopped || active.get(key) !== state) return
      if (Date.now() - state.startedAt > maxDurationMs) {
        stop(taskId)
        await resolveHandler('onTimeout')?.()
        return
      }

      try {
        const res = await taskApi.get(taskId)
        const task = ((res as any).data?.data || {}) as PolledTask
        state.failureDelayMs = intervalMs
        if (task.status === 'SUCCESS') {
          stop(taskId)
          await resolveHandler('onSuccess')?.(task)
          return
        }
        if (task.status === 'FAILED') {
          stop(taskId)
          await resolveHandler('onFailure')?.(task)
          return
        }
        await resolveHandler('onProgress')?.(task)
      } catch {
        state.failureDelayMs = Math.min(Math.round(state.failureDelayMs * 1.5), intervalMs * 4)
      }

      if (!state.stopped && active.get(key) === state) {
        state.timer = window.setTimeout(poll, state.failureDelayMs)
      }
    }

    state.timer = window.setTimeout(poll, intervalMs)
  }

  onScopeDispose(stopAll)

  return { start, stop, stopAll }
}
