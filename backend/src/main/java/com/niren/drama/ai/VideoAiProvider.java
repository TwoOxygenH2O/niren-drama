package com.niren.drama.ai;

/**
 * AI video generation provider interface.
 * Supports providers like Kling AI (可灵), Seedance, Runway, etc.
 */
public interface VideoAiProvider {

    /**
     * Generate a video from a text prompt (text-to-video).
     *
     * @param prompt      text description of the video content
     * @param duration    desired video duration in seconds
     * @param resolution  output resolution (e.g. "1080P", "720P", "480P")
     * @param quality     quality tier: "standard" or "pro"
     * @param withSound   whether to include AI-generated sound
     * @return URL or identifier of the generated video
     */
    String generateVideoFromText(String prompt, int duration, String resolution, String quality, boolean withSound);

    /**
     * Generate a video from an image and text prompt (image-to-video).
     * Uses the image as a reference frame for the video.
     *
     * @param imageUrl    URL of the reference image
     * @param prompt      text description of the motion/action
     * @param duration    desired video duration in seconds
     * @param resolution  output resolution (e.g. "1080P", "720P", "480P")
     * @param quality     quality tier: "standard" or "pro"
     * @param withSound   whether to include AI-generated sound
     * @return URL or identifier of the generated video
     */
    String generateVideoFromImage(String imageUrl, String prompt, int duration, String resolution, String quality, boolean withSound);

    /**
     * Estimate the cost of generating a video in CNY (¥).
     *
     * @param durationSeconds video duration in seconds
     * @param quality         quality tier: "standard" or "pro"
     * @param hasReferenceVideo whether a reference video is provided
     * @param withSound       whether to include AI-generated sound
     * @return estimated cost in CNY
     */
    double estimateCost(int durationSeconds, String quality, boolean hasReferenceVideo, boolean withSound);
}
