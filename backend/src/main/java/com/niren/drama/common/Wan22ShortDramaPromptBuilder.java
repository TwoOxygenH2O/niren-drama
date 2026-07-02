package com.niren.drama.common;

import com.niren.drama.entity.Character;
import com.niren.drama.entity.Project;
import com.niren.drama.entity.Scene;
import com.niren.drama.entity.Storyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds WAN 2.2 I2V prompts as shot directions instead of flat visual captions.
 */
public final class Wan22ShortDramaPromptBuilder {

    private Wan22ShortDramaPromptBuilder() {
    }

    public static String build(Storyboard shot,
                               Character character,
                               Scene scene,
                               Project project,
                               boolean chainedFromPreviousTail) {
        String base = firstText(
                shot != null ? shot.getVideoPrompt() : null,
                shot != null ? shot.getDescription() : null);
        if (!hasText(base)) {
            return null;
        }

        String cameraLanguage = resolveCameraLanguage(shot);
        String motionLevel = resolveMotionLevel(shot, base);
        String cameraMove = resolveCameraMove(shot, base, motionLevel);
        String performance = resolvePerformance(shot, base);
        String sceneDirection = resolveSceneDirection(scene);
        String visualGuide = ProjectStyleSupport.buildVisualCreationRules(
                        project != null ? project.getProjectType() : null,
                        project != null ? project.getGenre() : null)
                .replace("\n", " ")
                .replace("- ", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String continuityBible = ProjectStyleSupport.buildEpisodeContinuityBible(
                project != null ? project.getProjectType() : null,
                project != null ? project.getGenre() : null);

        List<String> parts = new ArrayList<>();
        parts.add("WAN 2.2 image-to-video, commercial vertical short-drama, one continuous 9:16 live-action shot.");
        if (chainedFromPreviousTail) {
            parts.add("The input image is the previous shot's last frame and must become this shot's first frame; continue the action naturally without a visible jump cut.");
        } else {
            parts.add("The input image is the exact first frame; preserve identity, face, hairstyle, outfit, age, body shape, props, lighting, camera angle, and scene layout.");
        }
        parts.add(continuityBible);
        parts.add("Do not redraw the image, do not turn it into sketch, comic, line art, monochrome, CGI, poster, or slideshow.");
        parts.add("Shot size and staging: " + cameraLanguage);
        parts.add("Camera movement: " + cameraMove);
        parts.add("Actor performance: " + performance);
        if (hasText(sceneDirection)) {
            parts.add("Scene continuity: " + sceneDirection);
        }
        parts.add("Action beat: " + trimToLength(sanitizeActionBeat(base), 360));

        if (shot != null && hasText(shot.getDialogue())) {
            parts.add("Dialogue motivation: the actor may use subtle lip movement, breath timing, eye focus, and facial reaction; do not render subtitles or text.");
        }
        if (shot != null && hasText(shot.getNarration())) {
            parts.add("Narration is off-screen emotional context only; show the feeling through body language, expression, and camera rhythm, not on-screen text.");
        }
        if (character != null && hasText(character.getName())) {
            StringBuilder characterLine = new StringBuilder("Character lock: ").append(character.getName());
            if (hasText(character.getAppearance())) {
                characterLine.append(", ").append(trimToLength(character.getAppearance(), 120));
            }
            if (hasText(character.getPersonality())) {
                characterLine.append("; performance temperament: ").append(trimToLength(character.getPersonality(), 80));
            }
            characterLine.append(". Keep the same person, no face morphing, no wardrobe change.");
            parts.add(characterLine.toString());
        }
        if (scene != null && hasText(scene.getName())) {
            StringBuilder sceneLine = new StringBuilder("Scene lock: ").append(scene.getName());
            if (hasText(scene.getDescription())) {
                sceneLine.append(", ").append(trimToLength(scene.getDescription(), 120));
            }
            if (hasText(scene.getTimeOfDay())) {
                sceneLine.append("; time of day: ").append(scene.getTimeOfDay());
            }
            parts.add(sceneLine.toString());
        }
        parts.add("Temporal design: clear beginning, middle, and end inside the same shot; visible actor-local action progression, expression change, hand/cloth/hair movement, and anchored background continuity, but no whole-frame drift, no scene jump, and no new person.");
        parts.add("Motion intensity: " + motionLevel + "; enough motion to avoid frozen-frame output while keeping identity and continuity stable.");
        parts.add("Visual style boundary: " + trimToLength(visualGuide, 180));
        parts.add("Negative constraints: no camera cut, no extra person, no duplicated character, no distorted hands, no flicker, no subtitles, no logo, no watermark, no whole-frame pan, no gif-like zoom, no Ken Burns effect.");

        return String.join(" ", parts).replaceAll("\\s+", " ").trim();
    }

    private static String resolveCameraLanguage(Storyboard shot) {
        String angle = shot != null ? normalize(shot.getCameraAngle()) : "";
        return switch (angle) {
            case "close-up", "closeup", "特写", "近景" ->
                    "tight close-up, keep facial identity stable, let eyes, breath, lips, and micro expression carry the drama.";
            case "medium", "mid", "medium-shot", "中景" ->
                    "medium shot, include upper body reaction, hands, shoulders, and readable face expression.";
            case "wide", "long", "全景", "远景" ->
                    "wide shot, preserve the full scene layout and make body movement, blocking, and environmental motion readable.";
            case "overhead", "top", "俯拍" ->
                    "overhead or high-angle shot, keep spatial geography clear and avoid rotating the whole scene unnaturally.";
            case "pov", "主观" ->
                    "subjective POV feeling, maintain the original lens position and make the actor react toward camera naturally.";
            default ->
                    "cinematic short-drama framing, readable actor performance and scene geography.";
        };
    }

    private static String resolveCameraMove(Storyboard shot, String base, String motionLevel) {
        String text = normalize((shot != null ? firstText(shot.getVideoPrompt(), shot.getDescription()) : "") + " " + base);
        if (containsAny(text, "推进", "推近", "push", "push-in", "dolly in", "逼近")) {
            return "nearly locked camera; avoid whole-frame zoom or pan, keep the background anchored, and imply the push-in through actor lean, eye focus, foreground candle movement, and shallow depth-of-field change.";
        }
        if (containsAny(text, "拉远", "pull", "pull-back", "dolly out", "后退")) {
            return "nearly locked camera; avoid whole-frame pull-back, keep scene geometry anchored, and reveal emotion through body shift, hands, cloth, and foreground/background depth cues.";
        }
        if (containsAny(text, "摇", "pan", "横移", "移镜", "跟拍", "tracking", "track")) {
            return "actor-following composition with the background mostly anchored; use only tiny stabilization drift, no broad pan, no whole-frame translation.";
        }
        if (containsAny(text, "俯仰", "tilt", "抬头", "低头")) {
            return "locked camera height with actor head/eye movement carrying the tilt feeling; no whole-frame tilt or abrupt reframing.";
        }
        if ("high".equals(motionLevel)) {
            return "stable handheld feeling with the set anchored; emphasize body action, sleeve motion, hair motion, and prop interaction instead of moving the whole frame.";
        }
        if (shot != null && "wide".equals(normalize(shot.getCameraAngle()))) {
            return "mostly locked wide shot, preserve scene geography, and show blocking through actor movement rather than a broad camera pan.";
        }
        return "locked-off or almost locked camera; keep background, props, and horizon anchored, with visible motion coming from the actor's face, eyes, hands, breathing, cloth, hair, and present environmental elements.";
    }

    private static String resolvePerformance(Storyboard shot, String base) {
        String text = normalize((shot != null ? firstText(shot.getVideoPrompt(), shot.getDescription(), shot.getDialogue()) : "") + " " + base);
        List<String> cues = new ArrayList<>();
        if (containsAny(text, "哭", "泪", "悲", "心碎", "sad", "cry")) {
            cues.add("eyes become wet, restrained crying, trembling breath");
        }
        if (containsAny(text, "怒", "吼", "争吵", "愤", "angry", "rage")) {
            cues.add("anger through tightened jaw, sharper eye focus, controlled body tension");
        }
        if (containsAny(text, "笑", "甜", "温柔", "relief", "smile")) {
            cues.add("small believable smile, softened gaze, relaxed shoulders");
        }
        if (containsAny(text, "跑", "追", "逃", "冲", "fight", "打", "推", "抱", "转身", "跪")) {
            cues.add("clear body action with weight shift, hand motion, head turn, and cloth/hair response");
        }
        if (shot != null && hasText(shot.getDialogue())) {
            cues.add("subtle lip movement synchronized with emotional intent");
        }
        cues.add("natural breathing, blinking, eye movement, small head and hand reactions");
        return String.join("; ", cues);
    }

    private static String resolveSceneDirection(Scene scene) {
        if (scene == null) {
            return "";
        }
        List<String> cues = new ArrayList<>();
        if (hasText(scene.getLocation())) {
            cues.add(scene.getLocation());
        }
        if (hasText(scene.getTimeOfDay())) {
            cues.add("keep " + scene.getTimeOfDay() + " lighting");
        }
        if (hasText(scene.getDescription())) {
            cues.add(trimToLength(scene.getDescription(), 120));
        }
        cues.add("allow subtle light, shadow, curtain, smoke, rain, or reflection motion only when present in the frame");
        return String.join(", ", cues);
    }

    private static String resolveMotionLevel(Storyboard shot, String base) {
        String text = normalize((shot != null ? firstText(shot.getVideoPrompt(), shot.getDescription(), shot.getDialogue()) : "")
                + " " + base);
        if (containsAny(text, "跑", "追", "逃", "冲", "打", "推开", "推倒", "摔", "跪", "转身", "fight", "run", "chase")) {
            return "high";
        }
        boolean hasVisibleAction = containsAny(text,
                "睁眼", "猛然", "颤", "攥", "掐", "抬", "拔", "抓", "挥", "走", "回头", "转头", "起身",
                "站起", "跪下", "倒下", "踉跄", "哭", "泪", "推开", "拉住", "抱", "晃动", "摇曳", "起伏",
                "flicker", "tremble", "turn", "grip", "raise", "walk");
        if (shot != null && hasText(shot.getMotionLevel())) {
            String normalized = normalize(shot.getMotionLevel());
            if (normalized.contains("high") || normalized.contains("强") || normalized.contains("高")) {
                return "high";
            }
            if ((normalized.contains("low") || normalized.contains("弱") || normalized.contains("低"))
                    && !hasVisibleAction) {
                return "low";
            }
        }
        if (hasVisibleAction) {
            return "medium";
        }
        if (containsAny(text, "静止", "不动", "定格", "凝视", "沉默", "看着", "低头", "微笑")) {
            return "low";
        }
        return "medium";
    }

    private static boolean containsAny(String text, String... needles) {
        if (!hasText(text)) {
            return false;
        }
        for (String needle : needles) {
            if (hasText(needle) && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static String trimToLength(String text, int maxLen) {
        if (!hasText(text)) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLen ? normalized : normalized.substring(0, maxLen) + "...";
    }

    private static String sanitizeActionBeat(String text) {
        if (!hasText(text)) {
            return "";
        }
        String sanitized = text.trim()
                .replace("镜头缓慢推进至", "锁机位中让焦点过渡到")
                .replace("镜头缓慢推近至", "锁机位中让人物动作引导视线到")
                .replace("镜头缓缓推进至", "锁机位中让焦点过渡到")
                .replace("镜头缓缓推近至", "锁机位中让人物动作引导视线到")
                .replace("缓慢推进至", "通过人物动作和景深变化呈现")
                .replace("缓慢推近至", "通过人物动作和景深变化呈现")
                .replace("缓缓推进至", "通过人物动作和景深变化呈现")
                .replace("缓缓推近至", "通过人物动作和景深变化呈现")
                .replace("缓推镜头", "锁机位表演镜头")
                .replace("镜头缓慢推进", "锁机位下人物动作推进情绪")
                .replace("镜头缓慢推近", "锁机位下人物动作推进情绪")
                .replace("镜头缓缓推进", "锁机位下人物动作推进情绪")
                .replace("镜头缓缓推近", "锁机位下人物动作推进情绪")
                .replace("镜头下移", "演员低头或手部动作进入画面")
                .replace("镜头上移", "演员抬眼或起身动作进入画面")
                .replace("镜头右移", "角色转身或视线右移")
                .replace("镜头左移", "角色转身或视线左移")
                .replace("背景虚化推进", "背景保持锚定，仅用浅景深和光影变化")
                .replace("推至", "动作引导视线到")
                .replace("推向", "动作引导视线到")
                .replace("推进", "情绪推进")
                .replace("推近", "表演靠近")
                .replace("拉远", "人物退步或空间层次显露");
        return sanitized + " Keep the camera locked or nearly locked; all visible progression should come from actor-local motion, focus breathing, cloth hair, props, light, shadow, smoke, rain, or candle movement.";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
