param(
    [string]$OutDir = "output\public-service-refund-antifraud-v1",
    [string]$ComfyUrl = "http://127.0.0.1:8188",
    [string]$ComfyOutput = "D:\Projects\ComfyUI-aki\ComfyUI\output",
    [string]$PythonExe = "D:\Projects\ComfyUI-aki\python\python.exe",
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
    param([string]$Text, [int]$MaxChars = 15)
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
    param([System.Net.Http.HttpClient]$Client, [string]$Url)
    $response = $Client.GetAsync($Url).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "ComfyUI GET failed $Url HTTP $([int]$response.StatusCode): $text"
    }
    return $text | ConvertFrom-Json
}

function Invoke-ComfyPostJson {
    param([System.Net.Http.HttpClient]$Client, [string]$Url, [object]$Body)
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

    $submit = Invoke-ComfyPostJson -Client $Client -Url "$ComfyUrl/prompt" -Body @{
        prompt = $workflow
        client_id = [guid]::NewGuid().ToString()
    }
    $promptId = $submit.prompt_id
    if (-not $promptId) {
        throw "ComfyUI did not return prompt_id for $Prefix"
    }

    for ($i = 0; $i -lt 480; $i++) {
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

function New-EdgeVoiceLine {
    param([hashtable]$Shot, [string]$Output)
    $idx = "{0:00}" -f $Shot.No
    $raw = Join-Path $VoicePath "voice_${idx}_raw.mp3"
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
    & $Ffmpeg -y -i $raw -af "aresample=48000,highpass=f=70,acompressor=threshold=-18dB:ratio=2.2:attack=8:release=120,loudnorm=I=-16:TP=-1.5:LRA=11" -ac 1 -ar 48000 $Output
    if ($LASTEXITCODE -ne 0) {
        throw "ffmpeg voice postprocess failed for shot $idx"
    }
    return "edge-tts:$($Shot.EdgeVoice)"
}

$keyframes = @(
    @{
        Id = "daughter_call"; Seed = 2606241101
        Prompt = "Vertical 9:16 photorealistic Chinese public-service short drama still. Night hospital corridor break room, cool fluorescent light mixed with phone glow. A 27-year-old Chinese community nurse named Su Qing, short tied black hair, light blue scrub jacket under a beige coat, tired but kind face, holding a smartphone with a suspicious refund call, thermos and paper parcel on table, cinematic realism, shallow depth of field, no readable text, no logo, no watermark."
    },
    @{
        Id = "father_phone"; Seed = 2606241102
        Prompt = "Vertical 9:16 photorealistic Chinese public-service short drama still. A 58-year-old Chinese father named Su Jianguo, retired bus driver, short gray hair, navy zip jacket, sitting in a modest apartment dining area with an old smartphone on speaker, a blood pressure monitor parcel box on the table, calm eyes pretending to be confused, warm lamp light, realistic cinematic, no readable text, no logo, no watermark."
    },
    @{
        Id = "screen_share"; Seed = 2606241103
        Prompt = "Vertical 9:16 photorealistic close-up still. Two smartphones on a wooden table, one phone has an unreadable video call and screen sharing warning style interface, another phone has an unreadable courier refund page, old parcel box and blood pressure monitor blurred in background, blue phone glow, tense anti-fraud evidence mood, realistic reflections, no readable text, no official logos, no watermark."
    },
    @{
        Id = "family_final"; Seed = 2606241105
        Prompt = "Vertical 9:16 photorealistic Chinese public-service short drama final still. The 27-year-old daughter in light blue scrub jacket and beige coat sits beside her 58-year-old father in navy jacket at a dining table, blood pressure monitor parcel unopened, both relieved and smiling softly, warm family apartment light, phone placed face down, calm anti-fraud ending, cinematic realism, no readable text, no logo, no watermark."
    }
)

$shots = @(
    @{
        No = 1; Duration = 6.4; Keyframe = "daughter_call"; Speaker = "旁白"
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-4%"; EdgePitch = "-1Hz"
        Title = "夜班来电"; Visual = "苏晴夜班间隙接到快递理赔电话。"
        Line = "夜班快结束时，苏晴等来的不是休息，是一个快递理赔电话。"
        Motion = "slow_push"; Tier = "B"
    },
    @{
        No = 2; Duration = 7.1; Keyframe = "daughter_call"; Speaker = "骗子"
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "+8%"; EdgePitch = "+1Hz"
        Title = "退款诱饵"; Visual = "骗子声称父亲的血压仪破损，可十倍退款。"
        Line = "苏女士，您父亲的血压仪破损，我们马上退一千九百八。"
        Motion = "phone_push"; Tier = "A"
    },
    @{
        No = 3; Duration = 7.0; Keyframe = "daughter_call"; Speaker = "苏晴"
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-1%"; EdgePitch = "-1Hz"
        Title = "心急"; Visual = "苏晴想赶紧处理，不想再让父亲操心。"
        Line = "这么晚别折腾我爸了，流程你发我，我自己弄。"
        Motion = "hold"; Tier = "B"
    },
    @{
        No = 4; Duration = 7.5; Keyframe = "screen_share"; Speaker = "骗子"
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "+10%"; EdgePitch = "+2Hz"
        Title = "屏幕共享"; Visual = "手机弹出共享界面和退款页面。"
        Line = "打开屏幕共享，我帮您核对银行卡，验证码不用告诉任何人。"
        Motion = "evidence"; Tier = "A"
    },
    @{
        No = 5; Duration = 7.0; Keyframe = "father_phone"; Speaker = "父亲"
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-8%"; EdgePitch = "-4Hz"
        Title = "被误会"; Visual = "父亲连续打来电话，苏晴以为他又不懂手机。"
        Line = "小晴，别开那个共享。钱可以慢慢退，眼睛不能交给别人。"
        Motion = "slow_push"; Tier = "A"
    },
    @{
        No = 6; Duration = 7.3; Keyframe = "father_phone"; Speaker = "父亲"
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-7%"; EdgePitch = "-4Hz"
        Title = "反转"; Visual = "父亲仍坐在餐桌旁稳住骗子，电话里传来民警反馈。"
        Line = "我不是不会用手机。我在拖住他，民警已经锁到号码了。"
        Motion = "slow_pull"; Tier = "A"
    },
    @{
        No = 7; Duration = 6.8; Keyframe = "screen_share"; Speaker = "民警"
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-2%"; EdgePitch = "-1Hz"
        Title = "点破"; Visual = "两部手机和包裹形成证据链。"
        Line = "他们骗的不是退款，是你替家人着急的那一分钟。"
        Motion = "evidence"; Tier = "B"
    },
    @{
        No = 8; Duration = 7.4; Keyframe = "family_final"; Speaker = "旁白"
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-4%"; EdgePitch = "-1Hz"
        Title = "公益收束"; Visual = "父女坐下，手机扣在桌上。"
        Line = "退款不到账先核实，屏幕共享别打开。真正的家人，会先让你慢下来。"
        Motion = "slow_pull"; Tier = "C"
    }
)

$scriptMd = @"
# 《退款之前》完整短剧剧本

题材：反诈升级 + 快递理赔 + 屏幕共享 + 亲情反转公益短剧
规格：竖屏 9:16，约 55-60 秒，30fps，含配音、台词、烧录字幕、BGM。
核心反转：女儿以为父亲不懂手机、只会添乱，最后发现父亲一直在拖住骗子，并已经联系民警锁定号码。

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

## 苏晴
- 年龄：27 岁
- 身份：社区护士，刚下夜班，替父亲网购血压仪。
- 外观锁定：短扎黑发，浅蓝护士服外搭米色外套，疲惫但心软。
- 表演：一开始急着处理退款，不想打扰父亲；中段误会父亲；反转后内疚又松一口气。
- 配音：zh-CN-XiaoxiaoNeural。

## 苏建国
- 年龄：58 岁
- 身份：退休公交司机，社区反诈宣讲的常客。
- 外观锁定：短灰发，藏蓝拉链外套，旧手机，餐桌上有血压仪包裹。
- 表演：表面不懂手机，实际稳住骗子、保护女儿。
- 配音：zh-CN-YunyangNeural，低速低音。

## 骗子
- 身份：伪装快递理赔客服。
- 话术：快递破损、十倍退款、屏幕共享、验证码不用说但会被看到。
- 表演：语速快、制造深夜紧迫感。

## 民警
- 身份：社区民警。
- 呈现：通过电话反馈和证据说明出现；不抢父女情绪。
"@
Write-Utf8 (Join-Path $OutPath "characters.md") $charactersMd

$continuityMd = @"
# 镜头连续性规则

1. 苏晴锁定：短扎黑发、浅蓝护士服、米色外套、夜班疲惫感、手机冷光。
2. 苏建国锁定：短灰发、藏蓝拉链外套、旧手机、血压仪包裹。
3. 骗局锁定：快递理赔、十倍退款、屏幕共享、银行卡核对。
4. 情绪递进：焦急处理 -> 被话术推着走 -> 误会父亲 -> 父亲反转 -> 公益收束。
5. 第一版稳定策略：同角色镜头复用关键帧，通过字幕、配音、轻微运动和证据镜头完成叙事。
6. 第二版 Wan2.2 替换候选：镜头 2、4、5、6。必须以当前关键帧为首帧，保留脸、衣服、手机、房间和光线。
7. 负面约束：禁止换脸、换衣、加新人、Logo、水印、可读 UI 字、线稿化、漫画化、黑白化、整帧大幅抖动。
"@
Write-Utf8 (Join-Path $OutPath "continuity-rules.md") $continuityMd

$planMd = @"
# 逐步优化记录

## V1 完整可看版
- 目标：完整剧本、角色、分镜、关键帧、Edge 配音、字幕、BGM、合成视频。
- 关键策略：Z-Image 生成 5 张稳定关键帧；同人物镜头复用首帧，降低漂移。
- 成片：final_refund_antifraud_family_v1.mp4
- 已完成修复：反转镜头从警务室改为复用父亲居家首帧，避免父亲被误读为民警；服装锁回藏蓝便装拉链外套。

## V2 真实动态升级
- 候选镜头：2、4、5、6。
- 模型：Wan2.2 I2V。
- 目标：把电话、屏幕共享、父亲劝阻、家中反转做成真实动态镜头。

## V3 质检修复
- 检测：人物漂移、服装漂移、字幕遮脸、黑屏、冻结、低细节、配音回退。
- 修复策略：单镜重跑、降低噪声、缩短帧数、保留同一合成链路。
"@
Write-Utf8 (Join-Path $OutPath "progression.md") $planMd

$comfyPlanMd = @"
# ComfyUI 生成方案

## V1 当前方案
- 关键帧工作流：`backend/src/main/resources/comfyui/workflows/image_z_image_turbo.json`
- 生成分辨率：720x1280
- 合成分辨率：1080x1920，30fps
- 配音：Edge TTS 神经音色，统一转 WAV 后响度归一。

## V2 Wan2.2 I2V 方案
- 工作流：`backend/src/main/resources/comfyui/workflows/video_wan2_2_14B_i2v.json`
- 替换镜头：2、4、5、6
- 建议参数：49 帧起步，24fps，LightX2V 4-step LoRA，`noise_aug_strength=0.032-0.045`。
- 合成：保留本版本 `voice/subtitles/shots` 结构，只替换 `wan_clips` 后重合成。
"@
Write-Utf8 (Join-Path $OutPath "comfyui-generation-plan.md") $comfyPlanMd

$storyboard = [pscustomobject]@{
    title = "退款之前"
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
            $result = Submit-ZImageKeyframe -Client $client -Prompt $frame.Prompt -Seed $frame.Seed -Prefix "niren_refund_antifraud_$($frame.Id)" -OutputFile $target
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
    $voiceManifest = @()

    foreach ($shot in $shots) {
        $idx = "{0:00}" -f $shot.No
        $audio = Join-Path $VoicePath "voice_$idx.wav"
        $ass = "shot_$idx.ass"
        $mp4 = Join-Path $ShotPath "shot_$idx.mp4"
        $keyframe = Join-Path $KeyframePath "$($shot.Keyframe).png"
        if (-not (Test-Path -LiteralPath $keyframe)) {
            throw "Missing keyframe for shot ${idx}: $keyframe"
        }

        $provider = New-EdgeVoiceLine -Shot $shot -Output $audio
        $voiceManifest += [pscustomobject]@{ shotNo = $shot.No; provider = $provider; file = $audio }
        $audioDuration = Get-DurationSeconds $audio
        $duration = [math]::Max([double]$shot.Duration, $audioDuration + 1.0)
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

        $audioDelay = 240
        $motion = switch ($shot.Motion) {
            "slow_pull" { "scale=1160:2062:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2-18*sin(t*0.24)':y='(ih-1920)/2-14*cos(t*0.21)'" }
            "phone_push" { "scale=1180:2098:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+24*sin(t*0.32)':y='(ih-1920)/2+18*cos(t*0.27)'" }
            "evidence" { "scale=1160:2062:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+22*sin(t*0.18)':y='(ih-1920)/2+16*cos(t*0.16)'" }
            default { "scale=1140:2027:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+16*sin(t*0.25)':y='(ih-1920)/2+12*cos(t*0.20)'" }
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
    & $Ffmpeg -y -i (Join-Path $OutPath "voice_only.mp4") -stream_loop -1 -i $BgmPath -filter_complex $mix -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -movflags +faststart (Join-Path $OutPath "final_refund_antifraud_family_v1.mp4")
    if ($LASTEXITCODE -ne 0) {
        throw "final mix failed"
    }
} finally {
    Pop-Location
}

$final = Join-Path $OutPath "final_refund_antifraud_family_v1.mp4"
$probe = & $Ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate,duration -show_entries format=duration,size -of json $final
Write-Utf8 (Join-Path $OutPath "ffprobe.json") ($probe -join "`n")
Write-Host "FINAL_VIDEO=$final"
