package com.niren.drama.ai;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        return persistHttpUrl(sourceUrl, uploadPath, publicBaseUrl, subDir, httpClient, fallbackExtension, false);
    }

    public static String persistHttpUrlStrict(String sourceUrl,
                                              String uploadPath,
                                              String publicBaseUrl,
                                              String subDir,
                                              HttpClient httpClient,
                                              String fallbackExtension) {
        return persistHttpUrl(sourceUrl, uploadPath, publicBaseUrl, subDir, httpClient, fallbackExtension, true);
    }

    private static String persistHttpUrl(String sourceUrl,
                                         String uploadPath,
                                         String publicBaseUrl,
                                         String subDir,
                                         HttpClient httpClient,
                                         String fallbackExtension,
                                         boolean failOnError) {
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

        Path targetFile = null;
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
            targetFile = targetDir.resolve(fileName);
            Files.write(targetFile, bytes);
            if ("mp4".equals(extension)) {
                normalizeMp4ForPlayback(targetFile);
            }
            return normalizedBaseUrl + "/" + trimSlashes(subDir) + "/" + fileName;
        } catch (Exception e) {
            if (targetFile != null) {
                try {
                    Files.deleteIfExists(targetFile);
                } catch (Exception cleanupError) {
                    log.warn("远程资源落盘失败后的临时文件清理失败: file={}, reason={}", targetFile, cleanupError.getMessage());
                }
            }
            if (failOnError) {
                throw new RuntimeException("远程资源落盘失败: " + sourceUrl + "，原因: " + e.getMessage(), e);
            }
            log.warn("远程资源落盘失败，保留原始 URL: sourceUrl={}, reason={}", sourceUrl, e.getMessage());
            return sourceUrl;
        }
    }

    private static void normalizeMp4ForPlayback(Path targetFile) throws IOException, InterruptedException {
        Path normalized = Files.createTempFile(targetFile.getParent(), "niren-video-", ".mp4");
        boolean normalizedWritten = false;
        try {
            runFfmpeg(
                    List.of(
                            "-hide_banner",
                            "-y",
                            "-v",
                            "error",
                            "-i",
                            targetFile.toString(),
                            "-map",
                            "0",
                            "-c",
                            "copy",
                            "-map_metadata",
                            "-1",
                            "-movflags",
                            "+faststart",
                            normalized.toString()),
                    "normalize mp4");
            if (!Files.exists(normalized) || Files.size(normalized) == 0) {
                throw new IOException("ffmpeg did not produce a normalized mp4");
            }
            validateMp4Playback(normalized);
            Files.move(normalized, targetFile, StandardCopyOption.REPLACE_EXISTING);
            normalizedWritten = true;
        } finally {
            if (!normalizedWritten) {
                Files.deleteIfExists(normalized);
            }
        }
    }

    private static void validateMp4Playback(Path file) throws IOException, InterruptedException {
        runFfmpeg(
                List.of(
                        "-hide_banner",
                        "-v",
                        "error",
                        "-i",
                        file.toString(),
                        "-f",
                        "null",
                        nullDevice()),
                "validate mp4");
    }

    private static void runFfmpeg(List<String> arguments, String action) throws IOException, InterruptedException {
        List<String> failures = new ArrayList<>();
        for (String executable : ffmpegCandidates()) {
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            try {
                Process process = builder.start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                process.getInputStream().transferTo(output);
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    return;
                }
                failures.add(executable + " exit " + exitCode + ": " + abbreviate(output.toString(), 600));
            } catch (IOException e) {
                failures.add(executable + ": " + e.getMessage());
            }
        }
        throw new IOException("ffmpeg " + action + " failed; " + String.join(" | ", failures));
    }

    private static List<String> ffmpegCandidates() {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, System.getProperty("niren.ffmpeg.path"));
        addCandidate(candidates, System.getProperty("ffmpeg.path"));
        addCandidate(candidates, System.getenv("FFMPEG_PATH"));
        addCandidate(candidates, System.getenv("NIREN_FFMPEG_PATH"));
        addCandidate(candidates, isWindows() ? "ffmpeg.exe" : "ffmpeg");
        if (isWindows()) {
            addCandidate(candidates, "D:\\javaSoftware\\ffmpeg_full\\bin\\ffmpeg.exe");
            addCandidate(candidates, "C:\\ffmpeg\\bin\\ffmpeg.exe");
        }
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if (!hasText(candidate)) {
            return;
        }
        String normalized = trimQuotes(candidate.trim());
        if (!hasText(normalized) || candidates.contains(normalized)) {
            return;
        }
        candidates.add(normalized);
    }

    private static String nullDevice() {
        return isWindows() ? "NUL" : "/dev/null";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String trimQuotes(String value) {
        if (!hasText(value)) {
            return value;
        }
        String result = value;
        while ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
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
