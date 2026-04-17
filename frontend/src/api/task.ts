import request from './request'

export const taskApi = {
  get: (id: number | string) =>
    request.get(`/tasks/${id}`),

  listByProject: (projectId: number | string) =>
    request.get(`/tasks/project/${projectId}`),

  myTasks: () =>
    request.get('/tasks/my'),

  voices: () =>
    request.get('/tasks/voices'),
}
