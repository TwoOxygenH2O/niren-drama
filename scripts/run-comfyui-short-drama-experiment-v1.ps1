param(
    [string]$OutDir = "output\comfyui-short-drama-experiment-v1",
    [string]$ComfyUrl = "http://127.0.0.1:8188",
    [string]$ComfyInput = "D:\Projects\ComfyUI-aki\ComfyUI\input",
    [string]$ComfyOutput = "D:\Projects\ComfyUI-aki\ComfyUI\output",
    [string]$PythonExe = "D:\Projects\ComfyUI-aki\python\python.exe",
    [ValidateSet("series_balanced", "standard", "quality_long")]
    [string]$WorkflowVariant = "series_balanced",
    [switch]$FastWan,
    [switch]$LongWan,
    [switch]$InterpolateMotion,
    [int]$AudioSampleRate = 48000,
    [switch]$ReuseKeyframes,
    [switch]$ReuseWan,
    [switch]$SkipWan,
    [switch]$SkipKeyframes,
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
$WanClipPath = Join-Path $OutPath "wan_clips"
$VoicePath = Join-Path $OutPath "voice"
$ShotPath = Join-Path $OutPath "shots"
$ComfyInputSubdir = "niren_short_drama_experiment_v1"
$ComfyInputPath = Join-Path $ComfyInput $ComfyInputSubdir
$ImageWorkflowPath = Join-Path $Root "backend\src\main\resources\comfyui\workflows\image_z_image_turbo.json"
$BgmPath = Join-Path $Root "backend\src\main\resources\assets\bgm_default.wav"

$WanWorkflowByVariant = @{
    series_balanced = Join-Path $Root "backend\src\main\resources\comfyui\workflows\video_wan2_2_14B_i2v_series_balanced.json"
    standard        = Join-Path $Root "backend\src\main\resources\comfyui\workflows\video_wan2_2_14B_i2v.json"
    quality_long    = Join-Path $Root "backend\src\main\resources\comfyui\workflows\video_wan2_2_14B_i2v_quality_long.json"
}
$WanWorkflowPath = $WanWorkflowByVariant[$WorkflowVariant]

New-Item -ItemType Directory -Force -Path $OutPath, $KeyframePath, $WanClipPath, $VoicePath, $ShotPath, $ComfyInputPath | Out-Null
Add-Type -AssemblyName System.Net.Http

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Add-JsonLine {
    param([string]$Path, [object]$Entry)
    $json = $Entry | ConvertTo-Json -Depth 30 -Compress
    [System.IO.File]::AppendAllText($Path, $json + "`n", $utf8NoBom)
}

function Get-DurationSeconds {
    param([string]$Path)
    $raw = & $Ffprobe -v error -show_entries format=duration -of default=nk=1:nw=1 $Path
    if ($LASTEXITCODE -ne 0) {
        throw "ffprobe duration failed for $Path"
    }
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
    $json = $Body | ConvertTo-Json -Depth 40 -Compress
    $content = [System.Net.Http.StringContent]::new($json, $utf8NoBom, "application/json")
    $response = $Client.PostAsync($Url, $content).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "ComfyUI POST failed $Url HTTP $([int]$response.StatusCode): $text"
    }
    return $text | ConvertFrom-Json
}

function Get-ComfyAssetPath {
    param([object]$Item)
    if ($null -eq $Item -or $Item.PSObject.Properties["filename"] -eq $null) {
        return $null
    }
    $file = Join-Path $ComfyOutput $Item.filename
    if ($Item.PSObject.Properties["subfolder"] -and $Item.subfolder) {
        $file = Join-Path (Join-Path $ComfyOutput $Item.subfolder) $Item.filename
    }
    return $file
}

function Find-ComfyVideoOutput {
    param([object]$Entry)
    foreach ($prop in $Entry.outputs.PSObject.Properties) {
        $value = $prop.Value
        foreach ($mediaName in @("videos", "gifs", "images")) {
            $mediaProp = $value.PSObject.Properties[$mediaName]
            if ($null -eq $mediaProp) {
                continue
            }
            foreach ($item in @($mediaProp.Value)) {
                $source = Get-ComfyAssetPath -Item $item
                if (-not $source -or -not (Test-Path -LiteralPath $source)) {
                    continue
                }
                $extension = [System.IO.Path]::GetExtension($source).ToLowerInvariant()
                if ($extension -in @(".mp4", ".webm", ".mov")) {
                    return $source
                }
            }
        }
    }
    return $null
}

