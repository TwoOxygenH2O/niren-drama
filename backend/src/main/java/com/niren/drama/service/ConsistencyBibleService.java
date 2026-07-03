package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.Character;
import com.niren.drama.entity.ConsistencyBible;
import com.niren.drama.entity.Scene;
import com.niren.drama.mapper.ConsistencyBibleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsistencyBibleService {

    private static final int DEFAULT_GUIDE_MAX_CHARS = 720;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ConsistencyBibleMapper consistencyBibleMapper;
    private final ObjectMapper objectMapper;

    public void syncCharacterBible(Character character) {
        if (character == null || character.getProjectId() == null || character.getId() == null) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        putIfHasText(attrs, "name", character.getName());
        putIfHasText(attrs, "gender", character.getGender());
        putIfHasText(attrs, "age", character.getAge());
        putIfHasText(attrs, "appearance", character.getAppearance());
        putIfHasText(attrs, "temperament", character.getPersonality());
        putIfHasText(attrs, "voice", character.getVoiceName());
        upsertBible(
                character.getProjectId(),
                "character",
                character.getId(),
                hasText(character.getName()) ? character.getName() + " 角色锚点" : "角色锚点",
                attrs,
                "由角色库同步，生成图片和视频时用于锁定身份、服装、外貌与表演气质。");
    }

    public void syncSceneBible(Scene scene) {
        if (scene == null || scene.getProjectId() == null || scene.getId() == null) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        putIfHasText(attrs, "name", scene.getName());
        putIfHasText(attrs, "location", scene.getLocation());
        putIfHasText(attrs, "timeOfDay", scene.getTimeOfDay());
        putIfHasText(attrs, "description", scene.getDescription());
        upsertBible(
                scene.getProjectId(),
                "scene",
                scene.getId(),
                hasText(scene.getName()) ? scene.getName() + " 场景锚点" : "场景锚点",
                attrs,
                "由场景库同步，生成图片和视频时用于锁定地点、光线、陈设与环境连续性。");
    }

    public String appendPromptConstraints(Long projectId,
                                          Long characterId,
                                          Long sceneId,
                                          String prompt,
                                          int maxChars) {
        if (!hasText(prompt) || projectId == null) {
            return prompt;
        }
        String guide = buildPromptGuide(projectId, characterId, sceneId, DEFAULT_GUIDE_MAX_CHARS);
        if (!hasText(guide)) {
            return prompt;
        }
        String prefix = " Consistency bible lock: ";
        String suffix = prefix + guide;
        if (maxChars > 0 && prompt.length() + suffix.length() > maxChars) {
            int guideBudget = maxChars - prompt.length() - prefix.length();
            if (guideBudget < 80) {
                return prompt;
            }
            suffix = prefix + trimToLength(guide, guideBudget);
        }
        return prompt + suffix;
    }

    public String buildPromptGuide(Long projectId, Long characterId, Long sceneId, int maxChars) {
        if (projectId == null) {
            return "";
        }
        List<ConsistencyBible> bibles = consistencyBibleMapper.selectList(new LambdaQueryWrapper<ConsistencyBible>()
                .eq(ConsistencyBible::getProjectId, projectId)
                .orderByAsc(ConsistencyBible::getBibleType)
                .orderByAsc(ConsistencyBible::getCreateTime));
        List<String> parts = new ArrayList<>();
        for (ConsistencyBible bible : bibles) {
            if (!shouldUseInPrompt(bible, characterId, sceneId)) {
                continue;
            }
            String attrs = attributesForPrompt(bible.getLockedAttributes());
            if (!hasText(attrs)) {
                attrs = bible.getNotes();
            }
            if (!hasText(attrs)) {
                continue;
            }
            String type = hasText(bible.getBibleType()) ? bible.getBibleType().trim() : "style";
            String title = hasText(bible.getTitle()) ? bible.getTitle().trim() : "untitled";
            parts.add("[" + type + "] " + title + ": " + attrs);
        }
        return trimToLength(String.join("; ", parts), maxChars > 0 ? maxChars : DEFAULT_GUIDE_MAX_CHARS);
    }

    private void upsertBible(Long projectId,
                             String bibleType,
                             Long refId,
                             String title,
                             Map<String, Object> attrs,
                             String defaultNotes) {
        ConsistencyBible bible = consistencyBibleMapper.selectOne(new LambdaQueryWrapper<ConsistencyBible>()
                .eq(ConsistencyBible::getProjectId, projectId)
                .eq(ConsistencyBible::getBibleType, bibleType)
                .eq(ConsistencyBible::getRefId, refId)
                .last("LIMIT 1"));
        if (bible == null) {
            bible = new ConsistencyBible();
            bible.setProjectId(projectId);
            bible.setBibleType(bibleType);
            bible.setRefId(refId);
            bible.setLocked(true);
        }
        bible.setTitle(title);
        bible.setLockedAttributes(toJson(mergeAttributes(bible.getLockedAttributes(), attrs)));
        if (!hasText(bible.getNotes())) {
            bible.setNotes(defaultNotes);
        }
        if (bible.getLocked() == null) {
            bible.setLocked(true);
        }
        if (bible.getId() == null) {
            consistencyBibleMapper.insert(bible);
        } else {
            consistencyBibleMapper.updateById(bible);
        }
    }

    private Map<String, Object> mergeAttributes(String raw, Map<String, Object> attrs) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (hasText(raw)) {
            try {
                Object value = objectMapper.readValue(raw, Object.class);
                if (value instanceof Map<?, ?> map) {
                    map.forEach((key, val) -> merged.put(String.valueOf(key), val));
                }
            } catch (Exception ignored) {
                merged.put("legacy", raw.trim());
            }
        }
        attrs.forEach((key, value) -> {
            if (value != null && hasText(String.valueOf(value))) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private boolean shouldUseInPrompt(ConsistencyBible bible, Long characterId, Long sceneId) {
        if (bible == null || Boolean.FALSE.equals(bible.getLocked())) {
            return false;
        }
        String type = bible.getBibleType() != null ? bible.getBibleType().trim().toLowerCase(Locale.ROOT) : "";
        if ("character".equals(type)) {
            return bible.getRefId() == null || (characterId != null && characterId.equals(bible.getRefId()));
        }
        if ("scene".equals(type)) {
            return bible.getRefId() == null || (sceneId != null && sceneId.equals(bible.getRefId()));
        }
        return true;
    }

    private String attributesForPrompt(String raw) {
        if (!hasText(raw)) {
            return "";
        }
        try {
            Map<String, Object> map = objectMapper.readValue(raw, MAP_TYPE);
            List<String> parts = new ArrayList<>();
            map.forEach((key, value) -> {
                if (value != null && hasText(String.valueOf(value))) {
                    parts.add(key + "=" + String.valueOf(value).trim());
                }
            });
            return String.join(", ", parts);
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("一致性圣经属性序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private void putIfHasText(Map<String, Object> map, String key, Object value) {
        if (value != null && hasText(String.valueOf(value))) {
            map.put(key, String.valueOf(value).trim());
        }
    }

    private String trimToLength(String value, int maxChars) {
        if (!hasText(value) || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)).trim() + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
