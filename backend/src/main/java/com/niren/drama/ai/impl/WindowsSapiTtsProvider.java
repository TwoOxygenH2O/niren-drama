package com.niren.drama.ai.impl;

import com.niren.drama.ai.TtsProvider;
import com.niren.drama.ai.VoiceInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Local Windows SAPI TTS fallback for development and offline generation.
 */
@Slf4j
public class WindowsSapiTtsProvider implements TtsProvider {

    private static final int TIMEOUT_SECONDS = 90;

    @Override
    public byte[] synthesize(String text, String voiceId, float speed, float pitch) {
        return synthesize(text, voiceId, speed, pitch, null, null);
    }

    @Override
    public byte[] synthesize(String text,
                             String voiceId,
                             float speed,
                             float pitch,
                             String instruction,
                             String languageType) {
        if (!isSupported()) {
            throw new RuntimeException("Windows SAPI TTS is only available on Windows with powershell.exe");
        }
        String normalizedText = text != null ? text.trim() : "";
        if (normalizedText.isBlank()) {
            throw new RuntimeException("TTS text is empty");
        }
        Path output = null;
        try {
            output = Files.createTempFile("niren-sapi-tts-", ".wav");
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-EncodedCommand",
                    encodePowerShell(buildSapiScript()));
            builder.redirectErrorStream(true);
            builder.environment().put("NIREN_TTS_TEXT", normalizedText);
            builder.environment().put("NIREN_TTS_VOICE", resolveSapiVoiceName(voiceId));
            builder.environment().put("NIREN_TTS_RATE", String.valueOf(resolveSapiRate(speed)));
            builder.environment().put("NIREN_TTS_OUT", output.toAbsolutePath().toString());

            Process process = builder.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            byte[] console = process.getInputStream().readAllBytes();
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Windows SAPI TTS timed out after " + TIMEOUT_SECONDS + "s");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("Windows SAPI TTS failed: " + new String(console, StandardCharsets.UTF_8));
            }
            byte[] audio = Files.readAllBytes(output);
            log.debug("Windows SAPI TTS 合成成功: voiceId={}, textLength={}, audioSize={}",
                    voiceId, normalizedText.length(), audio.length);
            return audio;
        } catch (IOException e) {
            throw new RuntimeException("Windows SAPI TTS failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Windows SAPI TTS interrupted", e);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public List<VoiceInfo> listVoices() {
        return List.of(
                new VoiceInfo("zh_female_zhubo", "本机中文女旁白", "female", "zh-CN", "Windows SAPI 中文女声，适合小说旁白"),
                new VoiceInfo("zh_female_qingxin", "本机清晰女声", "female", "zh-CN", "Windows SAPI 中文女声，清晰自然"),
                new VoiceInfo("zh_female_tianmei", "本机温柔女声", "female", "zh-CN", "Windows SAPI 中文女声，温柔克制"),
                new VoiceInfo("zh_male_zhubo", "本机中文男旁白", "male", "zh-CN", "Windows SAPI 中文男声，适合低沉旁白"),
                new VoiceInfo("Microsoft Huihui Desktop", "Microsoft Huihui Desktop", "female", "zh-CN", "Windows installed Chinese female voice"),
                new VoiceInfo("Microsoft Huihui", "Microsoft Huihui", "female", "zh-CN", "Windows installed Chinese female voice"),
                new VoiceInfo("Microsoft Yaoyao", "Microsoft Yaoyao", "female", "zh-CN", "Windows installed Chinese female voice"),
                new VoiceInfo("Microsoft Kangkang", "Microsoft Kangkang", "male", "zh-CN", "Windows installed Chinese male voice")
        );
    }

    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private String resolveSapiVoiceName(String voiceId) {
        String normalized = voiceId != null ? voiceId.trim() : "";
        if (normalized.toLowerCase(Locale.ROOT).startsWith("microsoft ")) {
            return normalized;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("male") || lower.contains("男") || lower.contains("kangkang")) {
            return "Microsoft Kangkang";
        }
        if (lower.contains("yaoyao") || lower.contains("甜") || lower.contains("tianmei")) {
            return "Microsoft Yaoyao";
        }
        return "Microsoft Huihui Desktop";
    }

    private int resolveSapiRate(float speed) {
        float normalized = speed > 0 ? speed : 1.0f;
        int rate = Math.round((normalized - 1.0f) * 8.0f);
        return Math.max(-6, Math.min(6, rate));
    }

    private String encodePowerShell(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private String buildSapiScript() {
        return """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Speech
                $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                try {
                    $voice = [Environment]::GetEnvironmentVariable('NIREN_TTS_VOICE')
                    $selected = $false
                    if ($voice) {
                        foreach ($installed in $synth.GetInstalledVoices()) {
                            if ($installed.VoiceInfo.Name -ieq $voice) {
                                $synth.SelectVoice($installed.VoiceInfo.Name)
                                $selected = $true
                                break
                            }
                        }
                    }
                    if (-not $selected) {
                        foreach ($candidate in @('Microsoft Huihui Desktop', 'Microsoft Huihui', 'Microsoft Yaoyao', 'Microsoft Kangkang')) {
                            foreach ($installed in $synth.GetInstalledVoices()) {
                                if ($installed.VoiceInfo.Name -ieq $candidate) {
                                    $synth.SelectVoice($installed.VoiceInfo.Name)
                                    $selected = $true
                                    break
                                }
                            }
                            if ($selected) { break }
                        }
                    }
                    $rateText = [Environment]::GetEnvironmentVariable('NIREN_TTS_RATE')
                    $rate = 0
                    if ([int]::TryParse($rateText, [ref]$rate)) {
                        $synth.Rate = [Math]::Max(-10, [Math]::Min(10, $rate))
                    }
                    $synth.Volume = 100
                    $synth.SetOutputToWaveFile([Environment]::GetEnvironmentVariable('NIREN_TTS_OUT'))
                    $synth.Speak([Environment]::GetEnvironmentVariable('NIREN_TTS_TEXT'))
                } finally {
                    $synth.Dispose()
                }
                """;
    }
}
