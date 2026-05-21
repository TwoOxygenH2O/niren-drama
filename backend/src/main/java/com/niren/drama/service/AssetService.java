package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niren.drama.common.PageQuery;
import com.niren.drama.entity.Asset;
import com.niren.drama.mapper.AssetMapper;
import com.niren.drama.service.storage.StoredAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetMapper assetMapper;
    private final PublicAssetStorageService publicAssetStorageService;

    public Asset uploadFile(Long userId, Long projectId, MultipartFile file,
                            String refType, Long refId) throws IOException {
        String originalName = file.getOriginalFilename();
        String subDir = determineSubDir(file.getContentType());
        StoredAsset storedAsset = publicAssetStorageService.storeMultipartFile(file, subDir);

        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setProjectId(projectId);
        asset.setName(originalName);
        asset.setType(determineType(file.getContentType()));
        asset.setFilePath(storedAsset.filePath());
        asset.setUrl(storedAsset.publicUrl());
        asset.setFileSize(storedAsset.fileSize());
        asset.setMimeType(storedAsset.contentType());
        asset.setRefType(refType);
        asset.setRefId(refId);
        assetMapper.insert(asset);
        return asset;
    }

    public Page<Asset> listAssets(Long projectId, String type, PageQuery query) {
        Page<Asset> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .orderByDesc(Asset::getCreateTime);
        if (type != null) wrapper.eq(Asset::getType, type);
        return assetMapper.selectPage(page, wrapper);
    }

    public Asset getAsset(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null) {
            throw new com.niren.drama.exception.BusinessException("素材不存在");
        }
        return asset;
    }

    public void deleteAsset(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset != null) {
            publicAssetStorageService.deleteStoredAsset(asset.getFilePath(), asset.getUrl());
            assetMapper.deleteById(id);
        }
    }

    private String determineSubDir(String contentType) {
        if (contentType == null) return "other";
        if (contentType.startsWith("image/")) return "images";
        if (contentType.startsWith("video/")) return "videos";
        if (contentType.startsWith("audio/")) return "audios";
        return "other";
    }

    private String determineType(String contentType) {
        if (contentType == null) return "other";
        if (contentType.startsWith("image/")) return "image";
        if (contentType.startsWith("video/")) return "video";
        if (contentType.startsWith("audio/")) return "audio";
        return "other";
    }
}
