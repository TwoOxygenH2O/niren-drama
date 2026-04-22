import request from './request'

function buildShotSelectionPayload(shotIds?: Array<number | string>) {
  if (!shotIds || shotIds.length === 0) {
    return undefined
  }
  return { shotIds }
}

export const videoApi = {
  /** Generate images for all storyboard shots */
  generateImages: (projectId: number | string, shotIds?: Array<number | string>) =>
    request.post(`/videos/generate-images/${projectId}`, buildShotSelectionPayload(shotIds)),

  /** Generate dynamic clips for selected storyboard shots */
  generateDynamic: (projectId: number | string, shotIds?: Array<number | string>) =>
    request.post(`/videos/generate-dynamic/${projectId}`, buildShotSelectionPayload(shotIds)),

  /** Generate TTS audio for all storyboard shots */
  generateAudio: (projectId: number | string, shotIds?: Array<number | string>) =>
    request.post(`/videos/generate-audio/${projectId}`, buildShotSelectionPayload(shotIds)),

  /** Start video composition */
  compose: (projectId: number | string, shotIds?: Array<number | string>) =>
    request.post(`/videos/compose/${projectId}`, buildShotSelectionPayload(shotIds)),

  /** Get latest video composition status */
  getStatus: (projectId: number | string) =>
    request.get(`/videos/status/${projectId}`),

  /** Get storyboard list with status */
  getStoryboards: (projectId: number | string) =>
    request.get(`/videos/storyboards/${projectId}`),

  /** Get project composition overview */
  getOverview: (projectId: number | string) =>
    request.get(`/videos/overview/${projectId}`),

  /** Get download URL for the final video */
  getDownloadUrl: (projectId: number | string) =>
    `/api/videos/download/${projectId}`,
}
