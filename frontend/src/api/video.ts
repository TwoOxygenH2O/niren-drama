import request from './request'

export const videoApi = {
  /** Generate images for all storyboard shots */
  generateImages: (projectId: number | string) =>
    request.post(`/videos/generate-images/${projectId}`),

  /** Generate dynamic clips for selected storyboard shots */
  generateDynamic: (projectId: number | string) =>
    request.post(`/videos/generate-dynamic/${projectId}`),

  /** Generate TTS audio for all storyboard shots */
  generateAudio: (projectId: number | string) =>
    request.post(`/videos/generate-audio/${projectId}`),

  /** Start video composition */
  compose: (projectId: number | string) =>
    request.post(`/videos/compose/${projectId}`),

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
