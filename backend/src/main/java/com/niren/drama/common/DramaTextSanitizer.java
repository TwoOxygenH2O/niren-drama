package com.niren.drama.common;

import com.niren.drama.entity.Storyboard;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 AI 分镜/剧本里偏书面、伪换行、带说话人标签的文本，统一成更适合配音与上屏的口语化形态。
 * 与合成环节 {@code VideoCompositionService} 的字幕清洗保持一致入口。
 */
public final class DramaTextSanitizer {

    private DramaTextSanitizer() {
    }

    /**
     * 折叠空白、去掉字面量 \n/\\n、统一为单行可读（用于对话/旁白字段入库与合成前）。
     */
    public static String normalizeSpokenText(String source) {
        if (StringUtils.isBlank(source)) {
            return null;
        }
        String s = source
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 去掉句首「说话人+冒号/半角:」；角色名在 {@link Storyboard} 的 character 信息里体现即可。
     * 只处理开头一段，避免误伤正文中含冒号的内容（保守匹配长度）。
     */
    public static String stripLeadingSpeakerLabel(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String t = text.stripLeading();
        t = t.replaceFirst("^[\\p{IsHan}A-Za-z0-9·.\\s　]{1,20}[：:]\\s*", "");
        t = t.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 在 dialogue 与 narration 完全一致时，去掉重复的一条（一般保留对白，清旁白）。
     */
    public static void dedupeDialogueNarration(Storyboard shot, boolean keepDialogue) {
        if (shot == null) {
            return;
        }
        String d = StringUtils.trimToNull(shot.getDialogue());
        String n = StringUtils.trimToNull(shot.getNarration());
        if (d != null && n != null && d.equals(n)) {
            if (keepDialogue) {
                shot.setNarration(null);
            } else {
                shot.setDialogue(null);
            }
        }
    }

    /**
     * 对分镜实体的对话/旁白做：规范化、可选去重、可选去说话人前缀（对白、旁白分别可配）。
     */
    public static void applyToStoryboard(Storyboard shot,
                                         boolean stripSpeakerOnDialogue,
                                         boolean stripSpeakerOnNarration,
                                         boolean dedupe) {
        if (shot == null) {
            return;
        }
        if (StringUtils.isNotBlank(shot.getDialogue())) {
            String d = normalizeSpokenText(shot.getDialogue());
            if (stripSpeakerOnDialogue) {
                d = stripLeadingSpeakerLabel(d);
            }
            shot.setDialogue(d);
        }
        if (StringUtils.isNotBlank(shot.getNarration())) {
            String n = normalizeSpokenText(shot.getNarration());
            if (stripSpeakerOnNarration) {
                n = stripLeadingSpeakerLabel(n);
            }
            shot.setNarration(n);
        }
        if (dedupe) {
            dedupeDialogueNarration(shot, true);
        }
    }

    /**
     * 与分镜念白一致：旁白后接对白（同 {@link com.niren.drama.service.StoryboardService} 旧逻辑）。
     */
    public static String deriveTtsFromDialogueNarration(Storyboard shot) {
        if (shot == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(shot.getNarration())) {
            sb.append(shot.getNarration().trim());
        }
        if (StringUtils.isNotBlank(shot.getDialogue())) {
            if (!sb.isEmpty()) {
                sb.append("。");
            }
            sb.append(shot.getDialogue().trim());
        }
        return sb.toString().trim();
    }

    public static String resolveEffectiveTts(Storyboard shot) {
        if (shot == null) {
            return "";
        }
        if (StringUtils.isNotBlank(shot.getTtsText())) {
            String n = normalizeSpokenText(shot.getTtsText().trim());
            return n != null ? n : "";
        }
        return deriveTtsFromDialogueNarration(shot);
    }

    public static String deriveRawSubtitle(Storyboard shot,
                                           boolean includeNarration,
                                           boolean stripDialogue,
                                           boolean stripNarration) {
        if (shot == null) {
            return null;
        }
        String dialogue = normalizeSpokenText(shot.getDialogue());
        if (stripDialogue) {
            dialogue = stripLeadingSpeakerLabel(dialogue);
        }
        if (!includeNarration) {
            return dialogue;
        }
        String narration = normalizeSpokenText(shot.getNarration());
        if (stripNarration) {
            narration = stripLeadingSpeakerLabel(narration);
        }
        if (StringUtils.isNotBlank(dialogue) && StringUtils.isNotBlank(narration) && !dialogue.equals(narration)) {
            return dialogue + "\n" + narration;
        }
        return StringUtils.isNotBlank(dialogue) ? dialogue : narration;
    }

    /**
     * 与 {@code VideoCompositionService#wrapSubtitleText} 同逻辑，供 API 层展示与合成复用。
     */
    public static String wrapSubtitleLines(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;
        int maxWidth = 26;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                }
                currentLine.setLength(0);
                currentWidth = 0;
                continue;
            }
            int charWidth = ch > 255 ? 2 : 1;
            if (currentWidth + charWidth > maxWidth && !currentLine.isEmpty()) {
                lines.add(currentLine.toString());
                currentLine.setLength(0);
                currentWidth = 0;
            }
            currentLine.append(ch);
            currentWidth += charWidth;
            if (lines.size() >= 2 && currentWidth >= maxWidth) {
                break;
            }
        }
        if (currentLine.length() > 0 && lines.size() < 3) {
            lines.add(currentLine.toString());
        }
        return lines.isEmpty() ? null : String.join("\n", lines.subList(0, Math.min(3, lines.size())));
    }

    public static String resolveEffectiveWrappedSubtitle(Storyboard shot,
                                                       boolean includeNarration,
                                                       boolean stripDialogue,
                                                       boolean stripNarration) {
        if (shot == null) {
            return null;
        }
        if (StringUtils.isNotBlank(shot.getSubtitleText())) {
            String n = normalizeSpokenText(shot.getSubtitleText().trim());
            return wrapSubtitleLines(n != null ? n : "");
        }
        return wrapSubtitleLines(deriveRawSubtitle(shot, includeNarration, stripDialogue, stripNarration));
    }
}
