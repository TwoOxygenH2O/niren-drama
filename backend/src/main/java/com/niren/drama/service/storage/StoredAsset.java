package com.niren.drama.service.storage;

public record StoredAsset(
        String publicUrl,
        String filePath,
        String objectKey,
        long fileSize,
        String contentType,
        String originalName) {
}