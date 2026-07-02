param(
    [string]$SourceDir = "output\public-service-refund-antifraud-v1",
    [string]$OutDir = "output\public-service-refund-antifraud-wan22",
    [string]$ComfyUrl = "http://127.0.0.1:8188",
    [string]$ComfyInput = "D:\Projects\ComfyUI-aki\ComfyUI\input",
    [string]$ComfyOutput = "D:\Projects\ComfyUI-aki\ComfyUI\output",
    [string]$PythonExe = "D:\Projects\ComfyUI-aki\python\python.exe",
    [int[]]$WanShotNumbers = @(2, 4, 5, 6),
    [switch]$ReuseWan,
    [switch]$SkipWan,
    [switch]$UseSapiTts,
    [string]$Ffmpeg = "ffmpeg",
    [string]$Ffprobe = "ffprobe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

$Root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$SourcePath = Join-Path $Root $SourceDir
$OutPath = Join-Path $Root $OutDir
$KeyframePath = Join-Path $OutPath "keyframes"
$WanClipPath = Join-Path $OutPath "wan_clips"
$VoicePath = Join-Path $OutPath "voice"
$ShotPath = Join-Path $OutPath "shots"
$WanWorkflowPath = Join-Path $Root "backend\src\main\resources\comfyui\workflows\video_wan2_2_14B_i2v.json"
$BgmPath = Join-Path $Root "backend\src\main\resources\assets\bgm_default.wav"
$ComfyInputSubdir = "niren_refund_antifraud_wan22"
$ComfyInputPath = Join-Path $ComfyInput $ComfyInputSubdir

New-Item -ItemType Directory -Force -Path $OutPath, $KeyframePath, $WanClipPath, $VoicePath, $ShotPath, $ComfyInputPath | Out-Null
Get-ChildItem -LiteralPath (Join-Path $SourcePath "keyframes") -Filter "*.png" | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $KeyframePath -Force
}

Add-Type -AssemblyName System.Speech
Add-Type -AssemblyName System.Net.Http

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Get-DurationSeconds {
    param([string]$Path)
    $raw = & $Ffprobe -v error -show_entries format=duration -of default=nk=1:nw=1 $Path
    return [double]::Parse(($raw | Select-Object -First 1), [Globalization.CultureInfo]::InvariantCulture)
}

function Format-AssTime {
    param([double]$Seconds)
    $ts = [TimeSpan]::FromSeconds([math]::Max(0, $Seconds))
    return "{0}:{1:00}:{2:00}.{3:00}" -f [int]$ts.TotalHours, $ts.Minutes, $ts.Seconds, [math]::Floor($ts.Milliseconds / 10)
}

function Format-SrtTime {
    param([double]$Seconds)
    $ts = [TimeSpan]::FromSeconds([math]::Max(0, $Seconds))
    return "{0:00}:{1:00}:{2:00},{3:000}" -f [int]$ts.TotalHours, $ts.Minutes, $ts.Seconds, $ts.Milliseconds
}

function Escape-Ass {
    param([string]$Text)
    return $Text.Replace("\", "\\").Replace("{", "\{").Replace("}", "\}").Replace("`r", "").Replace("`n", "\N")
}

function Split-SubtitleLine {
    param(
        [string]$Text,
        [int]$MaxChars = 15
    )
    $parts = New-Object System.Collections.Generic.List[string]
    $current = ""
    foreach ($ch in $Text.ToCharArray()) {
        $current += [string]$ch
        $isPunctuation = "，。？！、；：,.?!;:".Contains([string]$ch)
        if ($current.Length -ge $MaxChars -and ($isPunctuation -or $current.Length -ge ($MaxChars + 4))) {
            $parts.Add($current.Trim())
            $current = ""
        }
    }
    if ($current.Trim().Length -gt 0) {
        $parts.Add($current.Trim())
    }
    return ($parts -join "`n")
}

function New-ComfyClient {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(45)
    return $client
}

function Invoke-ComfyGetJson {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Url
    )
    $response = $Client.GetAsync($Url).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "ComfyUI GET failed $Url HTTP $([int]$response.StatusCode): $text"
    }
    return $text | ConvertFrom-Json
}

