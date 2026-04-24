package com.niren.drama.ai;

import java.util.List;

public interface ImageAiProvider {

    int MIN_TOTAL_PIXELS = 655_360;
    String DEFAULT_PORTRAIT_SIZE = "1024x1792";
    String DEFAULT_SQUARE_SIZE = "1024x1024";
    String DEFAULT_LANDSCAPE_SIZE = "1792x1024";

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

    /**
     * Generate an image with optional reference images and provider-specific negative prompt.
     */
    default String generateImage(String prompt,
                                 String size,
                                 String style,
                                 List<String> referenceImageUrls,
                                 String negativePrompt) {
        return generateImage(prompt, size, style, referenceImageUrls);
    }

    default String normalizeImageSize(String requestedSize) {
        if (requestedSize == null || requestedSize.isBlank()) {
            return DEFAULT_PORTRAIT_SIZE;
        }

        String normalized = requestedSize.trim();
        String[] parts = normalized.toLowerCase().split("x");
        if (parts.length != 2) {
            return normalized;
        }

        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            if (width <= 0 || height <= 0) {
                return DEFAULT_PORTRAIT_SIZE;
            }
            if ((long) width * height >= MIN_TOTAL_PIXELS) {
                return normalized;
            }
            if (width == height) {
                return DEFAULT_SQUARE_SIZE;
            }
            return width > height ? DEFAULT_LANDSCAPE_SIZE : DEFAULT_PORTRAIT_SIZE;
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }
}
