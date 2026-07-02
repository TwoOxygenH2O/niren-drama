package com.niren.drama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niren.drama.entity.TaskRecord;
import com.niren.drama.mapper.TaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WanLoraTrainingRunner {

    private final TaskRecordMapper taskRecordMapper;
    private final ObjectMapper objectMapper;

    @Value("${niren.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${niren.training.wan22.trainer-root:}")
    private String trainerRoot;

    @Value("${niren.training.wan22.accelerate-command:accelerate}")
    private String accelerateCommand;

    @Value("${niren.training.wan22.model-paths-json:}")
    private String modelPathsJson;

    @Value("${niren.training.wan22.command-template:}")
    private String commandTemplate;

    @Value("${niren.training.wan22.dataset-repeat:5}")
    private int datasetRepeat;

    @Value("${niren.training.wan22.dataset-workers:2}")
    private int datasetWorkers;

    @Value("${niren.training.wan22.learning-rate:0.0001}")
    private String learningRate;

    @Value("${niren.training.wan22.output-dir:wan22-video-training/checkpoints}")
    private String outputDir;

    @Async("aiTaskExecutor")
    public void startTraining(WanLoraTrainingService.TrainingContext context) {
        Path logFile = context.runDir().resolve("logs").resolve("train.log");
        Path manifestPath = context.runDir().resolve("manifest").resolve("wan22_shot_manifest.csv");
        Path datasetBase = context.runDir().resolve("processed").resolve("wan_dataset");
        Path outputPath = resolveProjectPath(outputDir)
                .resolve(context.runId() + ".safetensors")
                .toAbsolutePath()
                .normalize();
        String commandLine = "";
        try {
            updateTask(context.taskId(), "RUNNING", 8, "正在创建 Wan2.2 LoRA 训练数据集...", null);
            Files.createDirectories(logFile.getParent());
            Files.createDirectories(manifestPath.getParent());
            Files.createDirectories(datasetBase.resolve("videos"));
            Files.createDirectories(datasetBase.resolve("first_frames"));
            Files.createDirectories(outputPath.getParent());

            writeLog(logFile, "Run " + context.runId() + " started at " + LocalDateTime.now());
            List<ManifestRow> rows = prepareDataset(context, datasetBase, logFile);
            writeManifest(rows, manifestPath);
            updateTask(context.taskId(), "RUNNING", 30, "训练数据集准备完成，正在启动 Wan2.2 LoRA 训练...", resultPayload(context, manifestPath, datasetBase, outputPath, commandLine, logFile, "dataset-ready", null));

            if (!hasText(commandTemplate) && !hasText(trainerRoot)) {
                throw new IllegalStateException("Wan2.2 trainer root is not configured. Set NIREN_WAN22_TRAINER_ROOT or NIREN_WAN22_COMMAND_TEMPLATE.");
            }

            Path trainerRootPath = hasText(trainerRoot)
                    ? resolveProjectPath(trainerRoot)
                    : Paths.get("").toAbsolutePath().normalize();
            Path trainScript = trainerRootPath.resolve("examples").resolve("wanvideo").resolve("model_training").resolve("train.py");
            if (!Files.exists(trainScript) && !hasText(commandTemplate)) {
                throw new IllegalStateException("未找到 DiffSynth-Studio 训练脚本，请安装到 " + trainerRootPath);
            }

            commandLine = buildCommandLine(context, trainerRootPath, manifestPath, datasetBase, outputPath);
            writeLog(logFile, "Command: " + commandLine);
            updateTask(context.taskId(), "RUNNING", 38, "训练进程已启动，正在写入日志...", resultPayload(context, manifestPath, datasetBase, outputPath, commandLine, logFile, "running", null));

            int exitCode = runShellCommand(commandLine, trainerRootPath, logFile, context.taskId());
            if (exitCode != 0) {
                throw new IllegalStateException("Wan2.2 LoRA 训练进程退出码: " + exitCode);
            }
            if (!Files.exists(outputPath)) {
                throw new IllegalStateException("训练进程已结束，但未找到 LoRA 权重: " + outputPath);
            }
            updateTask(context.taskId(), "SUCCESS", 100, "Wan2.2 LoRA 训练完成，权重已生成",
                    resultPayload(context, manifestPath, datasetBase, outputPath, commandLine, logFile, "success", null));
        } catch (Exception e) {
            log.warn("Wan2.2 LoRA training failed: taskId={}, runId={}", context.taskId(), context.runId(), e);
            try {
                writeLog(logFile, "FAILED: " + e.getMessage());
            } catch (IOException ignored) {
                // ignore logging failure
            }
            updateTask(context.taskId(), "FAILED", 100, "Wan2.2 LoRA 训练失败: " + e.getMessage(),
                    resultPayload(context, manifestPath, datasetBase, outputPath, commandLine, logFile, "failed", e.getMessage()));
        }
    }

    private List<ManifestRow> prepareDataset(WanLoraTrainingService.TrainingContext context,
                                             Path datasetBase,
                                             Path logFile) throws Exception {
        List<ManifestRow> rows = new ArrayList<>();
        int index = 0;
        for (WanLoraTrainingService.TrainingSample sample : context.samples()) {
            index++;
            String sampleId = "sample_" + String.format("%04d", index);
            Path videoTarget = datasetBase.resolve("videos").resolve(sampleId + extension(sample.videoPath(), ".mp4"));
            Path frameTarget = datasetBase.resolve("first_frames").resolve(sampleId + ".png");
            Files.copy(sample.videoPath(), videoTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            extractFirstFrame(videoTarget, frameTarget, logFile);
            rows.add(new ManifestRow(
                    sampleId,
                    "train",
                    datasetBase.relativize(frameTarget).toString().replace('\\', '/'),
                    datasetBase.relativize(videoTarget).toString().replace('\\', '/'),
                    hasText(sample.prompt()) ? sample.prompt() : context.caption(),
                    hasText(sample.negativePrompt())
                            ? sample.negativePrompt()
                            : "no cuts, no new people, no wardrobe change, no scene jump, no face morphing, no slideshow",
                    context.workflowFile(),
                    context.baseModel() == null ? "" : context.baseModel()));
            updateTask(context.taskId(), "RUNNING", Math.min(28, 10 + index * 18 / Math.max(1, context.samples().size())),
                    String.format(Locale.ROOT, "正在处理训练素材 %d/%d", index, context.samples().size()), null);
        }
        return rows;
    }

    private void extractFirstFrame(Path videoPath, Path framePath, Path logFile) throws Exception {
        List<String> parts = List.of(
                ffmpegPath,
                "-y",
                "-i",
                videoPath.toString(),
                "-frames:v",
                "1",
                "-update",
                "1",
                framePath.toString());
        int exitCode = runProcess(parts, videoPath.getParent(), logFile, null);
        if (exitCode != 0 || !Files.exists(framePath)) {
            throw new IllegalStateException("抽取视频首帧失败: " + videoPath.getFileName());
        }
    }

    private void writeManifest(List<ManifestRow> rows, Path manifestPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(manifestPath, StandardCharsets.UTF_8)) {
            writer.write("sample_id,split,input_image,video,prompt,negative_prompt,workflow_file,base_model,quality_label,usable_for_lora,license_status,notes");
            writer.newLine();
            for (ManifestRow row : rows) {
                writer.write(String.join(",",
                        csv(row.sampleId()),
                        csv(row.split()),
                        csv(row.inputImage()),
                        csv(row.video()),
                        csv(row.prompt()),
                        csv(row.negativePrompt()),
                        csv(row.workflowFile()),
                        csv(row.baseModel()),
                        csv("ok"),
                        csv("true"),
                        csv("confirmed"),
                        csv("uploaded from AI config training dialog")));
                writer.newLine();
            }
        }
    }

    private String buildCommandLine(WanLoraTrainingService.TrainingContext context,
                                    Path trainerRootPath,
                                    Path manifestPath,
                                    Path datasetBase,
                                    Path outputPath) {
        if (hasText(commandTemplate)) {
            return replacePlaceholders(commandTemplate, context, manifestPath, datasetBase, outputPath);
        }
        if (!hasText(modelPathsJson)) {
            throw new IllegalStateException("Wan2.2 model paths are not configured. Set NIREN_WAN22_MODEL_PATHS_JSON or NIREN_WAN22_COMMAND_TEMPLATE.");
        }
        int height = context.lowVram() ? 832 : 1280;
        int width = context.lowVram() ? 480 : 720;
        int frames = context.lowVram() ? 81 : 121;
        List<String> parts = new ArrayList<>();
        parts.add(accelerateCommand);
        parts.add("launch");
        parts.add("examples/wanvideo/model_training/train.py");
        parts.add("--dataset_base_path");
        parts.add(datasetBase.toString());
        parts.add("--dataset_metadata_path");
        parts.add(manifestPath.toString());
        parts.add("--data_file_keys");
        parts.add("video,input_image");
        parts.add("--dataset_repeat");
        parts.add(String.valueOf(Math.max(1, datasetRepeat)));
        parts.add("--dataset_num_workers");
        parts.add(String.valueOf(Math.max(0, datasetWorkers)));
        parts.add("--model_paths");
        parts.add(modelPathsJson);
        parts.add("--learning_rate");
        parts.add(learningRate);
        parts.add("--num_epochs");
        parts.add(String.valueOf(context.epochs()));
        parts.add("--trainable_models");
        parts.add("");
        parts.add("--lora_base_model");
        parts.add("dit");
        parts.add("--lora_target_modules");
        parts.add("to_q,to_k,to_v");
        parts.add("--lora_rank");
        parts.add(String.valueOf(context.loraRank()));
        parts.add("--height");
        parts.add(String.valueOf(height));
        parts.add("--width");
        parts.add(String.valueOf(width));
        parts.add("--num_frames");
        parts.add(String.valueOf(frames));
        parts.add("--use_gradient_checkpointing");
        if (context.lowVram()) {
            parts.add("--use_gradient_checkpointing_offload");
            parts.add("--enable_model_cpu_offload");
            parts.add("--fp8_models");
            parts.add("text_encoder,vae");
        }
        parts.add("--find_unused_parameters");
        parts.add("--output_path");
        parts.add(outputPath.toString());
        return "cd " + quote(trainerRootPath.toString()) + " && " + buildShellCommand(parts);
    }

    private String replacePlaceholders(String template,
                                       WanLoraTrainingService.TrainingContext context,
                                       Path manifestPath,
                                       Path datasetBase,
                                       Path outputPath) {
        return template
                .replace("{runId}", context.runId())
                .replace("{manifestPath}", quote(manifestPath.toString()))
                .replace("{datasetBase}", quote(datasetBase.toString()))
                .replace("{outputPath}", quote(outputPath.toString()))
                .replace("{rank}", String.valueOf(context.loraRank()))
                .replace("{epochs}", String.valueOf(context.epochs()))
                .replace("{workflowFile}", context.workflowFile());
    }

    private int runShellCommand(String commandLine, Path workingDir, Path logFile, Long taskId) throws Exception {
        ProcessBuilder builder = isWindows()
                ? new ProcessBuilder("cmd.exe", "/c", commandLine)
                : new ProcessBuilder("bash", "-lc", commandLine);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                writeLog(logFile, line);
                if (taskId != null && lineCount % 20 == 0) {
                    int progress = Math.min(95, 38 + lineCount / 20);
                    updateTask(taskId, "RUNNING", progress, "Wan2.2 LoRA 训练进行中，日志持续写入...", null);
                }
            }
        }
        return process.waitFor();
    }

    private int runProcess(List<String> command, Path workingDir, Path logFile, Long taskId) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                writeLog(logFile, line);
                if (taskId != null && lineCount % 20 == 0) {
                    updateTask(taskId, "RUNNING", 25, "正在抽取训练视频首帧...", null);
                }
            }
        }
        return process.waitFor();
    }

    private Map<String, Object> resultPayload(WanLoraTrainingService.TrainingContext context,
                                              Path manifestPath,
                                              Path datasetBase,
                                              Path outputPath,
                                              String command,
                                              Path logFile,
                                              String phase,
                                              String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", context.runId());
        payload.put("configId", context.configId());
        payload.put("workflowFile", context.workflowFile());
        payload.put("baseModel", context.baseModel());
        payload.put("sampleCount", context.samples().size());
        payload.put("loraRank", context.loraRank());
        payload.put("epochs", context.epochs());
        payload.put("lowVram", context.lowVram());
        payload.put("phase", phase);
        payload.put("manifestPath", manifestPath.toString());
        payload.put("datasetBasePath", datasetBase.toString());
        payload.put("outputPath", outputPath.toString());
        payload.put("logPath", logFile.toString());
        payload.put("command", command);
        if (errorMessage != null && !errorMessage.isBlank()) {
            payload.put("errorMessage", errorMessage);
        }
        return payload;
    }

    private void updateTask(Long taskId, String status, Integer progress, String message, Map<String, Object> result) {
        if (taskId == null) {
            return;
        }
        TaskRecord update = new TaskRecord();
        update.setId(taskId);
        update.setStatus(status);
        update.setProgress(progress);
        update.setMessage(message);
        if (result != null) {
            try {
                update.setResult(objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                update.setResult("{\"errorMessage\":\"result serialization failed\"}");
            }
        }
        taskRecordMapper.updateById(update);
    }

    private void writeLog(Path logFile, String line) throws IOException {
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private Path resolveProjectPath(String value) {
        Path configured = Paths.get(value);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path projectRoot = findProjectRoot(cwd);
        if (projectRoot != null) {
            return projectRoot.resolve(configured).normalize();
        }
        return cwd.resolve(configured).normalize();
    }

    private Path findProjectRoot(Path cwd) {
        Path current = cwd;
        while (current != null) {
            if (Files.exists(current.resolve("AGENTS.md"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private String buildShellCommand(List<String> parts) {
        return parts.stream().map(this::quote).reduce((a, b) -> a + " " + b).orElse("");
    }

    private String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        if (isWindows()) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String csv(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private String extension(Path path, String fallback) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : fallback;
    }

    private record ManifestRow(String sampleId,
                               String split,
                               String inputImage,
                               String video,
                               String prompt,
                               String negativePrompt,
                               String workflowFile,
                               String baseModel) {
    }
}
