param(
    [string]$SourceDir = "output\public-service-refund-antifraud-v1",
    [string]$OutDir = "output\public-service-refund-antifraud-v3-wan22",
    [string]$ComfyUrl = "http://127.0.0.1:8188",
    [string]$ComfyInput = "D:\Projects\ComfyUI-aki\ComfyUI\input",
    [string]$ComfyOutput = "D:\Projects\ComfyUI-aki\ComfyUI\output",
    [string]$PythonExe = "D:\Projects\ComfyUI-aki\python\python.exe",
    [int[]]$WanShotNumbers = @(1, 2, 3, 5, 6, 8),
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
$UiAssetPath = Join-Path $OutPath "ui_assets"
$WanWorkflowPath = Join-Path $Root "backend\src\main\resources\comfyui\workflows\video_wan2_2_14B_i2v.json"
$BgmPath = Join-Path $Root "backend\src\main\resources\assets\bgm_default.wav"
$ComfyInputSubdir = "niren_refund_antifraud_v3_wan22"
$ComfyInputPath = Join-Path $ComfyInput $ComfyInputSubdir

New-Item -ItemType Directory -Force -Path $OutPath, $KeyframePath, $WanClipPath, $VoicePath, $ShotPath, $UiAssetPath, $ComfyInputPath | Out-Null
Get-ChildItem -LiteralPath (Join-Path $SourcePath "keyframes") -Filter "*.png" | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $KeyframePath -Force
}

Add-Type -AssemblyName System.Speech
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Drawing

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

