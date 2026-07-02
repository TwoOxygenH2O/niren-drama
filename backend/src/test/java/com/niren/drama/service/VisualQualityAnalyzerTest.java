package com.niren.drama.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisualQualityAnalyzerTest {

    @Test
    void flagsLowDetailFramesAsUnwatchableVisual() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(lowDetailFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .contains("low_visual_detail", "unwatchable_visual");
    }

    @Test
    void flagsNoisyGrayFramesAsWashedGrayVideo() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(noisyGrayFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .contains("washed_gray_video");
        assertThat(report.findings())
                .filteredOn(finding -> "washed_gray_video".equals(finding.issueType()))
                .extracting(VisualQualityFinding::severity)
                .containsExactly("blocking");
        assertThat(report.metrics())
                .containsKey("averageColorfulness")
                .containsKey("lowColorFrameRatio");
    }

    @Test
    void doesNotFlagSharpReadableMotion() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(sharpMovingFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .doesNotContain("low_visual_detail", "unwatchable_visual", "weak_motion");
    }

    @Test
    void flagsGlobalDriftAsAnimatedStillInsteadOfReadablePerformance() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(globalDriftFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .contains("animated_still");
        assertThat(report.findings())
                .filteredOn(finding -> "animated_still".equals(finding.issueType()))
                .extracting(VisualQualityFinding::recommendedAction)
                .containsExactly("switchWan");
        assertThat(report.metrics())
                .containsKey("globalMotionLikeRatio")
                .containsKey("gridMotionCoefficientOfVariation")
                .containsKey("translationCompensatedResidualRatio");
    }

    @Test
    void doesNotFlagLocalizedActorPerformanceAsAnimatedStill() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(localizedActorPerformanceFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .doesNotContain("animated_still");
        assertThat(report.metrics())
                .containsEntry("localPerformanceEvidence", true);
    }

    @Test
    void doesNotFlagCenterSubjectActionAsAnimatedStill() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(centerSubjectActionFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .doesNotContain("animated_still");
        assertThat(report.metrics())
                .containsEntry("localPerformanceEvidence", true)
                .containsKey("localPerformanceEvidenceReason");
    }

    @Test
    void doesNotFlagLargeSubjectTraversalAsAnimatedStill() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(largeSubjectTraversalFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .doesNotContain("animated_still");
        assertThat(report.metrics())
                .containsEntry("localPerformanceEvidence", true)
                .containsEntry("localPerformanceEvidenceReason", "translation_residual_subject_action");
    }

    @Test
    void flagsRepeatedAdjacentFramesAsLowEffectiveFrameRate() {
        VisualQualityAnalyzer analyzer = new VisualQualityAnalyzer();

        VisualQualityReport report = analyzer.analyzeFrames(repeatedAdjacentFrames());

        assertThat(report.findings())
                .extracting(VisualQualityFinding::issueType)
                .contains("low_effective_fps");
        assertThat(report.findings())
                .filteredOn(finding -> "low_effective_fps".equals(finding.issueType()))
                .extracting(VisualQualityFinding::severity)
                .containsExactly("blocking");
        assertThat(report.metrics())
                .containsKey("duplicateAdjacentFrameRatio");
    }

    private static List<BufferedImage> lowDetailFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BufferedImage image = new BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            int tone = 118 + i;
            g.setColor(new Color(tone, tone, tone));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.dispose();
            frames.add(image);
        }
        return frames;
    }

    private static List<BufferedImage> noisyGrayFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BufferedImage image = new BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int noise = ((x * 17 + y * 31 + i * 11) % 23) - 11;
                    int tone = Math.max(0, Math.min(255, 128 + noise));
                    image.setRGB(x, y, new Color(tone, tone + ((x + i) % 2), tone).getRGB());
                }
            }
            frames.add(image);
        }
        return frames;
    }

    private static List<BufferedImage> sharpMovingFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BufferedImage image = new BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            for (int y = 0; y < image.getHeight(); y += 12) {
                for (int x = 0; x < image.getWidth(); x += 12) {
                    if (((x + y) / 12) % 2 == 0) {
                        g.fillRect(x, y, 12, 12);
                    }
                }
            }
            g.setColor(new Color(180, 30, 30));
            g.fillRect(10 + i * 5, 55, 32, 44);
            g.dispose();
            frames.add(image);
        }
        return frames;
    }

    private static List<BufferedImage> globalDriftFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        BufferedImage base = texturedFrame();
        for (int i = 0; i < 8; i++) {
            BufferedImage image = new BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(238, 238, 236));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(base, i, i, null);
            g.dispose();
            frames.add(image);
        }
        return frames;
    }

    private static List<BufferedImage> localizedActorPerformanceFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BufferedImage image = texturedFrame();
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(226, 185, 160));
            g.fillOval(44, 24, 32, 36);
            g.setColor(new Color(60, 45, 38));
            g.fillRect(43, 42, 34, 88);
            g.setColor(Color.BLACK);
            g.drawLine(50, 40, 58 + i, 39);
            g.drawLine(63, 40, 71, 39);
            g.setColor(new Color(130, 40, 42));
            g.fillOval(56, 52, 8 + (i % 3) * 3, 4 + (i % 4));
            g.setColor(new Color(226, 185, 160));
            g.fillOval(26 + i * 4, 92 - i, 18, 18);
            g.setColor(new Color(238, 235, 228));
            g.fillRect(20 + i * 4, 108 - i, 34, 12);
            g.dispose();
            frames.add(image);
        }
        return frames;
    }

    private static List<BufferedImage> centerSubjectActionFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BufferedImage image = texturedFrame();
            Graphics2D g = image.createGraphics();
            int bodyWidth = 34 + i * 3;
            int bodyHeight = 72 + i * 5;
            int x = 60 - bodyWidth / 2;
            int y = 64 - i * 2;
            g.setColor(new Color(72, 116, 106));
            g.fillRect(x, y, bodyWidth, bodyHeight);
            g.setColor(new Color(226, 185, 160));
            g.fillOval(46 - i, 28 - i, 28 + i * 2, 34 + i * 2);
            g.setColor(new Color(60, 35, 35));
            g.drawLine(50, 43, 60 + i, 42);
            g.drawLine(64, 43, 74, 42);
            g.setColor(new Color(238, 235, 228));
            g.fillRect(34 - i * 2, 90 - i, 24 + i * 3, 12);
            g.fillRect(70, 92 - i, 28 + i * 3, 12);
            g.dispose();
            frames.add(image);
        }
        return frames;
    }

    private static List<BufferedImage> largeSubjectTraversalFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            BufferedImage image = texturedFrame();
            Graphics2D g = image.createGraphics();
            int x = 16 + i * 11;
            int y = 30 + i * 10;
            g.setColor(new Color(18, 18, 22));
            g.fillOval(x + 10, y, 24, 28);
            g.fillRect(x, y + 24, 48, 66);
            g.fillPolygon(
                    new int[]{x - 14, x + 8, x + 52 + i, x + 66 + i},
                    new int[]{y + 42, y + 30, y + 42, y + 58},
                    4);
            g.setColor(new Color(226, 185, 160));
            g.fillOval(x + 16, y + 8, 14, 12);
            g.setColor(new Color(60, 35, 35));
            g.drawLine(x + 14, y + 36, x - 12 + i * 2, y + 60);
            g.drawLine(x + 36, y + 36, x + 66 + i * 2, y + 54);
            g.dispose();
            frames.add(image);
        }
        return frames;
    }

    private static List<BufferedImage> repeatedAdjacentFrames() {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BufferedImage image = texturedFrame();
            Graphics2D g = image.createGraphics();
            int offset = (i / 2) * 6;
            g.setColor(new Color(30, 80, 140));
            g.fillRect(20 + offset, 58, 38, 60);
            g.setColor(new Color(230, 210, 190));
            g.fillOval(28 + offset, 36, 20, 24);
            g.dispose();
            frames.add(image);
        }
        return frames;
    }

    private static BufferedImage texturedFrame() {
        BufferedImage image = new BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(232, 230, 220));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        for (int y = 0; y < image.getHeight(); y += 10) {
            int tone = 120 + (y % 40);
            g.setColor(new Color(tone, tone + 20, Math.min(255, tone + 40)));
            g.drawLine(0, y, image.getWidth(), Math.max(0, y - 24));
        }
        g.setColor(new Color(75, 62, 52));
        g.fillRect(42, 42, 36, 92);
        g.setColor(new Color(210, 180, 150));
        g.fillOval(45, 24, 30, 34);
        g.setColor(Color.BLACK);
        g.drawLine(50, 40, 68, 39);
        g.drawLine(46, 78, 24, 112);
        g.drawLine(74, 78, 98, 108);
        g.dispose();
        return image;
    }
}
