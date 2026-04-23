package com.niren.drama.service;

import com.niren.drama.ai.AiProviderFactory;
import com.niren.drama.dto.video.ReferenceVideoTaskResponse;
import com.niren.drama.dto.video.ReferenceVideoTaskStatusResponse;
import com.niren.drama.entity.Asset;
import com.niren.drama.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ReferenceVideoService {

    private final AssetService assetService;
    private final PublicAssetStorageService publicAssetStorageService;
    private final AiVideoGenerationService aiVideoGenerationService;
    private final AiProviderFactory aiProviderFactory;

    public ReferenceVideoTaskResponse submit(Long userId,
                                             Long projectId,
                                             MultipartFile file,
                                             String referenceImageUrl,
                                             String prompt,
                                             Integer duration) throws Exception {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException("prompt 不能为空");
        }
        String publicReferenceImageUrl = resolveReferenceImageUrl(userId, projectId, file, referenceImageUrl);
        if (publicReferenceImageUrl == null || publicReferenceImageUrl.isBlank()) {
            throw new BusinessException("参考图不能为空，请上传图片或提供线上图片地址");
        }

        AiVideoGenerationService.VideoTaskSubmission submission = aiVideoGenerationService.submitReferenceVideoTask(
                userId,
                prompt,
                publicReferenceImageUrl,
                duration);

        ReferenceVideoTaskResponse response = new ReferenceVideoTaskResponse();
        response.setProvider(submission.provider());
        response.setModel(aiProviderFactory.resolveConfig(userId, "video").model());
        response.setPrompt(prompt);
        response.setReferenceImageUrl(publicReferenceImageUrl);
        response.setTaskId(submission.taskId());
        response.setStatusUrl(submission.statusUrl());
        response.setVideoUrl(submission.videoUrl());
        return response;
    }

    public ReferenceVideoTaskStatusResponse query(Long userId, String taskId, String statusUrl) {
        AiVideoGenerationService.VideoTaskQueryResult result = aiVideoGenerationService.queryReferenceVideoTask(userId, taskId, statusUrl);
        ReferenceVideoTaskStatusResponse response = new ReferenceVideoTaskStatusResponse();
        response.setProvider(result.provider());
        response.setTaskId(taskId);
        response.setStatusUrl(statusUrl);
        response.setStatus(result.status());
        response.setVideoUrl(result.videoUrl());
        response.setErrorMessage(result.errorMessage());
        return response;
    }

    private String resolveReferenceImageUrl(Long userId,
                                            Long projectId,
                                            MultipartFile file,
                                            String referenceImageUrl) throws Exception {
        if (file != null && !file.isEmpty()) {
            if (projectId != null) {
                Asset asset = assetService.uploadFile(userId, projectId, file, "reference_video", null);
                return asset.getUrl();
            }
            return publicAssetStorageService.storeMultipartFile(file, "reference-images").publicUrl();
        }
        return publicAssetStorageService.ensurePublicUrl(referenceImageUrl, "reference-images", "png");
    }
}