param(
    [string]$OutDir = "output\public-service-worldcup-antifraud-comfy",
    [string]$ComfyUrl = "http://127.0.0.1:8188",
    [string]$ComfyOutput = "D:\Projects\ComfyUI-aki\ComfyUI\output",
    [switch]$ReuseKeyframes,
    [switch]$SkipComfy,
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
$OutPath = Join-Path $Root $OutDir
$KeyframePath = Join-Path $OutPath "keyframes"
$VoicePath = Join-Path $OutPath "voice"
$ShotPath = Join-Path $OutPath "shots"
$WorkflowPath = Join-Path $Root "backend\src\main\resources\comfyui\workflows\image_z_image_turbo.json"
$BgmPath = Join-Path $Root "backend\src\main\resources\assets\bgm_default.wav"

New-Item -ItemType Directory -Force -Path $OutPath, $KeyframePath, $VoicePath, $ShotPath | Out-Null

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
    $client.Timeout = [TimeSpan]::FromSeconds(40)
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
    $json = $Body | ConvertTo-Json -Depth 30 -Compress
    $content = [System.Net.Http.StringContent]::new($json, $utf8NoBom, "application/json")
    $response = $Client.PostAsync($Url, $content).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "ComfyUI POST failed $Url HTTP $([int]$response.StatusCode): $text"
    }
    return $text | ConvertFrom-Json
}

function Submit-ZImageKeyframe {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Prompt,
        [int64]$Seed,
        [string]$Prefix,
        [string]$OutputFile
    )

    $workflow = Get-Content -Raw -LiteralPath $WorkflowPath | ConvertFrom-Json -AsHashtable
    $workflow["4"]["inputs"]["text"] = $Prompt
    $workflow["6"]["inputs"]["width"] = 720
    $workflow["6"]["inputs"]["height"] = 1280
    $workflow["8"]["inputs"]["seed"] = $Seed
    $workflow["10"]["inputs"]["filename_prefix"] = $Prefix

    $clientId = [guid]::NewGuid().ToString()
    $submit = Invoke-ComfyPostJson -Client $Client -Url "$ComfyUrl/prompt" -Body @{ prompt = $workflow; client_id = $clientId }
    $promptId = $submit.prompt_id
    if (-not $promptId) {
        throw "ComfyUI did not return prompt_id for $Prefix"
    }

    for ($i = 0; $i -lt 360; $i++) {
        Start-Sleep -Seconds 1
        $history = Invoke-ComfyGetJson -Client $Client -Url "$ComfyUrl/history/$promptId"
        $entryProp = $history.PSObject.Properties[$promptId]
        if ($entryProp -eq $null) {
            continue
        }
        $entry = $entryProp.Value
        $status = $entry.status
        if ($status -and $status.status_str -and $status.status_str -ne "success") {
            $messages = $status.messages | ConvertTo-Json -Depth 10 -Compress
            throw "ComfyUI prompt $promptId failed for ${Prefix}: $messages"
        }
        $outputNode = $entry.outputs.PSObject.Properties["10"].Value
        if ($outputNode -eq $null -or $outputNode.images.Count -lt 1) {
            throw "ComfyUI prompt $promptId finished without SaveImage output for $Prefix"
        }
        $image = $outputNode.images[0]
        $source = Join-Path $ComfyOutput $image.filename
        if ($image.subfolder) {
            $source = Join-Path (Join-Path $ComfyOutput $image.subfolder) $image.filename
        }
        Copy-Item -LiteralPath $source -Destination $OutputFile -Force
        return [pscustomobject]@{
            promptId = $promptId
            source = $source
            output = $OutputFile
            filename = $image.filename
        }
    }

    throw "Timed out waiting for ComfyUI keyframe $Prefix ($promptId)"
}