function New-UiPng {
    param(
        [string]$Path,
        [ValidateSet("share", "evidence")]
        [string]$Kind
    )
    $bmp = [System.Drawing.Bitmap]::new(1080, 1920)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit

    $bg = [System.Drawing.ColorTranslator]::FromHtml("#EEF3F7")
    $ink = [System.Drawing.ColorTranslator]::FromHtml("#111827")
    $muted = [System.Drawing.ColorTranslator]::FromHtml("#6B7280")
    $blue = [System.Drawing.ColorTranslator]::FromHtml("#1677FF")
    $red = [System.Drawing.ColorTranslator]::FromHtml("#DC2626")
    $green = [System.Drawing.ColorTranslator]::FromHtml("#16A34A")
    $white = [System.Drawing.Color]::White

    $g.Clear($bg)
    $fontTitle = [System.Drawing.Font]::new("Microsoft YaHei UI", 46, [System.Drawing.FontStyle]::Bold)
    $fontH = [System.Drawing.Font]::new("Microsoft YaHei UI", 34, [System.Drawing.FontStyle]::Bold)
    $fontBody = [System.Drawing.Font]::new("Microsoft YaHei UI", 28, [System.Drawing.FontStyle]::Regular)
    $fontSmall = [System.Drawing.Font]::new("Microsoft YaHei UI", 22, [System.Drawing.FontStyle]::Regular)
    $fontButton = [System.Drawing.Font]::new("Microsoft YaHei UI", 30, [System.Drawing.FontStyle]::Bold)

    $brushInk = [System.Drawing.SolidBrush]::new($ink)
    $brushMuted = [System.Drawing.SolidBrush]::new($muted)
    $brushBlue = [System.Drawing.SolidBrush]::new($blue)
    $brushRed = [System.Drawing.SolidBrush]::new($red)
    $brushGreen = [System.Drawing.SolidBrush]::new($green)
    $brushWhite = [System.Drawing.SolidBrush]::new($white)
    $brushCard = [System.Drawing.SolidBrush]::new($white)

    $phoneX = 130
    $phoneY = 135
    $phoneW = 820
    $phoneH = 1600
    $g.FillRectangle([System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#0F172A")), $phoneX, $phoneY, $phoneW, $phoneH)
    $g.FillRectangle($brushCard, $phoneX + 28, $phoneY + 34, $phoneW - 56, $phoneH - 68)

    if ($Kind -eq "share") {
        $g.FillRectangle($brushBlue, $phoneX + 28, $phoneY + 34, $phoneW - 56, 150)
        $g.DrawString("快递理赔中心", $fontTitle, $brushWhite, [System.Drawing.RectangleF]::new($phoneX + 78, $phoneY + 70, 650, 70))
        $g.DrawString("屏幕共享请求", $fontH, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 82, $phoneY + 270, 640, 60))
        $g.DrawString("陌生人正在请求查看你的手机屏幕", $fontBody, $brushMuted, [System.Drawing.RectangleF]::new($phoneX + 82, $phoneY + 340, 660, 56))
        $g.FillRectangle([System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#FEF2F2")), $phoneX + 82, $phoneY + 430, 656, 290)
        $g.DrawString("风险提醒", $fontH, $brushRed, [System.Drawing.RectangleF]::new($phoneX + 122, $phoneY + 470, 540, 58))
        $g.DrawString("开启后，对方可以看到验证码、银行卡、支付页面和聊天内容。", $fontBody, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 122, $phoneY + 540, 560, 150))
        $g.DrawString("如非本人主动联系官方客服，请立即拒绝。", $fontBody, $brushRed, [System.Drawing.RectangleF]::new($phoneX + 122, $phoneY + 640, 560, 90))
        $g.FillRectangle([System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#E5E7EB")), $phoneX + 82, $phoneY + 810, 310, 92)
        $g.DrawString("拒绝", $fontButton, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 202, $phoneY + 834, 120, 45))
        $g.FillRectangle($brushRed, $phoneX + 430, $phoneY + 810, 310, 92)
        $g.DrawString("停止共享", $fontButton, $brushWhite, [System.Drawing.RectangleF]::new($phoneX + 515, $phoneY + 834, 170, 45))
        $g.DrawString("订单号  ND-2026-0626", $fontSmall, $brushMuted, [System.Drawing.RectangleF]::new($phoneX + 82, $phoneY + 1010, 620, 42))
        $g.DrawString("官方客服不会索要屏幕共享权限", $fontSmall, $brushMuted, [System.Drawing.RectangleF]::new($phoneX + 82, $phoneY + 1060, 620, 42))
    } else {
        $g.FillRectangle([System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#0B3B2E")), $phoneX + 28, $phoneY + 34, $phoneW - 56, 150)
        $g.DrawString("反诈提醒", $fontTitle, $brushWhite, [System.Drawing.RectangleF]::new($phoneX + 78, $phoneY + 70, 650, 70))
        $g.DrawString("风险已拦截", $fontTitle, $brushGreen, [System.Drawing.RectangleF]::new($phoneX + 82, $phoneY + 270, 640, 70))
        $g.DrawString("检测到：快递理赔 + 屏幕共享 + 验证码诱导", $fontBody, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 82, $phoneY + 360, 660, 85))
        $g.FillRectangle([System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#ECFDF5")), $phoneX + 82, $phoneY + 490, 656, 330)
        $g.DrawString("处理建议", $fontH, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 122, $phoneY + 530, 540, 58))
        $g.DrawString("1. 关闭屏幕共享", $fontBody, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 122, $phoneY + 610, 560, 50))
        $g.DrawString("2. 不说验证码", $fontBody, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 122, $phoneY + 670, 560, 50))
        $g.DrawString("3. 回拨官方电话核实", $fontBody, $brushInk, [System.Drawing.RectangleF]::new($phoneX + 122, $phoneY + 730, 560, 50))
        $g.FillRectangle($brushGreen, $phoneX + 82, $phoneY + 900, 656, 96)
        $g.DrawString("已通知家人核实", $fontButton, $brushWhite, [System.Drawing.RectangleF]::new($phoneX + 280, $phoneY + 925, 360, 50))
        $g.DrawString("真正的家人，会让你慢下来。", $fontBody, $brushMuted, [System.Drawing.RectangleF]::new($phoneX + 82, $phoneY + 1100, 640, 60))
    }

    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $fontTitle.Dispose(); $fontH.Dispose(); $fontBody.Dispose(); $fontSmall.Dispose(); $fontButton.Dispose()
    $brushInk.Dispose(); $brushMuted.Dispose(); $brushBlue.Dispose(); $brushRed.Dispose(); $brushGreen.Dispose(); $brushWhite.Dispose(); $brushCard.Dispose()
    $g.Dispose()
    $bmp.Dispose()
}

New-UiPng -Path (Join-Path $UiAssetPath "ui_screen_share.png") -Kind "share"
New-UiPng -Path (Join-Path $UiAssetPath "ui_antifraud.png") -Kind "evidence"

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
    $workflow["18"]["inputs"]["start_latent_strength"] = 0.965
    $workflow["18"]["inputs"]["end_latent_strength"] = 0.84
    $workflow["18"]["inputs"]["augment_empty_frames"] = 0.035
    foreach ($nodeId in @("21", "22")) {
        $workflow[$nodeId]["inputs"]["steps"] = 6
        $workflow[$nodeId]["inputs"]["cfg"] = 1.08
        $workflow[$nodeId]["inputs"]["seed"] = [int64](2606232000 + $Shot.No)
    }
    $workflow["21"]["inputs"]["end_step"] = 3
    $workflow["22"]["inputs"]["start_step"] = 3
    $workflow["24"]["inputs"]["frame_rate"] = 24
    $workflow["24"]["inputs"]["filename_prefix"] = "niren_refund_antifraud_v3_wan_shot$idx"
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
            & $Ffmpeg -y -i $raw -af "aresample=48000,highpass=f=55,acompressor=threshold=-21dB:ratio=1.35:attack=18:release=220,alimiter=limit=0.92,loudnorm=I=-16:TP=-1.5:LRA=13" -ac 1 -ar 48000 $Output
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
        No = 1; Duration = 5.4; Keyframe = "daughter_call"; Speaker = "旁白"; SapiVoice = "Microsoft Huihui Desktop"; SapiRate = -1
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-14%"; EdgePitch = "-2Hz"
        Title = "亏欠来电"; Visual = "急诊医生苏晴夜班后看到父亲买血压仪的快递理赔电话，神色疲惫。"
        Line = "夜班刚结束，苏晴接到一个关于父亲的理赔电话。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese anti-fraud family short-drama opening video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same young Chinese emergency doctor Su Qing, short black hair, white doctor coat over light blue scrubs, same hospital break room lighting and parcel on table. She stands still at first, then blinks, exhales, looks down at the phone, thumb slightly moves, tired and worried after night shift. Stable face, stable white coat and blue scrubs, locked camera, no new person, no scene jump, no readable text, no subtitles."
    },
    @{
        No = 2; Duration = 6.2; Keyframe = "daughter_call"; Speaker = "骗子"; SapiVoice = "Microsoft Kangkang"; SapiRate = 1
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "+4%"; EdgePitch = "-1Hz"
        Title = "孝心诱饵"; Visual = "骗子利用女儿对父亲的亏欠感，催她立即处理退款。"
        Line = "苏女士，您爸买的血压仪批次异常。现在处理，能退一千九百八。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese anti-fraud family short-drama video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same young Chinese emergency doctor Su Qing, same short black hair, same white doctor coat over light blue scrubs, same hospital break room lighting. She listens to a suspicious refund call after a night shift, tired eyes, guilt and worry rising, phone near her face, subtle breathing, small thumb movement, slight eye shift, realistic micro-expression. Stable identity, stable white coat, stable blue scrubs, locked camera, no scene change, no new person, no text in image."
    },
    @{
        No = 3; Duration = 5.8; Keyframe = "daughter_call"; Speaker = "苏晴"; SapiVoice = "Microsoft Huihui Desktop"; SapiRate = -1
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-12%"; EdgePitch = "-2Hz"
        Title = "替他省事"; Visual = "苏晴怕父亲又被流程折腾，决定自己替他处理。"
        Line = "别打给我爸。他眼睛不好，流程发我，我替他弄。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese family short-drama video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same emergency doctor Su Qing, same short black hair, same white doctor coat over light blue scrubs, same break room. She becomes anxious, lowers her voice, holds the phone tighter, hesitates, then nods slightly as if deciding to handle the refund for her father. Natural face and hand motion, no lip-sync exaggeration, stable face, stable white coat, stable blue scrubs, locked camera, no new person, no scene jump, no text or subtitles."
    },
    @{
        No = 4; Duration = 6.4; Keyframe = "ui_screen_share"; Speaker = "骗子"; SapiVoice = "Microsoft Kangkang"; SapiRate = 2
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "+5%"; EdgePitch = "+0Hz"
        Title = "交出眼睛"; Visual = "屏幕共享页面、理赔页面和血压仪包裹形成危险证据画面。"
        Line = "开屏幕共享，我帮您走绿色通道。验证码不用说，页面会自动过。"
        Motion = "controlled_ui"; Tier = "A"
    },
    @{
        No = 5; Duration = 6.2; Keyframe = "father_phone"; Speaker = "父亲"; SapiVoice = "Microsoft Kangkang"; SapiRate = -2
        EdgeVoice = "zh-CN-YunxiNeural"; EdgeRate = "-18%"; EdgePitch = "-7Hz"
        Title = "父亲拦下"; Visual = "父亲突然来电，声音慢但坚定，女儿误以为他又不会用手机。"
        Line = "小晴，别开共享。钱可以慢慢退，眼睛不能交出去。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese family anti-fraud short-drama video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same 58-year-old Chinese father Su Jianguo, short gray hair, plain navy zip jacket, modest dining room, blood pressure monitor on table. He holds an old phone to his ear, calm but protective, slow mouth movement, blinking, tense fingers on the table, natural breathing. Locked camera, stable face and clothes, no police uniform, no new person, no scene change."
    },
    @{
        No = 6; Duration = 6.4; Keyframe = "father_phone"; Speaker = "父亲"; SapiVoice = "Microsoft Kangkang"; SapiRate = -2
        EdgeVoice = "zh-CN-YunxiNeural"; EdgeRate = "-18%"; EdgePitch = "-7Hz"
        Title = "真正反转"; Visual = "父亲没有慌，他压着声音继续拖住骗子，手机旁放着报警记录。"
        Line = "我不是不会用。我一直在拖他，民警已经锁到号了。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese family anti-fraud reveal video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same father, same navy zip jacket, same dining table, same phone and blood pressure monitor. He lowers his eyes to the phone, pauses, then looks forward with quiet protective expression as if revealing he has been delaying the scammer. Subtle mouth movement, slight hand relaxation, no dramatic shake, no police uniform, no new person, no scene jump, stable identity, realistic live action."
    },
    @{
        No = 7; Duration = 5.6; Keyframe = "ui_antifraud"; Speaker = "民警"; SapiVoice = "Microsoft Huihui"; SapiRate = -1
        EdgeVoice = "zh-CN-YunyangNeural"; EdgeRate = "-8%"; EdgePitch = "-2Hz"
        Title = "骗局点破"; Visual = "手机、包裹和屏幕共享页面构成证据链。"
        Line = "他们不是怕你不懂，是怕你先问一句家人。"
        Motion = "controlled_ui"; Tier = "A"
    },
    @{
        No = 8; Duration = 6.2; Keyframe = "family_final"; Speaker = "苏晴"; SapiVoice = "Microsoft Huihui Desktop"; SapiRate = -1
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-13%"; EdgePitch = "-2Hz"
        Title = "女儿道歉"; Visual = "父女坐在餐桌旁，女儿把手机扣下，第一次慢下来。"
        Line = "爸，对不起。我总想替你快一点，差点把你也交出去。"
        Motion = "wan"; Tier = "A"
        WanPrompt = "Commercial vertical Chinese family short-drama emotional ending video, one continuous 9:16 live-action shot. Use the input image as the exact first frame and keep the same daughter Su Qing and father Su Jianguo, same clothes, same dining table, warm modest home lighting. Daughter gently puts the phone face-down on the table, father looks at her quietly, both calm down, restrained emotion, tiny hand movement, subtle breathing, realistic family moment, stable identity and wardrobe, locked camera, no new person, no scene change, no text or subtitles."
    },
    @{
        No = 9; Duration = 5.8; Keyframe = "family_final"; Speaker = "旁白"; SapiVoice = "Microsoft Huihui Desktop"; SapiRate = -1
        EdgeVoice = "zh-CN-XiaoxiaoNeural"; EdgeRate = "-14%"; EdgePitch = "-2Hz"
        Title = "公益收束"; Visual = "父女坐下核对订单，手机远离共享页面，画面安静收束。"
        Line = "退款不到账，先核实；屏幕共享，不给陌生人。"
        Motion = "micro_pull"; Tier = "C"
    }
)

$scriptMd = @"
# 《别急着孝顺》Wan2.2 V3 修正版

题材：反诈升级 + 快递理赔 + 屏幕共享 + 亲情亏欠反转公益短剧
规格：竖屏 9:16，30fps，含 Wan2.2 动态镜头、神经配音、烧录字幕、BGM。
修复点：镜头 1 进入 Wan2.2 动态；女儿统一为“白大褂 + 蓝色洗手衣”的急诊医生；镜头 4、7 使用可控中文 UI 资产，避免 AI 伪字。

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
# Wan2.2 + Neural TTS V3 生成方案

- 关键帧来源：上一版稳定样片 `public-service-refund-antifraud-v1/keyframes`
- Wan2.2 工作流：`video_wan2_2_14B_i2v.json`
- 加速：`wan2.2_i2v_lightx2v_4steps_lora_v1_high_noise.safetensors` 与 low noise LoRA
- 替换镜头：$($WanShotNumbers -join ", ")
- 采样：49 frames, 24fps, 6 steps, split 3/3, low noise drift control
- 配音：Edge Neural TTS 优先，父亲改用更低速更低音的 Yunxi；失败时回退 Windows SAPI
- 合成：所有镜头重编码到 1080x1920 30fps，再统一烧字幕、混 BGM、ducking
- 字幕：字号降到 42，底部安全区下沉，最多两行，避免挡脸
- 抖动控制：非 Wan 镜头只保留极轻微推拉；Wan 镜头禁止镜头乱晃、换脸、换衣
- 可控 UI：镜头 4、7 不再使用 AI 生成手机界面，改为脚本生成的中文安全提示界面

成本记录：V3 保持 49 帧，重跑镜头 1、2、3、5、6、8；屏幕 UI 镜头用确定性渲染控制成本和文字质量。
"@
Write-Utf8 (Join-Path $OutPath "comfyui-generation-plan.md") $plan
Write-Utf8 (Join-Path $OutPath "storyboard.json") ([pscustomobject]@{ title = "别急着孝顺"; version = "wan22-v3"; wanShotNumbers = $WanShotNumbers; shots = $shots } | ConvertTo-Json -Depth 8)

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
        if ($shot.Motion -eq "controlled_ui") {
            $keyframe = Join-Path $UiAssetPath "$($shot.Keyframe).png"
        } else {
            $keyframe = Join-Path $KeyframePath "$($shot.Keyframe).png"
        }
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
                "controlled_ui" { "scale=1096:1948:force_original_aspect_ratio=increase,crop=1080:1920:x='(iw-1080)/2+2*sin(t*0.10)':y='(ih-1920)/2+2*cos(t*0.09)'" }
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
    $mix = "[1:a]atrim=0:$totalText,volume=0.06[bgm];[0:a]loudnorm=I=-16:TP=-1.5:LRA=12[voice];[bgm][voice]sidechaincompress=threshold=0.022:ratio=10:attack=10:release=300[duck];[voice][duck]amix=inputs=2:duration=first:weights='1 0.28'[a]"
    & $Ffmpeg -y -i (Join-Path $OutPath "voice_only.mp4") -stream_loop -1 -i $BgmPath -filter_complex $mix -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -movflags +faststart (Join-Path $OutPath "final_refund_antifraud_family_v3_wan22.mp4")
    if ($LASTEXITCODE -ne 0) {
        throw "final mix failed"
    }
} finally {
    Pop-Location
}

$final = Join-Path $OutPath "final_refund_antifraud_family_v3_wan22.mp4"
$probe = & $Ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate,duration -show_entries format=duration,size -of json $final
Write-Utf8 (Join-Path $OutPath "ffprobe.json") ($probe -join "`n")
Write-Host "FINAL_VIDEO=$final"
