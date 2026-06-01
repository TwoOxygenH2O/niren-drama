package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niren.drama.entity.AiConfig;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.AiConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiConfigService {

    private final AiConfigMapper aiConfigMapper;
    private final AiConfigSecretCodec secretCodec;

    public List<AiConfig> listByUser(Long userId) {
        return aiConfigMapper.selectList(new LambdaQueryWrapper<AiConfig>()
                        .eq(AiConfig::getUserId, userId)
                        .orderByAsc(AiConfig::getConfigType))
                .stream()
                .map(this::toClientView)
                .toList();
    }

    public AiConfig getDefaultByType(Long userId, String configType) {
        AiConfig config = aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getUserId, userId)
                .eq(AiConfig::getConfigType, configType)
                .eq(AiConfig::getIsDefault, 1)
                .last("LIMIT 1"));
        return decryptForUse(config);
    }

    public AiConfig saveConfig(Long userId, AiConfig config) {
        config.setUserId(userId);
        AiConfig existing = null;
        if (config.getId() != null) {
            existing = aiConfigMapper.selectById(config.getId());
            if (existing == null || !userId.equals(existing.getUserId())) {
                throw new BusinessException("配置不存在或无权操作");
            }
        }
        config.setApiKey(resolveApiKeyForStorage(config.getApiKey(), existing));

        if (Integer.valueOf(1).equals(config.getIsDefault())) {
            clearDefaults(userId, config.getConfigType());
        }
        if (config.getId() == null) {
            aiConfigMapper.insert(config);
        } else {
            aiConfigMapper.updateById(config);
        }
        return toClientView(config);
    }

    public void deleteConfig(Long userId, Long id) {
        AiConfig config = aiConfigMapper.selectById(id);
        if (config == null || !userId.equals(config.getUserId())) {
            throw new BusinessException("配置不存在或无权操作");
        }
        aiConfigMapper.deleteById(id);
    }

    public void setDefault(Long userId, Long id) {
        AiConfig config = aiConfigMapper.selectById(id);
        if (config == null || !userId.equals(config.getUserId())) {
            throw new BusinessException("配置不存在或无权操作");
        }
        clearDefaults(userId, config.getConfigType());
        config.setIsDefault(1);
        aiConfigMapper.updateById(config);
    }

    private void clearDefaults(Long userId, String configType) {
        AiConfig update = new AiConfig();
        update.setIsDefault(0);
        aiConfigMapper.update(update, new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getUserId, userId)
                .eq(AiConfig::getConfigType, configType));
    }

    private String resolveApiKeyForStorage(String incomingApiKey, AiConfig existing) {
        if (existing != null && (!hasText(incomingApiKey) || secretCodec.isMaskedValue(incomingApiKey))) {
            return existing.getApiKey();
        }
        return secretCodec.encrypt(incomingApiKey);
    }

    private AiConfig decryptForUse(AiConfig config) {
        if (config != null) {
            config.setApiKey(secretCodec.decrypt(config.getApiKey()));
        }
        return config;
    }

    private AiConfig toClientView(AiConfig source) {
        if (source == null) {
            return null;
        }
        AiConfig view = new AiConfig();
        view.setId(source.getId());
        view.setCreateTime(source.getCreateTime());
        view.setUpdateTime(source.getUpdateTime());
        view.setUserId(source.getUserId());
        view.setConfigType(source.getConfigType());
        view.setProvider(source.getProvider());
        view.setBaseUrl(source.getBaseUrl());
        view.setApiKey(secretCodec.mask(source.getApiKey()));
        view.setModel(source.getModel());
        view.setExtra(source.getExtra());
        view.setIsDefault(source.getIsDefault());
        return view;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