function Submit-ZImageKeyframe {
    param(
        [System.Net.Http.HttpClient]$Client,
        [hashtable]$Frame,
        [string]$OutputFile
    )
    $prefix = "niren_experiment_v1_$($Frame.Id)"
    $workflow = Get-Content -Raw -LiteralPath $ImageWorkflowPath | ConvertFrom-Json -AsHashtable
    $workflow["4"]["inputs"]["text"] = $Frame.Prompt
    $workflow["6"]["inputs"]["width"] = 720
    $workflow["6"]["inputs"]["height"] = 1280
    $workflow["8"]["inputs"]["seed"] = [int64]$Frame.Seed
    $workflow["10"]["inputs"]["filename_prefix"] = $prefix

    $submit = Invoke-ComfyPostJson -Client $Client -Url "$ComfyUrl/prompt" -Body @{ prompt = $workflow; client_id = [guid]::NewGuid().ToString() }
    $promptId = $submit.prompt_id
    if (-not $promptId) {
        throw "ComfyUI did not return prompt_id for keyframe $($Frame.Id)"
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
            throw "ComfyUI keyframe $promptId failed for $($Frame.Id): $messages"
        }
        $outputNode = $entry.outputs.PSObject.Properties["10"].Value
        if ($outputNode -eq $null -or $outputNode.images.Count -lt 1) {
            continue
        }
        $image = $outputNode.images[0]
        $source = Get-ComfyAssetPath -Item $image
        if (-not $source -or -not (Test-Path -LiteralPath $source)) {
            continue
        }
        Copy-Item -LiteralPath $source -Destination $OutputFile -Force
        return [pscustomobject]@{ promptId = $promptId; source = $source; output = $OutputFile }
    }

    throw "Timed out waiting for keyframe $($Frame.Id) ($promptId)"
}

function Set-WanInputImage {
    param([System.Collections.IDictionary]$Workflow, [string]$InputImage)
    $count = 0
    foreach ($nodeId in @($Workflow.Keys)) {
        $node = $Workflow[$nodeId]
        if (-not ($node -is [System.Collections.IDictionary]) -or -not $node.Contains("inputs")) {
            continue
        }
        $inputs = $node["inputs"]
        if (($inputs -is [System.Collections.IDictionary]) -and $inputs.Contains("image")) {
            $inputs["image"] = $InputImage
            $count++
        }
    }
    if ($count -lt 1) {
        throw "Wan workflow has no image input nodes: $WanWorkflowPath"
    }
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
    Set-WanInputImage -Workflow $workflow -InputImage $inputRel
    $workflow["12"]["inputs"]["positive_prompt"] = $Shot.WanPrompt
    $workflow["12"]["inputs"]["negative_prompt"] = $negativePrompt

    if ($FastWan) {
        $workflow["18"]["inputs"]["num_frames"] = 49
        $workflow["18"]["inputs"]["noise_aug_strength"] = 0.032
        $workflow["18"]["inputs"]["start_latent_strength"] = 0.965
        $workflow["18"]["inputs"]["end_latent_strength"] = 0.84
        $workflow["18"]["inputs"]["augment_empty_frames"] = 0.035
        foreach ($nodeId in @("21", "22")) {
            $workflow[$nodeId]["inputs"]["steps"] = 6
            $workflow[$nodeId]["inputs"]["cfg"] = 1.08
            $workflow[$nodeId]["inputs"]["seed"] = [int64](27070100 + [int]$Shot.No)
        }
        $workflow["21"]["inputs"]["end_step"] = 3
        $workflow["22"]["inputs"]["start_step"] = 3
        $workflow["24"]["inputs"]["frame_rate"] = 24
        $workflow["24"]["inputs"]["crf"] = 18
    }

    if ($LongWan) {
        $workflow["18"]["inputs"]["num_frames"] = 81
        $workflow["18"]["inputs"]["noise_aug_strength"] = 0.038
        $workflow["18"]["inputs"]["start_latent_strength"] = 0.94
        $workflow["18"]["inputs"]["end_latent_strength"] = 0.80
        $workflow["18"]["inputs"]["augment_empty_frames"] = 0.055
        $workflow["24"]["inputs"]["frame_rate"] = 16
        $workflow["24"]["inputs"]["crf"] = 18
    }

    $workflow["24"]["inputs"]["filename_prefix"] = "niren_experiment_v1_wan_shot$idx"
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
            throw "ComfyUI Wan prompt $promptId failed for shot ${idx}: $messages"
        }
        $source = Find-ComfyVideoOutput -Entry $entry
        if ($source) {
            Copy-Item -LiteralPath $source -Destination $OutputFile -Force
            return [pscustomobject]@{ promptId = $promptId; source = $source; output = $OutputFile }
        }
    }

    throw "Timed out waiting for Wan shot $idx ($promptId)"
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
    & $Ffmpeg -y -i $raw -ac 1 -ar $AudioSampleRate -filter:a "aresample=$AudioSampleRate,loudnorm=I=-16:TP=-1.5:LRA=11" $Output | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $Output)) {
        throw "ffmpeg voice convert failed for shot $idx"
    }
}

