package com.niren.drama.ai;

import lombok.extern.slf4j.Slf4j;

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
public final class RemoteAssetStorage {

    private RemoteAssetStorage() {
    }

    public static String persistHttpUrl(String sourceUrl,
                                        String uploadPath,
                                        String publicBaseUrl,
                                        String subDir,
                                        HttpClient httpClient,
                                        String fallbackExtension) {
        if (!hasText(sourceUrl)
                || !isHttpUrl(sourceUrl)
                || !hasText(uploadPath)
                || !hasText(publicBaseUrl)
                || !hasText(subDir)) {
            return sourceUrl;
        }

        String normalizedBaseUrl = trimTrailingSlash(publicBaseUrl.trim());
        if (sourceUrl.startsWith(normalizedBaseUrl + "/")) {
            return sourceUrl;
        }

        try {
            Path targetDir = Paths.get(uploadPath, subDir);
            Files.createDirectories(targetDir);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(180))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (response.statusCode() >= 400 || bytes == null || bytes.length == 0) {
                throw new RuntimeException("HTTP " + response.statusCode() + " when downloading remote asset");
            }

            String extension = resolveExtension(
                    sourceUrl,
                    response.headers().firstValue("Content-Type").orElse(null),
                    fallbackExtension);
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
            Path targetFile = targetDir.resolve(fileName);
            Files.write(targetFile, bytes);
            return normalizedBaseUrl + "/" + trimSlashes(subDir) + "/" + fileName;
        } catch (Exception e) {
            log.warn("远程资源落盘失败，保留原始 URL: sourceUrl={}, reason={}", sourceUrl, e.getMessage());
            return sourceUrl;
        }
    }

    private static String resolveExtension(String sourceUrl, String contentType, String fallbackExtension) {
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedContentType.contains("png")) {
            return "png";
        }
        if (normalizedContentType.contains("jpeg") || normalizedContentType.contains("jpg")) {
            return "jpg";
        }
        if (normalizedContentType.contains("webp")) {
            return "webp";
        }
        if (normalizedContentType.contains("gif")) {
            return "gif";
        }
        if (normalizedContentType.contains("mpeg") || normalizedContentType.contains("mp3")) {
            return "mp3";
        }
        if (normalizedContentType.contains("wav")) {
            return "wav";
        }
        if (normalizedContentType.contains("mp4")) {
            return "mp4";
        }
        if (normalizedContentType.contains("quicktime") || normalizedContentType.contains("mov")) {
            return "mov";
        }
        if (normalizedContentType.contains("webm")) {
            return "webm";
        }

        if (hasText(sourceUrl)) {
            String cleanUrl = sourceUrl;
            int queryIndex = cleanUrl.indexOf('?');
            if (queryIndex >= 0) {
                cleanUrl = cleanUrl.substring(0, queryIndex);
            }
            int hashIndex = cleanUrl.indexOf('#');
            if (hashIndex >= 0) {
                cleanUrl = cleanUrl.substring(0, hashIndex);
            }
            int lastDot = cleanUrl.lastIndexOf('.');
            int lastSlash = Math.max(cleanUrl.lastIndexOf('/'), cleanUrl.lastIndexOf('\\'));
            if (lastDot > lastSlash && lastDot >= 0 && lastDot + 1 < cleanUrl.length()) {
                String extension = cleanUrl.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                if (extension.length() <= 5) {
                    return extension;
                }
            }
        }

        return hasText(fallbackExtension) ? fallbackExtension : "bin";
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}