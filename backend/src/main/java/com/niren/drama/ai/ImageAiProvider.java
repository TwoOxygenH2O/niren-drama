package com.niren.drama.ai;

public interface ImageAiProvider {

    /**
     * Generate an image and return its URL.
     *
     * @param prompt the image description
     * @param size   image size (e.g. "1024x1792" for vertical)
     * @param style  optional style hint
     * @return URL of the generated image
     */
    String generateImage(String prompt, String size, String style);
}
