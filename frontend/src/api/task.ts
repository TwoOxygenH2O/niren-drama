import request from './request'

const TASK_POLL_TIMEOUT = 12 * 60 * 60 * 1000

export const taskApi = {
  get: (id: number | string) =>
    request.get(`/tasks/${id}`, { timeout: TASK_POLL_TIMEOUT }),

  listByProject: (projectId: number | string) =>
    request.get(`/tasks/project/${projectId}`),

  myTasks: () =>
    request.get('/tasks/my'),

  voices: () =>
    request.get('/tasks/voices'),
}
