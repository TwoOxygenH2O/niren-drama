import request from './request'

const VIDEO_STATUS_TIMEOUT = 60 * 60 * 1000

export type ComposeOptions = {
  narrationEnabled?: boolean
  narrationVolume?: number
  dialoguePriority?: boolean
}

export type DynamicOptions = {
  forceDynamicByDefault?: boolean
}

function buildShotSelectionPayload(
  shotIds?: Array<number | string>,
  options?: Record<string, any>,
) {
  if (!shotIds || shotIds.length === 0) {
    return options && Object.keys(options).length > 0 ? options : undefined
  }
  return { shotIds, ...(options || {}) }
}

export const videoApi = {
  /** Legacy: generate reference images for storyboard shots */
  generateImages: (projectId: number | string, shotIds?: Array<number | string>) =>
    request.post(`/videos/generate-images/${projectId}`, buildShotSelectionPayload(shotIds)),

  /** Generate videos for selected storyboard shots */
  generateStoryboardVideos: (projectId: number | string, shotIds?: Array<number | string>, options?: DynamicOptions) =>
    request.post(`/videos/generate-dynamic/${projectId}`, buildShotSelectionPayload(shotIds, options)),

  /** Generate videos for selected storyboard shots */
  generateDynamic: (projectId: number | string, shotIds?: Array<number | string>, options?: DynamicOptions) =>
    request.post(`/videos/generate-dynamic/${projectId}`, buildShotSelectionPayload(shotIds, options)),

  /** Generate TTS audio for all storyboard shots */
  generateAudio: (projectId: number | string, shotIds?: Array<number | string>) =>
    request.post(`/videos/generate-audio/${projectId}`, buildShotSelectionPayload(shotIds)),

  /** Start video composition */
  compose: (projectId: number | string, shotIds?: Array<number | string>, options?: ComposeOptions) =>
    request.post(`/videos/compose/${projectId}`, buildShotSelectionPayload(shotIds, options)),

  /** Get latest video composition status */
  getStatus: (projectId: number | string) =>
    request.get(`/videos/status/${projectId}`, { timeout: VIDEO_STATUS_TIMEOUT }),

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