function Invoke-ComfyPostJson {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Url,
        [object]$Body
    )
    $json = $Body | ConvertTo-Json -Depth 40 -Compress
    $content = [System.Net.Http.StringContent]::new($json, $utf8NoBom, "application/json")
    $response = $Client.PostAsync($Url, $content).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "ComfyUI POST failed $Url HTTP $([int]$response.StatusCode): $text"
    }
    return $text | ConvertFrom-Json
}

function Find-ComfyOutputFile {
    param([object]$OutputNode)
    foreach ($prop in $OutputNode.PSObject.Properties) {
        $value = $prop.Value
        if ($null -eq $value) {
            continue
        }
        foreach ($item in @($value)) {
            if ($item.PSObject.Properties["filename"] -ne $null) {
                $file = Join-Path $ComfyOutput $item.filename
                if ($item.PSObject.Properties["subfolder"] -ne $null -and $item.subfolder) {
                    $file = Join-Path (Join-Path $ComfyOutput $item.subfolder) $item.filename
                }
                if (Test-Path -LiteralPath $file) {
                    return $file
                }
            }
        }
    }
    return $null
}

function Submit-Wan22Clip {
    param(
        [System.Net.Http.HttpClient]$Client,
        [hashtable]$Shot,
        [string]$ImagePath,
        [string]$OutputFile
    )
    $idx = "{0:00}" -f $Shot.No
    $inputName = "shot_${idx}_$($Shot.Keyframe).png"
    $inputTarget = Join-Path $ComfyInputPath $inputName
    Copy-Item -LiteralPath $ImagePath -Destination $inputTarget -Force
    $inputRel = "$ComfyInputSubdir/$inputName"

    $workflow = Get-Content -Raw -LiteralPath $WanWorkflowPath | ConvertFrom-Json -AsHashtable
    foreach ($nodeId in @("13", "14", "15")) {
        $workflow[$nodeId]["inputs"]["image"] = $inputRel
    }

    $workflow["12"]["inputs"]["positive_prompt"] = $Shot.WanPrompt
    $workflow["12"]["inputs"]["negative_prompt"] = "low quality, blurry, distorted, face morphing, identity drift, different person, changing clothes, new person, scene jump, camera cut, frozen frame, slideshow, whole-frame pan, flicker, jitter, subtitles, text, logo, watermark, sketch, line art, manga, monochrome, extra fingers, bad hands"
    $workflow["18"]["inputs"]["num_frames"] = 49
    $workflow["18"]["inputs"]["noise_aug_strength"] = 0.032
    $workflow["18"]["inputs"]["start_latent_strength"] = 0.97
    $workflow["18"]["inputs"]["end_latent_strength"] = 0.86
    $workflow["18"]["inputs"]["augment_empty_frames"] = 0.04
    foreach ($nodeId in @("21", "22")) {
        $workflow[$nodeId]["inputs"]["steps"] = 6
        $workflow[$nodeId]["inputs"]["cfg"] = 1.08
        $workflow[$nodeId]["inputs"]["seed"] = [int64](2606232000 + $Shot.No)
    }
    $workflow["21"]["inputs"]["end_step"] = 3
    $workflow["22"]["inputs"]["start_step"] = 3
    $workflow["24"]["inputs"]["frame_rate"] = 24
    $workflow["24"]["inputs"]["filename_prefix"] = "niren_refund_antifraud_wan_shot$idx"
    $workflow["24"]["inputs"]["crf"] = 18
    $workflow["24"]["inputs"]["save_output"] = $true

    $submit = Invoke-ComfyPostJson -Client $Client -Url "$ComfyUrl/prompt" -Body @{ prompt = $workflow; client_id = [guid]::NewGuid().ToString() }
    $promptId = $submit.prompt_id
    if (-not $promptId) {
        throw "ComfyUI did not return prompt_id for Wan shot $idx"
    }

    Write-Host "WAN_SHOT_$idx=$promptId"
    for ($i = 0; $i -lt 1800; $i += 5) {
        Start-Sleep -Seconds 5
        $history = Invoke-ComfyGetJson -Client $Client -Url "$ComfyUrl/history/$promptId"
        $entryProp = $history.PSObject.Properties[$promptId]
        if ($entryProp -eq $null) {
            continue
        }
        $entry = $entryProp.Value
        $status = $entry.status
        if ($status -and $status.status_str -and $status.status_str -ne "success") {
            $messages = $status.messages | ConvertTo-Json -Depth 10 -Compress
            throw "Wan shot $idx failed: $messages"
        }
        $outputNode = $entry.outputs.PSObject.Properties["24"].Value
        if ($outputNode -eq $null) {
            throw "Wan shot $idx finished without video output node"
        }
        $source = Find-ComfyOutputFile $outputNode
        if (-not $source) {
            throw "Wan shot $idx finished but output file was not found"
        }
        Copy-Item -LiteralPath $source -Destination $OutputFile -Force
        return [pscustomobject]@{
            shotNo = $Shot.No
            promptId = $promptId
            source = $source
            output = $OutputFile
        }
    }
    throw "Timed out waiting for Wan shot $idx ($promptId)"
}