function Write-ProbeJson {
    param([string]$Path, [string]$OutputJson)
    $probe = & $Ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate,duration -show_entries format=duration,size -of json $Path
    if ($LASTEXITCODE -ne 0) {
        throw "ffprobe metadata failed for $Path"
    }
    Write-Utf8 $OutputJson ($probe -join "`n")
}

$negativePrompt = "low quality, blurry, distorted, face morphing, identity drift, different person, changing clothes, new person, scene jump, camera cut, frozen frame, slideshow, whole-frame pan, flicker, jitter, subtitles, text, logo, watermark, sketch, line art, manga, monochrome, extra fingers, bad hands"

$keyframes = @(
    @{
        Id     = "daughter_breakroom"
        Seed   = 270101
        Prompt = "vertical 9:16 photorealistic Chinese short drama still, tired 27-year-old Chinese emergency doctor daughter in white doctor coat over light blue scrubs, short black hair, hospital break room at night, phone screen glow on worried face, parcel on table, cinematic realistic lighting, commercial short drama, no text, no logo, no watermark"
    },
    @{
        Id     = "phone_evidence"
        Seed   = 270102
        Prompt = "vertical 9:16 photorealistic Chinese short drama evidence still, two smartphones on wooden table, delivery refund page and screen-share warning style interface but no readable text, blood pressure monitor parcel nearby, realistic reflections, tense night lighting, no logo, no watermark, no readable words"
    },
    @{
        Id     = "father_home"
        Seed   = 270103
        Prompt = "vertical 9:16 photorealistic Chinese short drama still, 58-year-old Chinese father with short gray hair wearing plain navy zip jacket, modest dining room at night, old smartphone near his ear, blood pressure monitor parcel on table, calm protective expression, warm home lighting, no text, no logo, no watermark"
    },
    @{
        Id     = "family_final"
        Seed   = 270104
        Prompt = "vertical 9:16 photorealistic Chinese short drama still, same daughter in white doctor coat and same father in navy jacket sitting at modest dining table, phone face-down, warm restrained emotional ending, realistic family drama lighting, commercial short drama, no text, no logo, no watermark"
    }
)

$globalWanPrefix = "Commercial vertical Chinese live-action short-drama video, one continuous 9:16 take. Use the input image as the exact first frame. Preserve face, hairstyle, wardrobe, props, lighting, camera angle, scene layout, color grade, and realistic skin texture. Actor-local motion only: breathing, blinking, eye movement, thumb movement, hand tension, phone glow, cloth micro-motion, subtle light shift. No cuts, no new people, no scene jump."