function Speak-Line {
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

$keyframes = @(
    @{
        Id = "lin_phone"; Seed = 26062301
        Prompt = "Vertical 9:16 photorealistic Chinese public service short drama still. World Cup final night in a small apartment, blue TV football match glow, no official logo. A 28-year-old Chinese delivery rider named Lin Zhou, short black hair, dark navy hoodie, old sports wristband, serious tired face, holding a smartphone at a table. Yellow-black delivery helmet beside him. Suspenseful anti-fraud mood, cinematic realistic lighting, shallow depth of field, no readable text, no logo, no watermark."
    },
    @{
        Id = "mother_phone"; Seed = 26062302
        Prompt = "Vertical 9:16 photorealistic Chinese public service short drama still. A 55-year-old Chinese mother named Li Mei in a modest living room, low ponytail, beige knitted cardigan, reading glasses, calm but worried expression. She holds an old smartphone on speaker and recording mode, warm lamp light, faint blue football match glow from another room, community anti-fraud pamphlet on the table without readable text, cinematic realism, no logo, no watermark."
    },
    @{
        Id = "mother_reveal"; Seed = 26062303
        Prompt = "Vertical 9:16 photorealistic Chinese short drama still. The same 55-year-old Chinese mother in beige cardigan holds her old phone toward camera as if showing a recording, red-blue police light reflection on the wall and door frame, a police silhouette outside the door, tense but controlled anti-fraud reveal moment, realistic cinematic low light, no readable text, no logo, no watermark."
    },
    @{
        Id = "phone_evidence"; Seed = 26062304
        Prompt = "Vertical 9:16 photorealistic close-up still. Two smartphones on a table under World Cup TV blue light, one phone shows an unreadable suspicious betting app interface, another phone shows an unreadable call recording waveform, a yellow-black delivery helmet blurred in background, anti-fraud evidence mood, realistic reflections, no readable text, no official logo, no watermark."
    },
    @{
        Id = "family_final"; Seed = 26062305
        Prompt = "Vertical 9:16 photorealistic Chinese public service short drama final still. A young Chinese delivery rider in dark navy hoodie sits beside his 55-year-old mother in beige cardigan, both watching a football match on TV in a small apartment, yellow-black delivery helmet on the table, relieved emotional family atmosphere, warm light mixed with TV blue glow, cinematic realism, no readable text, no logo, no watermark."
    }
)

$shots = @(
    @{
        No = 1; Duration = 6.6; Keyframe = "lin_phone"; Speaker = "旁白"; Voice = "Microsoft Huihui Desktop"; Rate = -1
        Title = "补时前"; Visual = "林舟盯着世界杯决赛，手机弹出竞猜返利。"
        Line = "决赛补时前，林舟以为自己等的是一粒进球。"
        Motion = "slow_push"; Tier = "B"
    },
    @{
        No = 2; Duration = 7.2; Keyframe = "lin_phone"; Speaker = "林舟"; Voice = "Microsoft Kangkang"; Rate = 0
        Title = "诱饵"; Visual = "三千变三万，倒计时不断逼近。"
        Line = "三千块变三万？只要今晚翻身，我就不用再让妈操心了。"
        Motion = "phone_push"; Tier = "A"
    },
    @{
        No = 3; Duration = 7.0; Keyframe = "lin_phone"; Speaker = "母亲"; Voice = "Microsoft Huihui Desktop"; Rate = 0
        Title = "来电"; Visual = "母亲连续来电，验证码停在输入框前。"
        Line = "小舟，先别点链接。越是补时，越不能孤注一掷。"
        Motion = "hold"; Tier = "A"
    },
    @{
        No = 4; Duration = 7.3; Keyframe = "mother_phone"; Speaker = "骗子"; Voice = "Microsoft Kangkang"; Rate = 2
        Title = "催促"; Visual = "旧手机免提里，骗子催母亲转保证金。"
        Line = "阿姨，最后三十秒，再不转保证金，名额就取消了。"
        Motion = "slow_push"; Tier = "A"
    },
    @{
        No = 5; Duration = 7.1; Keyframe = "lin_phone"; Speaker = "林舟"; Voice = "Microsoft Kangkang"; Rate = -1
        Title = "误会"; Visual = "林舟以为母亲也被骗，情绪失控。"
        Line = "妈，你是不是也进了那个群？你别转，我现在就过去！"
        Motion = "shake_soft"; Tier = "B"
    },
    @{
        No = 6; Duration = 7.2; Keyframe = "mother_reveal"; Speaker = "母亲"; Voice = "Microsoft Huihui Desktop"; Rate = -1
        Title = "反转"; Visual = "母亲推近录音界面，门外警灯亮起。"
        Line = "我没有要转钱。我在拖住他，警察已经到门口了。"
        Motion = "slow_push"; Tier = "A"
    },
    @{
        No = 7; Duration = 6.8; Keyframe = "phone_evidence"; Speaker = "民警"; Voice = "Microsoft Huihui"; Rate = 0
        Title = "证据"; Visual = "两个手机号码重合，骗局闭环。"
        Line = "他们盯上的不是球赛，是你们急着翻身的心。"
        Motion = "evidence"; Tier = "B"
    },
    @{
        No = 8; Duration = 7.4; Keyframe = "family_final"; Speaker = "旁白"; Voice = "Microsoft Huihui Desktop"; Rate = -1
        Title = "公益收束"; Visual = "母子重新坐在一起，看完最后一攻。"
        Line = "补时可以逆转，转账没有补时。世界杯再热，也别让骗子进球。"
        Motion = "slow_pull"; Tier = "C"
    }
)

$scriptMd = @"
# 《补时之前》完整短剧剧本

题材：反诈 + 亲情反转 + 世界杯公益短剧
规格：竖屏 9:16，约 55-60 秒，30fps，含配音、台词、烧录字幕、BGM。
核心反转：儿子以为母亲也被世界杯竞猜骗局骗了，最后发现母亲是在拖住骗子，也是在救差点被骗的自己。

## 分场剧本

"@
foreach ($shot in $shots) {
    $scriptMd += "### 镜头 $($shot.No)：$($shot.Title)`n"
    $scriptMd += "- 画面：$($shot.Visual)`n"
    $scriptMd += "- 台词：$($shot.Speaker)：$($shot.Line)`n"
    $scriptMd += "- 关键帧：$($shot.Keyframe)`n"
    $scriptMd += "- 动态层级：$($shot.Tier)`n`n"
}
Write-Utf8 (Join-Path $OutPath "script.md") $scriptMd

$charactersMd = @"
# 角色设定

## 林舟
- 年龄：28 岁
- 身份：外卖骑手，世界杯球迷，近期经济压力大。
- 外观锁定：短黑发，深色连帽外套，左手旧运动手环，黄色/黑色外卖头盔在身边。
- 表演：焦虑、嘴硬、急着翻身；反转后有明显羞愧和松弛。
- 配音：Microsoft Kangkang。

## 李梅
- 年龄：55 岁
- 身份：林舟母亲，社区反诈志愿者。
- 外观锁定：低马尾，米色针织开衫，老花镜，旧手机开免提/录音。
- 表演：表面唠叨，实际冷静；反转时不煽情，只稳稳说出真相。
- 配音：Microsoft Huihui Desktop。

## 骗子
- 身份：伪装成世界杯竞猜平台客服。
- 呈现：只以手机语音、收款界面、催促话术出现，不露脸。
- 表演：语速偏快，制造倒计时压力。

## 民警
- 身份：社区民警。
- 呈现：以门外轮廓、警灯反光、证据说明为主，避免抢戏。
"@
Write-Utf8 (Join-Path $OutPath "characters.md") $charactersMd

$continuityMd = @"
# 镜头连续性规则

1. 林舟锁定：短黑发、深色连帽外套、左手旧运动手环、黄色/黑色外卖头盔。
2. 母亲锁定：低马尾、米色针织开衫、老花镜、旧手机。
3. 世界杯只作为氛围：电视绿茵场、蓝色屏幕光、补时/倒计时情绪，不出现真实赛事 Logo。
4. 骗局只围绕四个元素：竞猜返利、保证金、验证码、收款码。
5. 稳定样片优先复用关键帧，避免每个镜头重生成人脸导致漂移。
6. A 档可替换成 Wan2.2 I2V：镜头 2、3、4、6。替换时必须以当前关键帧为首帧，锁定人物和服装。
7. 负面约束：禁止换脸、换衣、加新人、字幕入画、Logo、水印、线稿化、漫画化、黑白化、整帧抖动。
"@
Write-Utf8 (Join-Path $OutPath "continuity-rules.md") $continuityMd

$comfyPlanMd = @"
# ComfyUI 生成方案

## 当前成片方案
- 关键帧：`backend/src/main/resources/comfyui/workflows/image_z_image_turbo.json`
- 模型：`z_image_turbo_bf16.safetensors` + `qwen_3_4b.safetensors` + `ae.safetensors`
- 分辨率：720x1280 生成，ffmpeg 统一合成 1080x1920 30fps。
- 稳定策略：同一角色镜头复用关键帧，通过轻微推拉、剪辑节奏、配音和字幕完成叙事。

## Wan2.2 动态替换方案
- 首选：`backend/src/main/resources/comfyui/workflows/video_wan2_2_14B_i2v_series_balanced.json`
- 替换镜头：2、3、4、6
- 建议参数：`num_frames=49` 起步，`steps=18`，`cfg=1.25`，`noise_aug_strength=0.035-0.05`，`save_output=true`
- 输出后用 ffmpeg 补到 30fps，再进入同一字幕/TTS 合成链路。

## Wan2.2 通用 Prompt
Commercial vertical Chinese public-service short-drama video, one continuous 9:16 live-action shot. Use the input image as the exact first frame. Preserve face, age, outfit, props, phone position, room layout, lighting and camera angle. Locked or nearly locked camera. Actor-local motion only: eye movement, breathing, small head turn, hand movement, screen glow. Photorealistic live action. No subtitles, no logo, no watermark, no new person, no outfit change, no face drift, no whole-frame pan, no sketch, no manga, no monochrome.

## 自动修复规则
1. 若角色脸漂：降低 `noise_aug_strength`，缩短 `num_frames`，重新使用原关键帧。
2. 若服装变：在 prompt 开头重复服装锁定词，并提高首帧参考强度。
3. 若镜头抖：改为 locked camera / actor-local motion only，必要时回退到稳定关键帧版。
4. 若字幕遮挡：只在 ffmpeg 阶段烧录字幕，禁止 ComfyUI 画面内生成文字。
"@
Write-Utf8 (Join-Path $OutPath "comfyui-generation-plan.md") $comfyPlanMd

$storyboard = [pscustomobject]@{
    title = "补时之前"
    format = "vertical 9:16, 1080x1920, 30fps"
    keyframes = $keyframes
    shots = $shots
}
Write-Utf8 (Join-Path $OutPath "storyboard.json") ($storyboard | ConvertTo-Json -Depth 8)

$keyframeManifest = @()
if (-not $SkipComfy) {
    $client = New-ComfyClient
    try {
        Invoke-ComfyGetJson -Client $client -Url "$ComfyUrl/system_stats" | Out-Null
        foreach ($frame in $keyframes) {
            $target = Join-Path $KeyframePath "$($frame.Id).png"
            if ($ReuseKeyframes -and (Test-Path -LiteralPath $target)) {
                $keyframeManifest += [pscustomobject]@{ id = $frame.Id; reused = $true; output = $target }
                continue
            }
            Write-Host "Generating keyframe $($frame.Id)..."
            $result = Submit-ZImageKeyframe -Client $client -Prompt $frame.Prompt -Seed $frame.Seed -Prefix "niren_worldcup_antifraud_$($frame.Id)" -OutputFile $target
            $keyframeManifest += [pscustomobject]@{ id = $frame.Id; reused = $false; promptId = $result.promptId; source = $result.source; output = $result.output }
        }
    } finally {
        $client.Dispose()
    }
} else {
    foreach ($frame in $keyframes) {
        $target = Join-Path $KeyframePath "$($frame.Id).png"
        if (-not (Test-Path -LiteralPath $target)) {
            throw "Missing keyframe $target while -SkipComfy is set"
        }
        $keyframeManifest += [pscustomobject]@{ id = $frame.Id; reused = $true; output = $target }
    }
}
Write-Utf8 (Join-Path $OutPath "keyframe-manifest.json") ($keyframeManifest | ConvertTo-Json -Depth 6)

Push-Location $OutPath
try {
    $shotFiles = @()
    $srt = ""
    $timelineStart = 0.0

    foreach ($shot in $shots) {
        $idx = "{0:00}" -f $shot.No
        $audio = Join-Path $VoicePath "voice_$idx.wav"
        $ass = "shot_$idx.ass"
        $mp4 = Join-Path $ShotPath "shot_$idx.mp4"
        $keyframe = Join-Path $KeyframePath "$($shot.Keyframe).png"
        if (-not (Test-Path -LiteralPath $keyframe)) {
            throw "Missing keyframe for shot ${idx}: $keyframe"
        }

        Speak-Line -Text $shot.Line -Voice $shot.Voice -Rate $shot.Rate -Output $audio
        $audioDuration = Get-DurationSeconds $audio
        $duration = [math]::Max([double]$shot.Duration, $audioDuration + 1.1)
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
Style: Sub,Microsoft YaHei,48,&H00FFFFFF,&H000000FF,&HCC000000,&H99000000,-1,0,0,0,100,100,0,0,1,4,1,2,76,76,168,1

[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
Dialogue: 0,0:00:00.25,$endAss,Sub,,0,0,0,,$subtitle
"@
        Write-Utf8 (Join-Path $OutPath $ass) $assText

        $audioDelay = 260
        $motion = switch ($shot.Motion) {
            "slow_pull" { "scale=1160:2062:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2-18*sin(t*0.24)':y='(ih-1920)/2-14*cos(t*0.21)'" }
            "phone_push" { "scale=1180:2098:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+26*sin(t*0.32)':y='(ih-1920)/2+18*cos(t*0.27)'" }
            "shake_soft" { "scale=1160:2062:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+12*sin(t*1.1)':y='(ih-1920)/2+8*cos(t*0.9)'" }
            "evidence" { "scale=1160:2062:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+22*sin(t*0.18)':y='(ih-1920)/2+16*cos(t*0.16)'" }
            default { "scale=1140:2027:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+18*sin(t*0.25)':y='(ih-1920)/2+14*cos(t*0.20)'" }
        }
        $filter = "[0:v]$motion,setsar=1,fps=30,eq=contrast=1.025:saturation=1.03,unsharp=5:5:0.35,ass=$ass[v];[1:a]adelay=$audioDelay|$audioDelay,apad,atrim=0:$durationText,loudnorm=I=-16:TP=-1.5:LRA=11[a]"
        & $Ffmpeg -y -loop 1 -framerate 30 -t $durationText -i $keyframe -i $audio -filter_complex $filter -map "[v]" -map "[a]" -r 30 -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -c:a aac -b:a 160k -shortest $mp4
        if ($LASTEXITCODE -ne 0) {
            throw "ffmpeg failed on shot $idx"
        }
        $shotFiles += $mp4

        $srt += "$($shot.No)`r`n"
        $srt += "$(Format-SrtTime ($timelineStart + 0.25)) --> $(Format-SrtTime ($timelineStart + $duration))`r`n"
        $srt += "$($shot.Speaker)：$($shot.Line)`r`n`r`n"
        $timelineStart += $duration
    }

    Write-Utf8 (Join-Path $OutPath "subtitles.srt") $srt
    $concatLines = $shotFiles | ForEach-Object { "file '$($_.Replace('\', '/'))'" }
    Write-Utf8 (Join-Path $OutPath "concat.txt") ($concatLines -join "`n")
    & $Ffmpeg -y -f concat -safe 0 -i (Join-Path $OutPath "concat.txt") -c copy (Join-Path $OutPath "voice_only.mp4")
    if ($LASTEXITCODE -ne 0) {
        throw "concat failed"
    }

    $totalDuration = Get-DurationSeconds (Join-Path $OutPath "voice_only.mp4")
    $totalText = $totalDuration.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
    $mix = "[1:a]atrim=0:$totalText,volume=0.10[bgm];[0:a]loudnorm=I=-16:TP=-1.5:LRA=11[voice];[bgm][voice]sidechaincompress=threshold=0.028:ratio=10:attack=8:release=260[duck];[voice][duck]amix=inputs=2:duration=first:weights='1 0.42'[a]"
    & $Ffmpeg -y -i (Join-Path $OutPath "voice_only.mp4") -stream_loop -1 -i $BgmPath -filter_complex $mix -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -movflags +faststart (Join-Path $OutPath "final_worldcup_antifraud_family_comfy.mp4")
    if ($LASTEXITCODE -ne 0) {
        throw "final mix failed"
    }
} finally {
    Pop-Location
}

$final = Join-Path $OutPath "final_worldcup_antifraud_family_comfy.mp4"
$probe = & $Ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate,duration -show_entries format=duration,size -of json $final
Write-Utf8 (Join-Path $OutPath "ffprobe.json") ($probe -join "`n")
Write-Host "FINAL_VIDEO=$final"
