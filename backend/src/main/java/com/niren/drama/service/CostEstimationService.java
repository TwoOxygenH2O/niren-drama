package com.niren.drama.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cost estimation service for AI drama generation.
 * Provides cost predictions before generation and helps users understand expenses.
 */
@Slf4j
@Service
public class CostEstimationService {

    @Value("${niren.cost.image-reuse-enabled:true}")
    private boolean imageReuseEnabled;

    @Value("${niren.cost.quality-tier:standard}")
    private String qualityTier;

    @Value("${niren.cost.video-ai-enabled:false}")
    private boolean videoAiEnabled;

    @Value("${niren.cost.video-ai-shot-ratio:0.3}")
    private double videoAiShotRatio;

    // ---- Pricing constants (in CNY ¥) ----

    // Text AI pricing (per million tokens, CNY)
    private static final double TEXT_INPUT_PRICE_PREMIUM = 18.0;    // GPT-4o (~$2.50)
    private static final double TEXT_OUTPUT_PRICE_PREMIUM = 72.0;   // GPT-4o (~$10)
    private static final double TEXT_INPUT_PRICE_STANDARD = 0.80;   // 豆包-pro
    private static final double TEXT_OUTPUT_PRICE_STANDARD = 2.00;  // 豆包-pro
    private static final double TEXT_INPUT_PRICE_PREVIEW = 0.30;    // 通义千问-turbo
    private static final double TEXT_OUTPUT_PRICE_PREVIEW = 0.60;   // 通义千问-turbo

    // Image AI pricing (per image, CNY)
    private static final double IMAGE_PRICE_PREMIUM = 0.87;     // DALL-E 3 1024x1792 (~$0.12)
    private static final double IMAGE_PRICE_STANDARD = 0.16;    // 通义万相
    private static final double IMAGE_PRICE_PREVIEW = 0.05;     // 百度文生图

    // Image resolution-based pricing adjustment
    private static final double IMAGE_PRICE_CLOSEUP_MULTIPLIER = 1.0;   // Full price for close-ups
    private static final double IMAGE_PRICE_MEDIUM_MULTIPLIER = 0.67;   // 2/3 price for medium shots
    private static final double IMAGE_PRICE_WIDE_MULTIPLIER = 0.33;     // 1/3 price for wide shots

    // TTS pricing (per thousand characters, CNY)
    private static final double TTS_PRICE_PREMIUM = 0.108;     // OpenAI tts-1 (~$15/M chars)
    private static final double TTS_PRICE_STANDARD = 0.01;     // 火山引擎
    private static final double TTS_PRICE_PREVIEW = 0.01;      // 火山引擎

    // Video AI pricing (per second, CNY) - Kling standard
    private static final double VIDEO_STD_NO_REF_NO_SOUND = 0.6;
    private static final double VIDEO_STD_NO_REF_WITH_SOUND = 0.8;
    private static final double VIDEO_STD_WITH_REF_NO_SOUND = 0.9;
    private static final double VIDEO_PRO_NO_REF_NO_SOUND = 0.8;
    private static final double VIDEO_PRO_NO_REF_WITH_SOUND = 1.0;
    private static final double VIDEO_PRO_WITH_REF_NO_SOUND = 1.2;

    // Default parameters
    private static final int DEFAULT_SHOT_DURATION = 5;
    private static final int DEFAULT_CHARS_PER_SHOT = 60;
    private static final int DEFAULT_TEXT_INPUT_TOKENS_PER_EPISODE = 5000;
    private static final int DEFAULT_TEXT_OUTPUT_TOKENS_PER_EPISODE = 12000;

    /**
     * Estimate the total cost of generating a complete drama series.
     *
     * @param episodes         number of episodes
     * @param episodeDuration  duration per episode in seconds
     * @param shotsPerEpisode  estimated shots per episode (0 = auto-calculate)
     * @return cost estimation result
     */
    public CostEstimation estimateSeriesCost(int episodes, int episodeDuration, int shotsPerEpisode) {
        if (shotsPerEpisode <= 0) {
            shotsPerEpisode = episodeDuration / DEFAULT_SHOT_DURATION;
        }

        CostEstimation est = new CostEstimation();
        est.setEpisodes(episodes);
        est.setEpisodeDuration(episodeDuration);
        est.setShotsPerEpisode(shotsPerEpisode);
        est.setQualityTier(qualityTier);
        est.setImageReuseEnabled(imageReuseEnabled);
        est.setVideoAiEnabled(videoAiEnabled);

        int totalShots = episodes * shotsPerEpisode;
        est.setTotalShots(totalShots);

        // 1. Text cost
        double textInputPrice = getTextInputPrice();
        double textOutputPrice = getTextOutputPrice();
        double textCostPerEpisode = (DEFAULT_TEXT_INPUT_TOKENS_PER_EPISODE / 1_000_000.0) * textInputPrice
                + (DEFAULT_TEXT_OUTPUT_TOKENS_PER_EPISODE / 1_000_000.0) * textOutputPrice;
        est.setTextCostPerEpisode(textCostPerEpisode);
        est.setTextCostTotal(textCostPerEpisode * episodes);

        // 2. Image cost (with reuse optimization)
        double imageBasePrice = getImagePrice();
        int uniqueImages = imageReuseEnabled ? estimateUniqueImages(totalShots, episodes) : totalShots;
        est.setUniqueImages(uniqueImages);

        // Apply resolution-based pricing (weighted average)
        double avgImagePrice = imageBasePrice * getResolutionPriceMultiplier();
        est.setImageCostPerEpisode(avgImagePrice * uniqueImages / episodes);
        est.setImageCostTotal(avgImagePrice * uniqueImages);

        // 3. TTS cost
        double ttsPrice = getTtsPrice();
        long totalChars = (long) totalShots * DEFAULT_CHARS_PER_SHOT;
        double ttsCost = (totalChars / 1000.0) * ttsPrice;
        est.setTtsCostPerEpisode(ttsCost / episodes);
        est.setTtsCostTotal(ttsCost);

        // 4. Video AI cost (only if enabled)
        if (videoAiEnabled) {
            int aiVideoShots = (int) (totalShots * videoAiShotRatio);
            int aiVideoDuration = aiVideoShots * DEFAULT_SHOT_DURATION;
            double videoCostPerSecond = VIDEO_STD_NO_REF_NO_SOUND; // Default: cheapest option
            double videoCost = aiVideoDuration * videoCostPerSecond;
            est.setVideoAiCostPerEpisode(videoCost / episodes);
            est.setVideoAiCostTotal(videoCost);
            est.setAiVideoShots(aiVideoShots);
        }

        // Total
        est.setTotalCostPerEpisode(est.getTextCostPerEpisode() + est.getImageCostPerEpisode()
                + est.getTtsCostPerEpisode() + est.getVideoAiCostPerEpisode());
        est.setTotalCost(est.getTextCostTotal() + est.getImageCostTotal()
                + est.getTtsCostTotal() + est.getVideoAiCostTotal());

        log.info("Cost estimation: {} episodes × {}s, quality={}, imageReuse={}, videoAi={}, total=¥{}",
                episodes, episodeDuration, qualityTier, imageReuseEnabled, videoAiEnabled,
                String.format("%.2f", est.getTotalCost()));

        return est;
    }

