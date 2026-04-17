package com.niren.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niren.drama.common.PageQuery;
import com.niren.drama.entity.Asset;
import com.niren.drama.exception.BusinessException;
import com.niren.drama.mapper.AssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetMapper assetMapper;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String baseUrl;

    public Asset uploadFile(Long userId, Long projectId, MultipartFile file,
                            String refType, Long refId) throws IOException {
        String originalName = file.getOriginalFilename();
        String ext = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf("."))
                : "";
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;

        String subDir = determineSubDir(file.getContentType());
        Path dir = Paths.get(uploadPath, subDir);
        Files.createDirectories(dir);
        Path filePath = dir.resolve(filename);
        file.transferTo(filePath.toFile());

        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setProjectId(projectId);
        asset.setName(originalName);
        asset.setType(determineType(file.getContentType()));
        asset.setFilePath(filePath.toString());
        asset.setUrl(baseUrl + "/" + subDir + "/" + filename);
        asset.setFileSize(file.getSize());
        asset.setMimeType(file.getContentType());
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

    public void deleteAsset(Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset != null) {
            // Delete physical file
            if (asset.getFilePath() != null) {
                try {
                    Files.deleteIfExists(Paths.get(asset.getFilePath()));
                } catch (IOException e) {
                    log.warn("Failed to delete file: {}", asset.getFilePath());
                }
            }
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