$shots = @(
    @{
        No = 1; Duration = 5.0; Keyframe = "daughter_breakroom"; Speaker = "苏晴"; EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-5%"; EdgePitch = "-1Hz"
        Title = "可疑来电"; Line = "爸的血压仪退款？怎么会半夜打来。"
        WanPrompt = "$globalWanPrefix A tired emergency doctor daughter notices a suspicious refund call, looks down at the phone, blinks, exhales, thumb hesitates above the screen, worried but controlled expression."
    },
    @{
        No = 2; Duration = 5.0; Keyframe = "daughter_breakroom"; Speaker = "骗子"; EdgeVoice = "zh-CN-YunxiNeural"; EdgeRate = "+2%"; EdgePitch = "+0Hz"
        Title = "话术施压"; Line = "您父亲的订单异常，十倍理赔现在就能到账。"
        WanPrompt = "$globalWanPrefix Same daughter in the same hospital break room listens to the call, guilt rises, she grips the phone tighter, eyes shift from screen to parcel, subtle head turn, stable identity and wardrobe."
    },
    @{
        No = 3; Duration = 5.0; Keyframe = "phone_evidence"; Speaker = "旁白"; EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-4%"; EdgePitch = "-1Hz"
        Title = "危险动作"; Line = "他们要的不是验证码，是你打开屏幕的那一秒。"
        WanPrompt = "$globalWanPrefix Close-up evidence shot, two phones remain on table with unreadable refund and screen-share warning style screens, soft phone glow changes, a hand hesitates above one phone, tiny reflection movement, no readable text."
    },
    @{
        No = 4; Duration = 5.0; Keyframe = "father_home"; Speaker = "父亲"; EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-8%"; EdgePitch = "-4Hz"
        Title = "父亲拖延"; Line = "我年纪大，动作慢，你再说一遍。"
        WanPrompt = "$globalWanPrefix Same father at the same dining table calmly holds old phone to his ear, speaks slowly, blinks, fingers tense on table, protective calm expression, stable face and navy jacket."
    },
    @{
        No = 5; Duration = 5.0; Keyframe = "daughter_breakroom"; Speaker = "苏晴"; EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-3%"; EdgePitch = "-1Hz"
        Title = "停手"; Line = "不对，退款不需要共享屏幕。"
        WanPrompt = "$globalWanPrefix Same daughter suddenly realizes the trap, stops her thumb before tapping, pulls the phone back, eyes sharpen, breath steadies, subtle screen glow, no scene change."
    },
    @{
        No = 6; Duration = 5.0; Keyframe = "family_final"; Speaker = "旁白"; EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-4%"; EdgePitch = "-1Hz"
        Title = "慢下来"; Line = "真正的家人，会先让你慢下来。"
        WanPrompt = "$globalWanPrefix Daughter and father sit quietly at the table, phone face-down, both calm down, daughter lowers her eyes, father gives a restrained nod, warm family ending, stable identities and wardrobe."
    }
)

$storyboard = [pscustomobject]@{
    title           = "别急着转账"
    date            = (Get-Date).ToString("s")
    workflowVariant = $WorkflowVariant
    keyframes       = $keyframes
    shots           = $shots
}
Write-Utf8 (Join-Path $OutPath "storyboard.json") ($storyboard | ConvertTo-Json -Depth 20)

$logPath = Join-Path $OutPath "experiment-log.jsonl"
Add-JsonLine $logPath ([pscustomobject]@{
    type            = "run_start"
    at              = (Get-Date).ToString("s")
    workflowVariant = $WorkflowVariant
    fastWan         = [bool]$FastWan
    longWan         = [bool]$LongWan
    interpolate     = [bool]$InterpolateMotion
    audioSampleRate = $AudioSampleRate
    wanWorkflow     = $WanWorkflowPath
})

if (-not (Test-Path -LiteralPath $WanWorkflowPath)) {
    throw "Missing Wan workflow: $WanWorkflowPath"
}
if (-not (Test-Path -LiteralPath $ImageWorkflowPath)) {
    throw "Missing image workflow: $ImageWorkflowPath"
}

$client = New-ComfyClient
Invoke-ComfyGetJson -Client $client -Url "$ComfyUrl/system_stats" | Out-Null