function Speak-SapiLine {
    param(
        [string]$Text,
        [string]$Voice,
        [int]$Rate,
        [string]$Output
    )
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $installedNames = @($synth.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name })
    if ($installedNames -contains $Voice) {
        $synth.SelectVoice($Voice)
    } elseif ($installedNames -contains "Microsoft Huihui") {
        $synth.SelectVoice("Microsoft Huihui")
    }
    $synth.Rate = $Rate
    $synth.Volume = 100
    $synth.SetOutputToWaveFile($Output)
    $synth.Speak($Text)
    $synth.Dispose()
}

function New-VoiceLine {
    param(
        [hashtable]$Shot,
        [string]$Output
    )
    $idx = "{0:00}" -f $Shot.No
    $raw = Join-Path $VoicePath "voice_${idx}_raw.mp3"
    if (-not $UseSapiTts) {
        try {
            Remove-Item -LiteralPath $raw -Force -ErrorAction SilentlyContinue
            $edgeArgs = @(
                "-m", "edge_tts",
                "--voice=$($Shot.EdgeVoice)",
                "--rate=$($Shot.EdgeRate)",
                "--pitch=$($Shot.EdgePitch)",
                "--text=$($Shot.Line)",
                "--write-media=$raw"
            )
            & $PythonExe @edgeArgs
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $raw)) {
                throw "edge-tts failed for shot $idx"
            }
            & $Ffmpeg -y -i $raw -af "aresample=48000,highpass=f=65,acompressor=threshold=-20dB:ratio=1.7:attack=14:release=180,alimiter=limit=0.92,loudnorm=I=-16:TP=-1.5:LRA=12" -ac 1 -ar 48000 $Output
            if ($LASTEXITCODE -ne 0) {
                throw "ffmpeg voice postprocess failed for shot $idx"
            }
            return "edge-tts:$($Shot.EdgeVoice)"
        } catch {
            Write-Warning "Edge TTS failed for shot ${idx}; falling back to Windows SAPI. $($_.Exception.Message)"
        }
    }
    Speak-SapiLine -Text $Shot.Line -Voice $Shot.SapiVoice -Rate $Shot.SapiRate -Output $Output
    return "sapi:$($Shot.SapiVoice)"
}

