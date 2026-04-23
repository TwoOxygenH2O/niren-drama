package com.niren.drama.service;

import com.niren.drama.config.TencentCosProperties;
import com.niren.drama.service.storage.StoredAsset;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublicAssetStorageService {

    private final TencentCosProperties cosProperties;
    private final ObjectProvider<COSClient> cosClientProvider;

    @Value("${niren.upload.path:./uploads}")
    private String uploadPath;

    @Value("${niren.upload.base-url:http://localhost:8080/api/files}")
    private String localBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public StoredAsset storeMultipartFile(MultipartFile file, String subDir) throws IOException {
        String originalName = file.getOriginalFilename();
        String contentType = file.getContentType();
        String fileName = buildFileName(originalName, contentType, null);
        if (isCosEnabled()) {
            try (InputStream inputStream = file.getInputStream()) {
                return uploadToCos(inputStream, file.getSize(), subDir, fileName, contentType, originalName);
            }
        }
        Path dir = Paths.get(uploadPath, trimSlashes(subDir));
        Files.createDirectories(dir);
        Path targetFile = dir.resolve(fileName);
        file.transferTo(targetFile.toFile());
        return new StoredAsset(
                trimTrailingSlash(localBaseUrl) + "/" + trimSlashes(subDir) + "/" + fileName,
                targetFile.toString(),
                trimSlashes(subDir) + "/" + fileName,
                file.getSize(),
                contentType,
                originalName);
    }

    public StoredAsset storeBytes(byte[] bytes,
                                  String subDir,
                                  String originalName,
                                  String contentType,
                                  String fallbackExtension) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("文件内容为空，无法上传素材");
        }
        String fileName = buildFileName(originalName, contentType, fallbackExtension);
        if (isCosEnabled()) {
            return uploadToCos(bytes, subDir, fileName, contentType, originalName);
        }
        return saveToLocal(bytes, subDir, fileName, contentType, originalName);
    }

    public StoredAsset storeLocalFile(Path sourceFile,
                                      String subDir,
                                      String originalName,
                                      String contentType) throws IOException {
        String fileName = buildFileName(originalName, contentType, extensionFromName(sourceFile.getFileName().toString()));
        if (isCosEnabled()) {
            try (InputStream inputStream = Files.newInputStream(sourceFile)) {
                return uploadToCos(inputStream, Files.size(sourceFile), subDir, fileName, contentType, originalName);
            }
        }
        Path dir = Paths.get(uploadPath, trimSlashes(subDir));
        Files.createDirectories(dir);
        Path targetFile = dir.resolve(fileName);
        if (!targetFile.equals(sourceFile)) {
            Files.copy(sourceFile, targetFile);
        }
        return new StoredAsset(
                trimTrailingSlash(localBaseUrl) + "/" + trimSlashes(subDir) + "/" + fileName,
                targetFile.toString(),
                trimSlashes(subDir) + "/" + fileName,
                Files.size(targetFile),
                contentType,
                originalName);
    }

    public String ensurePublicUrl(String source,
                                  String subDir,
                                  String fallbackExtension) {
        if (!hasText(source)) {
            return source;
        }
        if (isCosPublicUrl(source)) {
            return source;
        }
        try {
            Path localPath = resolveLocalPath(source);
            if (localPath != null && Files.exists(localPath)) {
                String contentType = Files.probeContentType(localPath);
                return storeLocalFile(localPath, subDir, localPath.getFileName().toString(), contentType).publicUrl();
            }
            if (isHttpUrl(source)) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(source))
                        .GET()
                        .timeout(Duration.ofSeconds(180))
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400 || response.body() == null || response.body().length == 0) {
                    throw new IOException("HTTP " + response.statusCode() + " when downloading remote asset");
                }
                return storeBytes(
                        response.body(),
                        subDir,
                        deriveNameFromUrl(source, fallbackExtension),
                        response.headers().firstValue("Content-Type").orElse(null),
                        fallbackExtension).publicUrl();
            }
            Path sourcePath = Paths.get(source);
            if (Files.exists(sourcePath)) {
                String contentType = Files.probeContentType(sourcePath);
                return storeLocalFile(sourcePath, subDir, sourcePath.getFileName().toString(), contentType).publicUrl();
            }
        } catch (Exception e) {
            log.warn("Failed to convert asset to public URL {}, keep original: {}", source, e.getMessage());
        }
        return source;
    }

    public void deleteStoredAsset(String filePath, String publicUrl) {
        try {
            if (hasText(filePath) && filePath.startsWith("cos://") && isCosEnabled()) {
                String objectKey = filePath.substring(("cos://" + cosProperties.getBucket() + "/").length());
                COSClient cosClient = cosClientProvider.getIfAvailable();
                if (cosClient != null) {
                    cosClient.deleteObject(cosProperties.getBucket(), objectKey);
                }
                return;
            }
            Path localPath = resolveLocalPath(hasText(filePath) ? filePath : publicUrl);
            if (localPath != null) {
                Files.deleteIfExists(localPath);
            }
        } catch (Exception e) {
            log.warn("Failed to delete stored asset: filePath={}, publicUrl={}", filePath, publicUrl, e);
        }
    }

    public boolean isLocalPublicUrl(String value) {
        return hasText(value) && value.startsWith(trimTrailingSlash(localBaseUrl) + "/");
    }

    public boolean isRemotePublicUrl(String value) {
        return isHttpUrl(value) && !isLocalPublicUrl(value);
    }

    private StoredAsset uploadToCos(byte[] bytes,
                                    String subDir,
                                    String fileName,
                                    String contentType,
                                    String originalName) {
        return uploadToCos(new ByteArrayInputStream(bytes), bytes.length, subDir, fileName, contentType, originalName);
    }

    private StoredAsset uploadToCos(InputStream inputStream,
                                    long contentLength,
                                    String subDir,
                                    String fileName,
                                    String contentType,
                                    String originalName) {
        COSClient cosClient = cosClientProvider.getIfAvailable();
        if (cosClient == null) {
            throw new IllegalStateException("COS 已启用，但 COSClient 未初始化");
        }
        String objectKey = buildObjectKey(subDir, fileName);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        if (hasText(contentType)) {
            metadata.setContentType(contentType);
        }
        PutObjectRequest request = new PutObjectRequest(
                cosProperties.getBucket(),
                objectKey,
                inputStream,
                metadata);
        cosClient.putObject(request);
        return new StoredAsset(
                buildCosPublicUrl(objectKey),
                "cos://" + cosProperties.getBucket() + "/" + objectKey,
                objectKey,
                contentLength,
                contentType,
                originalName);
    }

    private StoredAsset saveToLocal(byte[] bytes,
                                    String subDir,
                                    String fileName,
                                    String contentType,
                                    String originalName) throws IOException {
        Path dir = Paths.get(uploadPath, trimSlashes(subDir));
        Files.createDirectories(dir);
        Path targetFile = dir.resolve(fileName);
        Files.write(targetFile, bytes);
        return new StoredAsset(
                trimTrailingSlash(localBaseUrl) + "/" + trimSlashes(subDir) + "/" + fileName,
                targetFile.toString(),
                trimSlashes(subDir) + "/" + fileName,
                bytes.length,
                contentType,
                originalName);
    }

    private Path resolveLocalPath(String filePathOrUrl) {
        if (!hasText(filePathOrUrl)) {
            return null;
        }
        if (isLocalPublicUrl(filePathOrUrl)) {
            String relativePath = filePathOrUrl.substring(trimTrailingSlash(localBaseUrl).length());
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            return Paths.get(uploadPath, relativePath);
        }
        if (filePathOrUrl.startsWith("cos://") || isCosPublicUrl(filePathOrUrl) || isHttpUrl(filePathOrUrl)) {
            return null;
        }
        try {
            return Paths.get(filePathOrUrl);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCosEnabled() {
        return cosProperties.isEnabled()
                && hasText(cosProperties.getSecretId())
                && hasText(cosProperties.getSecretKey())
                && hasText(cosProperties.getBucket());
    }

    private boolean isCosPublicUrl(String value) {
        return hasText(value) && hasText(buildCosPublicBaseUrl()) && value.startsWith(buildCosPublicBaseUrl() + "/");
    }

    private String buildCosPublicUrl(String objectKey) {
        return buildCosPublicBaseUrl() + "/" + trimSlashes(objectKey);
    }

    private String buildCosPublicBaseUrl() {
        if (hasText(cosProperties.getPublicBaseUrl())) {
            return trimTrailingSlash(cosProperties.getPublicBaseUrl().trim());
        }
        if (!hasText(cosProperties.getBucket()) || !hasText(cosProperties.getRegion())) {
            return "";
        }
        return "https://" + cosProperties.getBucket() + ".cos." + cosProperties.getRegion() + ".myqcloud.com";
    }

    private String buildObjectKey(String subDir, String fileName) {
        StringBuilder builder = new StringBuilder();
        if (hasText(cosProperties.getBasePath())) {
            builder.append(trimSlashes(cosProperties.getBasePath())).append('/');
        }
        if (hasText(subDir)) {
            builder.append(trimSlashes(subDir)).append('/');
        }
        builder.append(fileName);
        return builder.toString();
    }

    private String buildFileName(String originalName, String contentType, String fallbackExtension) {
        String extension = extensionFromName(originalName);
        if (!hasText(extension)) {
            extension = extensionFromContentType(contentType);
        }
        if (!hasText(extension)) {
            extension = hasText(fallbackExtension) ? trimLeadingDot(fallbackExtension) : "bin";
        }
        return UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    private String deriveNameFromUrl(String sourceUrl, String fallbackExtension) {
        String extension = extensionFromName(sourceUrl);
        if (!hasText(extension)) {
            extension = hasText(fallbackExtension) ? trimLeadingDot(fallbackExtension) : "bin";
        }
        return UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    private String extensionFromName(String value) {
        if (!hasText(value)) {
            return null;
        }
        String clean = value;
        int queryIndex = clean.indexOf('?');
        if (queryIndex >= 0) {
            clean = clean.substring(0, queryIndex);
        }
        int hashIndex = clean.indexOf('#');
        if (hashIndex >= 0) {
            clean = clean.substring(0, hashIndex);
        }
        int lastDot = clean.lastIndexOf('.');
        int lastSlash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (lastDot > lastSlash && lastDot + 1 < clean.length()) {
            return trimLeadingDot(clean.substring(lastDot + 1));
        }
        return null;
    }

    private String extensionFromContentType(String contentType) {
        if (!hasText(contentType)) {
            return null;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("png")) return "png";
        if (normalized.contains("jpeg") || normalized.contains("jpg")) return "jpg";
        if (normalized.contains("webp")) return "webp";
        if (normalized.contains("gif")) return "gif";
        if (normalized.contains("mpeg") || normalized.contains("mp3")) return "mp3";
        if (normalized.contains("wav")) return "wav";
        if (normalized.contains("mp4")) return "mp4";
        if (normalized.contains("quicktime") || normalized.contains("mov")) return "mov";
        if (normalized.contains("webm")) return "webm";
        return null;
    }

    private boolean isHttpUrl(String value) {
        return hasText(value) && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String trimSlashes(String value) {
        if (!hasText(value)) {
            return "";
        }
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String trimLeadingDot(String value) {
        if (!hasText(value)) {
            return value;
        }
        return value.startsWith(".") ? value.substring(1) : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}