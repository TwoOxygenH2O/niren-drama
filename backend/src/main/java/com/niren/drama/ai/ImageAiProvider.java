package com.niren.drama.ai;

import java.util.List;

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

    /**
     * Generate an image with optional reference images.
     *
     * @param prompt              the image description
     * @param size                image size (e.g. "1024x1792" for vertical)
     * @param style               optional style hint
     * @param referenceImageUrls  optional reference image URLs for consistency/editing
     * @return URL of the generated image
     */
    default String generateImage(String prompt, String size, String style, List<String> referenceImageUrls) {
        return generateImage(prompt, size, style);
    }
}