$shots = @(
    @{
        No = 1; Duration = 6.0; Keyframe = "daughter_call"; Speaker = "旁白"; SapiVoice = "Microsoft Huihui Desktop"; SapiRate = -1
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-8%"; EdgePitch = "-2Hz"
        Title = "夜班来电"; Visual = "苏晴夜班间隙接到快递理赔电话。"
        Line = "夜班快结束，苏晴接到一个理赔电话。"
        Motion = "micro_push"; Tier = "B"
    },
    @{
        No = 2; Duration = 7.0; Keyframe = "daughter_call"; Speaker = "骗子"; SapiVoice = "Microsoft Kangkang"; SapiRate = 1
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "+2%"; EdgePitch = "-1Hz"
        Title = "退款诱饵"; Visual = "骗子声称血压仪破损，可十倍退款。"
        Line = "苏女士，血压仪破了。现在能退一千九百八。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese anti-fraud short-drama video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same young Chinese nurse Su Qing, same tied black hair, light blue scrub jacket and beige coat, same hospital break room lighting. She holds the phone near her face, listens to a suspicious refund call, eyes narrow, small breath, slight thumb movement, very subtle head turn, realistic human micro-expression, stable identity, locked camera, no scene change, no new person, no text in image."
    },
    @{
        No = 3; Duration = 6.4; Keyframe = "daughter_call"; Speaker = "苏晴"; SapiVoice = "Microsoft Huihui Desktop"; SapiRate = -1
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-6%"; EdgePitch = "-2Hz"
        Title = "心急"; Visual = "苏晴急着自己处理退款。"
        Line = "别折腾我爸了。流程发我，我自己弄。"
        Motion = "micro_hold"; Tier = "B"
    },
    @{
        No = 4; Duration = 7.2; Keyframe = "screen_share"; Speaker = "骗子"; SapiVoice = "Microsoft Kangkang"; SapiRate = 2
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "+4%"; EdgePitch = "+0Hz"
        Title = "屏幕共享"; Visual = "两部手机和理赔页面形成证据画面。"
        Line = "打开屏幕共享，我帮您核对。验证码不用说。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese anti-fraud short-drama evidence video, one continuous 9:16 live-action shot. Use the input image as the exact first frame. Two smartphones stay on the wooden table with unreadable courier refund and screen-share warning style interfaces, blood pressure monitor parcel in background. Animate only realistic phone screen glow, tiny reflection movement, a hand slowly entering frame and hesitating above the phone, slight table parallax, no readable text, no logos, no new face, no camera shake, no scene jump."
    },
    @{
        No = 5; Duration = 7.0; Keyframe = "father_phone"; Speaker = "父亲"; SapiVoice = "Microsoft Kangkang"; SapiRate = -2
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-10%"; EdgePitch = "-5Hz"
        Title = "被误会"; Visual = "父亲打来电话，女儿以为他又不懂手机。"
        Line = "小晴，别开共享。钱慢慢退，眼睛不能交出去。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese family anti-fraud short-drama video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same 58-year-old Chinese father Su Jianguo, same short gray hair, same plain navy zip jacket, same modest dining room and blood pressure monitor. He holds his old phone to his ear, speaks calmly but urgently, small mouth movement, blinking, slight hand tension on the table, natural breathing, locked camera, stable face and clothes, no police uniform, no new person, no scene change."
    },
    @{
        No = 6; Duration = 7.2; Keyframe = "father_phone"; Speaker = "父亲"; SapiVoice = "Microsoft Kangkang"; SapiRate = -2
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-10%"; EdgePitch = "-5Hz"
        Title = "反转"; Visual = "父亲仍坐在餐桌旁稳住骗子，民警已锁定号码。"
        Line = "我不是不会用。我在拖住他，民警已经锁到号了。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese family anti-fraud short-drama reveal video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same father, same navy zip jacket, same dining table, phone and blood pressure monitor. He lowers his eyes to the phone, then looks slightly forward with calm protective expression, subtle mouth movement as if revealing the truth, no dramatic camera shake, no police uniform, no new person, no scene jump, stable identity, realistic live action."
    },
    @{
        No = 7; Duration = 6.4; Keyframe = "screen_share"; Speaker = "民警"; SapiVoice = "Microsoft Huihui"; SapiRate = -1
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-4%"; EdgePitch = "-2Hz"
        Title = "点破"; Visual = "手机与包裹形成证据链。"
        Line = "他们骗的，是你替家人着急的那一分钟。"
        Motion = "micro_evidence"; Tier = "B"
    },
    @{
        No = 8; Duration = 7.0; Keyframe = "family_final"; Speaker = "旁白"; SapiVoice = "Microsoft Huihui Desktop"; SapiRate = -1
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-8%"; EdgePitch = "-2Hz"
        Title = "公益收束"; Visual = "父女坐下，手机扣在桌上。"
        Line = "退款不到账，先核实。真正的家人，会让你慢下来。"
        Motion = "micro_pull"; Tier = "C"
    }
)