foreach ($frame in $keyframes) {
    $target = Join-Path $KeyframePath "$($frame.Id).png"
    if ($SkipKeyframes -or ($ReuseKeyframes -and (Test-Path -LiteralPath $target))) {
        Add-JsonLine $logPath ([pscustomobject]@{ type = "keyframe"; id = $frame.Id; reused = $true; output = $target })
        continue
    }
    $result = Submit-ZImageKeyframe -Client $client -Frame $frame -OutputFile $target
    Add-JsonLine $logPath ([pscustomobject]@{ type = "keyframe"; id = $frame.Id; reused = $false; promptId = $result.promptId; output = $target; source = $result.source })
}

foreach ($shot in $shots) {
    $idx = "{0:00}" -f $shot.No
    $keyframe = Join-Path $KeyframePath "$($shot.Keyframe).png"
    $wanClip = Join-Path $WanClipPath "wan_shot_$idx.mp4"
    if (-not (Test-Path -LiteralPath $keyframe)) {
        throw "Missing keyframe for shot ${idx}: $keyframe"
    }
    if ($SkipWan -or ($ReuseWan -and (Test-Path -LiteralPath $wanClip))) {
        Add-JsonLine $logPath ([pscustomobject]@{ type = "wan"; shotNo = $shot.No; reused = $true; output = $wanClip })
        continue
    }
    $result = Submit-Wan22Clip -Client $client -Shot $shot -ImagePath $keyframe -OutputFile $wanClip
    Add-JsonLine $logPath ([pscustomobject]@{ type = "wan"; shotNo = $shot.No; reused = $false; promptId = $result.promptId; output = $wanClip; source = $result.source; prompt = $shot.WanPrompt; negative = $negativePrompt })
}

$shotFiles = @()
$srt = ""
$timelineStart = 0.0

Push-Location $OutPath
try {
    foreach ($shot in $shots) {
        $idx = "{0:00}" -f $shot.No
        $wanClip = Join-Path $WanClipPath "wan_shot_$idx.mp4"
        $audio = Join-Path $VoicePath "voice_$idx.wav"
        $mp4 = Join-Path $ShotPath "shot_$idx.mp4"
        $assName = "subtitle_$idx.ass"
        $assPath = Join-Path $OutPath $assName

        if (-not (Test-Path -LiteralPath $wanClip)) {
            throw "Missing Wan clip for shot ${idx}: $wanClip"
        }
        New-EdgeVoiceLine -Shot $shot -Output $audio

        $targetDuration = [double]$shot.Duration
        $voiceDuration = Get-DurationSeconds $audio
        $duration = [math]::Max($targetDuration, $voiceDuration + 0.5)
        $durationText = $duration.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
        $wanDuration = Get-DurationSeconds $wanClip
        $ptsFactor = ($duration / [math]::Max(0.1, $wanDuration)).ToString("0.######", [Globalization.CultureInfo]::InvariantCulture)
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
        Write-Utf8 $assPath $assText

        $audioDelay = 180
        $rateFilter = if ($InterpolateMotion) {
            "minterpolate=fps=30:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1"
        } else {
            "fps=30"
        }
        $filter = "[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setpts=${ptsFactor}*PTS,trim=0:$durationText,$rateFilter,eq=contrast=1.02:saturation=1.02,unsharp=5:5:0.22,ass=$assName[v];[1:a]adelay=$audioDelay|$audioDelay,apad,atrim=0:$durationText,aresample=$AudioSampleRate,loudnorm=I=-16:TP=-1.5:LRA=11[a]"
        & $Ffmpeg -y -i $wanClip -i $audio -filter_complex $filter -map "[v]" -map "[a]" -r 30 -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -c:a aac -b:a 160k -shortest $mp4
        if ($LASTEXITCODE -ne 0) {
            throw "ffmpeg composition failed for shot $idx"
        }

        Write-ProbeJson -Path $mp4 -OutputJson (Join-Path $ShotPath "shot_${idx}_ffprobe.json")
        Add-JsonLine $logPath ([pscustomobject]@{ type = "shot_compose"; shotNo = $shot.No; output = $mp4; targetDuration = $targetDuration; composedDuration = (Get-DurationSeconds $mp4) })

        $shotFiles += $mp4
        $srt += "$($shot.No)`r`n"
        $srt += "$(Format-SrtTime ($timelineStart + 0.20)) --> $(Format-SrtTime ($timelineStart + $duration))`r`n"
        $srt += "$($shot.Speaker)：$($shot.Line)`r`n`r`n"
        $timelineStart += $duration
    }
} finally {
    Pop-Location
}