    /**
     * Estimate unique images needed after deduplication.
     * Based on typical scene/character/angle combinations reuse.
     */
    private int estimateUniqueImages(int totalShots, int episodes) {
        // Estimate: ~5 camera angles, ~10 scenes, ~5 characters = ~250 unique combos
        // With 50 episodes, reuse rate is approximately 60%
        int estimatedUniqueCombinations = 250;
        if (totalShots <= estimatedUniqueCombinations) {
            return totalShots;
        }
        // The more episodes, the higher the reuse rate
        double reuseRate = Math.min(0.8, 0.3 + (episodes * 0.01));
        return Math.max(estimatedUniqueCombinations, (int) (totalShots * (1 - reuseRate)));
    }

    private double getResolutionPriceMultiplier() {
        // Weighted average: 30% close-up (full), 40% medium (0.67), 30% wide (0.33)
        return 0.30 * IMAGE_PRICE_CLOSEUP_MULTIPLIER
                + 0.40 * IMAGE_PRICE_MEDIUM_MULTIPLIER
                + 0.30 * IMAGE_PRICE_WIDE_MULTIPLIER;
    }

    private double getTextInputPrice() {
        return switch (qualityTier) {
            case "premium" -> TEXT_INPUT_PRICE_PREMIUM;
            case "preview" -> TEXT_INPUT_PRICE_PREVIEW;
            default -> TEXT_INPUT_PRICE_STANDARD;
        };
    }

    private double getTextOutputPrice() {
        return switch (qualityTier) {
            case "premium" -> TEXT_OUTPUT_PRICE_PREMIUM;
            case "preview" -> TEXT_OUTPUT_PRICE_PREVIEW;
            default -> TEXT_OUTPUT_PRICE_STANDARD;
        };
    }

    private double getImagePrice() {
        return switch (qualityTier) {
            case "premium" -> IMAGE_PRICE_PREMIUM;
            case "preview" -> IMAGE_PRICE_PREVIEW;
            default -> IMAGE_PRICE_STANDARD;
        };
    }

    private double getTtsPrice() {
        return switch (qualityTier) {
            case "premium" -> TTS_PRICE_PREMIUM;
            case "preview" -> TTS_PRICE_PREVIEW;
            default -> TTS_PRICE_STANDARD;
        };
    }

    /**
     * Get the optimal image size for a given camera angle based on quality tier.
     */
    public String getOptimalImageSize(String cameraAngle) {
        if (cameraAngle == null) cameraAngle = "medium";

        return switch (qualityTier) {
            case "preview" -> "512x512"; // Low-res for all shots in preview mode
            case "premium" -> "1024x1792"; // High-res for all shots in premium mode
            default -> switch (cameraAngle.toLowerCase()) {
                case "close-up", "pov" -> "1024x1792";  // High-res for close-ups
                case "wide", "overhead" -> "512x912";    // Lower-res for wide shots
                default -> "1024x1024";                  // Standard for medium shots
            };
        };
    }

    @Data
    public static class CostEstimation {
        private int episodes;
        private int episodeDuration;
        private int shotsPerEpisode;
        private int totalShots;
        private int uniqueImages;
        private int aiVideoShots;
        private String qualityTier;
        private boolean imageReuseEnabled;
        private boolean videoAiEnabled;

        private double textCostPerEpisode;
        private double textCostTotal;
        private double imageCostPerEpisode;
        private double imageCostTotal;
        private double ttsCostPerEpisode;
        private double ttsCostTotal;
        private double videoAiCostPerEpisode;
        private double videoAiCostTotal;
        private double totalCostPerEpisode;
        private double totalCost;
    }
}