$scriptMd = @"
# 《退款之前》Wan2.2 动态升级版

题材：反诈升级 + 快递理赔 + 屏幕共享 + 亲情反转公益短剧
规格：竖屏 9:16，30fps，含 Wan2.2 动态镜头、神经配音、烧录字幕、BGM。
升级点：镜头 2、4、5、6 使用 Wan2.2 I2V LightX2V 4-step 工作流；台词缩短、字幕下沉、非 Wan 镜头取消明显假抖动。

## 分场剧本

"@
foreach ($shot in $shots) {
    $scriptMd += "### 镜头 $($shot.No)：$($shot.Title)`n"
    $scriptMd += "- 画面：$($shot.Visual)`n"
    $scriptMd += "- 台词：$($shot.Speaker)：$($shot.Line)`n"
    $scriptMd += "- 关键帧：$($shot.Keyframe)`n"
    $scriptMd += "- 动态层级：$($shot.Tier)`n"
    if ($WanShotNumbers -contains [int]$shot.No) {
        $scriptMd += "- 动态方案：Wan2.2 I2V`n"
    }
    $scriptMd += "`n"
}
Write-Utf8 (Join-Path $OutPath "script.md") $scriptMd

Copy-Item -LiteralPath (Join-Path $SourcePath "characters.md") -Destination (Join-Path $OutPath "characters.md") -Force
Copy-Item -LiteralPath (Join-Path $SourcePath "continuity-rules.md") -Destination (Join-Path $OutPath "continuity-rules.md") -Force
$plan = @"
# Wan2.2 + Neural TTS 升级方案

- 关键帧来源：上一版稳定样片 `public-service-refund-antifraud-v1/keyframes`
- Wan2.2 工作流：`video_wan2_2_14B_i2v.json`
- 加速：`wan2.2_i2v_lightx2v_4steps_lora_v1_high_noise.safetensors` 与 low noise LoRA
- 替换镜头：$($WanShotNumbers -join ", ")
- 采样：49 frames, 24fps, 6 steps, split 3/3, low noise drift control
- 配音：Edge Neural TTS 优先，台词缩短并降低语速；失败时回退 Windows SAPI
- 合成：所有镜头重编码到 1080x1920 30fps，再统一烧字幕、混 BGM、ducking
- 字幕：字号降到 42，底部安全区下沉，最多两行，避免挡脸
- 抖动控制：非 Wan 镜头只保留极轻微推拉；Wan 镜头禁止镜头乱晃、换脸、换衣

成本记录：上一版 balanced Wan2.2 测试在 49 frames / 12 steps 下超过 10 分钟仍未完成，已中断；本版改用 LightX2V 4-step LoRA 控制成本。
"@
Write-Utf8 (Join-Path $OutPath "comfyui-generation-plan.md") $plan
Write-Utf8 (Join-Path $OutPath "storyboard.json") ([pscustomobject]@{ title = "退款之前"; version = "wan22-upgrade"; wanShotNumbers = $WanShotNumbers; shots = $shots } | ConvertTo-Json -Depth 8)