Write-Utf8 (Join-Path $OutPath "subtitles.srt") $srt
$concatLines = $shotFiles | ForEach-Object { "file '$($_.Replace('\', '/'))'" }
Write-Utf8 (Join-Path $OutPath "concat.txt") ($concatLines -join "`n")

$voiceOnly = Join-Path $OutPath "voice_only.mp4"
& $Ffmpeg -y -f concat -safe 0 -i (Join-Path $OutPath "concat.txt") -c copy $voiceOnly
if ($LASTEXITCODE -ne 0) {
    throw "ffmpeg concat failed"
}

$final = Join-Path $OutPath "final_comfyui_short_drama_v1.mp4"
if (Test-Path -LiteralPath $BgmPath) {
    $totalDuration = Get-DurationSeconds $voiceOnly
    $totalText = $totalDuration.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
    $mix = "[1:a]aresample=$AudioSampleRate,atrim=0:$totalText,volume=0.055[bgm];[0:a]aresample=$AudioSampleRate,loudnorm=I=-16:TP=-1.5:LRA=12[voice];[bgm][voice]sidechaincompress=threshold=0.022:ratio=10:attack=10:release=300[duck];[voice][duck]amix=inputs=2:duration=first:weights='1 0.26',aresample=$AudioSampleRate[a]"
    & $Ffmpeg -y -i $voiceOnly -stream_loop -1 -i $BgmPath -filter_complex $mix -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -ar $AudioSampleRate -movflags +faststart $final
} else {
    & $Ffmpeg -y -i $voiceOnly -c:v copy -c:a aac -b:a 192k -ar $AudioSampleRate -movflags +faststart $final
}
if ($LASTEXITCODE -ne 0) {
    throw "final mix failed"
}

Write-ProbeJson -Path $final -OutputJson (Join-Path $OutPath "final_ffprobe.json")
Add-JsonLine $logPath ([pscustomobject]@{ type = "run_complete"; at = (Get-Date).ToString("s"); final = $final; duration = (Get-DurationSeconds $final) })

$reviewPath = Join-Path $OutPath "review.md"
$experimentLogPath = Join-Path $OutPath "experiment-log.jsonl"
$finalProbePath = Join-Path $OutPath "final_ffprobe.json"

$review = @"
# ComfyUI Short Drama Experiment V1 Review

Final video: $final

For each shot, fill this in after watching:

| Shot | Usability | Usable seconds | Main issue | Next retry focus | One-sentence review |
| --- | --- | --- | --- | --- | --- |
| 1 | usable / repairable / rejected |  | identity drift / wardrobe drift / weak motion / bad acting / style drift / continuity break / awkward cut / other |  |  |
| 2 | usable / repairable / rejected |  | identity drift / wardrobe drift / weak motion / bad acting / style drift / continuity break / awkward cut / other |  |  |
| 3 | usable / repairable / rejected |  | identity drift / wardrobe drift / weak motion / bad acting / style drift / continuity break / awkward cut / other |  |  |
| 4 | usable / repairable / rejected |  | identity drift / wardrobe drift / weak motion / bad acting / style drift / continuity break / awkward cut / other |  |  |
| 5 | usable / repairable / rejected |  | identity drift / wardrobe drift / weak motion / bad acting / style drift / continuity break / awkward cut / other |  |  |
| 6 | usable / repairable / rejected |  | identity drift / wardrobe drift / weak motion / bad acting / style drift / continuity break / awkward cut / other |  |  |

Run summary:

- Workflow variant: $WorkflowVariant
- Fast Wan: $([bool]$FastWan)
- Long Wan: $([bool]$LongWan)
- Motion interpolation: $([bool]$InterpolateMotion)
- Audio sample rate: $AudioSampleRate
- Experiment log: $experimentLogPath
- Final ffprobe: $finalProbePath
"@
Write-Utf8 $reviewPath $review
Write-Host "FINAL_VIDEO=$final"
Write-Host "REVIEW_FILE=$reviewPath"
