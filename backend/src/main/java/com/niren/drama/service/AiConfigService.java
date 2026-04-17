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

    public List<AiConfig> listByUser(Long userId) {
        return aiConfigMapper.selectList(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getUserId, userId)
                .orderByAsc(AiConfig::getConfigType));
    }

    public AiConfig saveConfig(Long userId, AiConfig config) {
        config.setUserId(userId);
        // If set as default, clear other defaults of same type
        if (Integer.valueOf(1).equals(config.getIsDefault())) {
            clearDefaults(userId, config.getConfigType());
        }
        if (config.getId() == null) {
            aiConfigMapper.insert(config);
        } else {
            AiConfig existing = aiConfigMapper.selectById(config.getId());
            if (existing == null || !userId.equals(existing.getUserId())) {
                throw new BusinessException("配置不存在或无权操作");
            }
            aiConfigMapper.updateById(config);
        }
        return config;
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
}