$wanManifest = @()
if (-not $SkipWan) {
    $client = New-ComfyClient
    try {
        Invoke-ComfyGetJson -Client $client -Url "$ComfyUrl/system_stats" | Out-Null
        foreach ($shot in $shots) {
            if (-not ($WanShotNumbers -contains [int]$shot.No)) {
                continue
            }
            $idx = "{0:00}" -f $shot.No
            $wanOut = Join-Path $WanClipPath "wan_shot_$idx.mp4"
            if ($ReuseWan -and (Test-Path -LiteralPath $wanOut)) {
                $wanManifest += [pscustomobject]@{ shotNo = $shot.No; reused = $true; output = $wanOut }
                continue
            }
            $keyframe = Join-Path $KeyframePath "$($shot.Keyframe).png"
            Write-Host "Generating Wan2.2 shot $idx..."
            $result = Submit-Wan22Clip -Client $client -Shot $shot -ImagePath $keyframe -OutputFile $wanOut
            $wanManifest += [pscustomobject]@{ shotNo = $shot.No; reused = $false; promptId = $result.promptId; source = $result.source; output = $result.output }
        }
    } finally {
        $client.Dispose()
    }
}
Write-Utf8 (Join-Path $OutPath "wan-manifest.json") ($wanManifest | ConvertTo-Json -Depth 6)

Push-Location $OutPath
try {
    $shotFiles = @()
    $srt = ""
    $timelineStart = 0.0
    $voiceManifest = @()

    foreach ($shot in $shots) {
        $idx = "{0:00}" -f $shot.No
        $audio = Join-Path $VoicePath "voice_$idx.wav"
        $ass = "shot_$idx.ass"
        $mp4 = Join-Path $ShotPath "shot_$idx.mp4"
        $keyframe = Join-Path $KeyframePath "$($shot.Keyframe).png"
        $wanClip = Join-Path $WanClipPath "wan_shot_$idx.mp4"
        $hasWan = ($WanShotNumbers -contains [int]$shot.No) -and (Test-Path -LiteralPath $wanClip)

        $voiceProvider = New-VoiceLine -Shot $shot -Output $audio
        $voiceManifest += [pscustomobject]@{ shotNo = $shot.No; provider = $voiceProvider; file = $audio }
        $audioDuration = Get-DurationSeconds $audio
        $duration = [math]::Max([double]$shot.Duration, $audioDuration + 0.75)
        $durationText = $duration.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
        $endAss = Format-AssTime $duration
        $subtitle = Escape-Ass (Split-SubtitleLine "$($shot.Speaker)：$($shot.Line)" 15)

        $assText = @"
[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
Style: Sub,Microsoft YaHei,42,&H00FFFFFF,&H000000FF,&HCC000000,&H88000000,-1,0,0,0,100,100,0,0,1,3,1,2,78,78,214,1

[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
Dialogue: 0,0:00:00.20,$endAss,Sub,,0,0,0,,$subtitle
"@
        Write-Utf8 (Join-Path $OutPath $ass) $assText

        $audioDelay = 220
        if ($hasWan) {
            $wanDuration = Get-DurationSeconds $wanClip
            $ptsFactor = ($duration / [math]::Max(0.1, $wanDuration)).ToString("0.######", [Globalization.CultureInfo]::InvariantCulture)
            $filter = "[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setpts=${ptsFactor}*PTS,fps=30,trim=0:$durationText,eq=contrast=1.02:saturation=1.02,unsharp=5:5:0.25,ass=$ass[v];[1:a]adelay=$audioDelay|$audioDelay,apad,atrim=0:$durationText,loudnorm=I=-16:TP=-1.5:LRA=11[a]"
            & $Ffmpeg -y -i $wanClip -i $audio -filter_complex $filter -map "[v]" -map "[a]" -r 30 -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -c:a aac -b:a 160k -shortest $mp4
        } else {
            $motion = switch ($shot.Motion) {
                "micro_pull" { "scale=1116:1984:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2-4*sin(t*0.18)':y='(ih-1920)/2-3*cos(t*0.16)'" }
                "micro_push" { "scale=1118:1988:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+4*sin(t*0.20)':y='(ih-1920)/2+3*cos(t*0.18)'" }
                "micro_hold" { "scale=1110:1973:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+2*sin(t*0.12)':y='(ih-1920)/2+2*cos(t*0.10)'" }
                "micro_evidence" { "scale=1114:1981:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+4*sin(t*0.14)':y='(ih-1920)/2+3*cos(t*0.13)'" }
                default { "scale=1114:1981:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+3*sin(t*0.16)':y='(ih-1920)/2+3*cos(t*0.14)'" }
            }
            $filter = "[0:v]$motion,setsar=1,fps=30,eq=contrast=1.025:saturation=1.03,unsharp=5:5:0.35,ass=$ass[v];[1:a]adelay=$audioDelay|$audioDelay,apad,atrim=0:$durationText,loudnorm=I=-16:TP=-1.5:LRA=11[a]"
            & $Ffmpeg -y -loop 1 -framerate 30 -t $durationText -i $keyframe -i $audio -filter_complex $filter -map "[v]" -map "[a]" -r 30 -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -c:a aac -b:a 160k -shortest $mp4
        }
        if ($LASTEXITCODE -ne 0) {
            throw "ffmpeg failed on shot $idx"
        }
        $shotFiles += $mp4

        $srt += "$($shot.No)`r`n"
        $srt += "$(Format-SrtTime ($timelineStart + 0.20)) --> $(Format-SrtTime ($timelineStart + $duration))`r`n"
        $srt += "$($shot.Speaker)：$($shot.Line)`r`n`r`n"
        $timelineStart += $duration
    }

    Write-Utf8 (Join-Path $OutPath "voice-manifest.json") ($voiceManifest | ConvertTo-Json -Depth 6)
    Write-Utf8 (Join-Path $OutPath "subtitles.srt") $srt
    $concatLines = $shotFiles | ForEach-Object { "file '$($_.Replace('\', '/'))'" }
    Write-Utf8 (Join-Path $OutPath "concat.txt") ($concatLines -join "`n")
    & $Ffmpeg -y -f concat -safe 0 -i (Join-Path $OutPath "concat.txt") -c copy (Join-Path $OutPath "voice_only.mp4")
    if ($LASTEXITCODE -ne 0) {
        throw "concat failed"
    }

    $totalDuration = Get-DurationSeconds (Join-Path $OutPath "voice_only.mp4")
    $totalText = $totalDuration.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
    $mix = "[1:a]atrim=0:$totalText,volume=0.08[bgm];[0:a]loudnorm=I=-16:TP=-1.5:LRA=11[voice];[bgm][voice]sidechaincompress=threshold=0.025:ratio=12:attack=8:release=260[duck];[voice][duck]amix=inputs=2:duration=first:weights='1 0.38'[a]"
    & $Ffmpeg -y -i (Join-Path $OutPath "voice_only.mp4") -stream_loop -1 -i $BgmPath -filter_complex $mix -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -movflags +faststart (Join-Path $OutPath "final_refund_antifraud_family_wan22.mp4")
    if ($LASTEXITCODE -ne 0) {
        throw "final mix failed"
    }
} finally {
    Pop-Location
}

$final = Join-Path $OutPath "final_refund_antifraud_family_wan22.mp4"
$probe = & $Ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate,duration -show_entries format=duration,size -of json $final
Write-Utf8 (Join-Path $OutPath "ffprobe.json") ($probe -join "`n")
Write-Host "FINAL_VIDEO=$final"
