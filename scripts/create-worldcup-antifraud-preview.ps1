param(
    [string]$OutDir = "output\public-service-worldcup-antifraud",
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
$BgmPath = Join-Path $Root "backend\src\main\resources\assets\bgm_default.wav"
New-Item -ItemType Directory -Force -Path $OutPath | Out-Null

Add-Type -AssemblyName System.Speech

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

function Escape-Ass {
    param([string]$Text)
    return $Text.Replace("\", "\\").Replace("{", "\{").Replace("}", "\}").Replace("`r", "").Replace("`n", "\N")
}

function Split-SubtitleLine {
    param(
        [string]$Text,
        [int]$MaxChars = 16
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

function Speak-Line {
    param(
        [string]$Text,
        [string]$Voice,
        [int]$Rate,
        [string]$Output
    )
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    foreach ($installed in $synth.GetInstalledVoices()) {
        if ($installed.VoiceInfo.Name -ieq $Voice) {
            $synth.SelectVoice($installed.VoiceInfo.Name)
            break
        }
    }
    $synth.Rate = $Rate
    $synth.Volume = 100
    $synth.SetOutputToWaveFile($Output)
    $synth.Speak($Text)
    $synth.Dispose()
}

$shots = @(
    @{
        No = 1; Duration = 6.2; Voice = "Microsoft Huihui Desktop"; Rate = -1
        Title = "世界杯决赛夜"
        Speaker = "旁白"
        Line = "决赛补时最后一分钟，林舟以为自己等的是一粒进球。"
        Visual = "出租屋内，电视蓝光照在外卖头盔和欠费短信上。"
        Tier = "C"; Color = "0x0b1530"
    },
    @{
        No = 2; Duration = 7.0; Voice = "Microsoft Kangkang"; Rate = 0
        Title = "中奖链接"
        Speaker = "林舟"
        Line = "三千块变三万？只要今晚翻身，我就不用再让妈担心了。"
        Visual = "手机弹出世界杯竞猜返利链接，手指停在充值按钮上。"
        Tier = "A"; Color = "0x130b28"
    },
    @{
        No = 3; Duration = 6.8; Voice = "Microsoft Huihui Desktop"; Rate = 1
        Title = "母亲来电"
        Speaker = "母亲"
        Line = "小舟，先别点链接。你听妈说，补时不能孤注一掷。"
        Visual = "母亲连续来电，屏幕上同时跳出验证码短信。"
        Tier = "A"; Color = "0x071f2a"
    },
    @{
        No = 4; Duration = 7.2; Voice = "Microsoft Kangkang"; Rate = -1
        Title = "误会爆发"
        Speaker = "林舟"
        Line = "妈，你是不是又被人拉进群了？你别管我，我懂！"
        Visual = "林舟挂断电话，电视解说声和倒计时同时变急。"
        Tier = "B"; Color = "0x1a1020"
    },
    @{
        No = 5; Duration = 7.0; Voice = "Microsoft Kangkang"; Rate = 1
        Title = "骗子催转账"
        Speaker = "骗子"
        Line = "阿姨，最后三十秒，再不转保证金，名额就取消了。"
        Visual = "骗子语音从免提里传出，收款码占满母亲旧手机。"
        Tier = "A"; Color = "0x22110d"
    },
    @{
        No = 6; Duration = 7.4; Voice = "Microsoft Huihui Desktop"; Rate = -1
        Title = "亲情反转"
        Speaker = "母亲"
        Line = "我不是要转钱。我在拖住他，警察就在门外。"
        Visual = "母亲把录音界面推到镜头前，门外警灯反光掠过墙面。"
        Tier = "A"; Color = "0x071b16"
    },
    @{
        No = 7; Duration = 7.2; Voice = "Microsoft Kangkang"; Rate = -1
        Title = "儿子醒悟"
        Speaker = "林舟"
        Line = "原来你一直打电话，不是问我要钱，是在救我。"
        Visual = "林舟删掉充值页面，验证码短信停在未发送状态。"
        Tier = "B"; Color = "0x0b1830"
    },
    @{
        No = 8; Duration = 7.0; Voice = "Microsoft Huihui Desktop"; Rate = 0
        Title = "抓捕"
        Speaker = "民警"
        Line = "转账前多问一句，很多骗局就差这一句。"
        Visual = "门被推开，民警亮证，骗子通话中断。"
        Tier = "B"; Color = "0x101b2a"
    },
    @{
        No = 9; Duration = 7.0; Voice = "Microsoft Huihui Desktop"; Rate = -1
        Title = "公益收束"
        Speaker = "旁白"
        Line = "补时可以逆转，转账没有补时。世界杯再热，也别让骗子进球。"
        Visual = "母子并肩看球，屏幕变成反诈提示。"
        Tier = "C"; Color = "0x080f1f"
    }
)

$scriptText = @"
# 《补时之前》完整短剧剧本

题材：反诈 + 亲情反转 + 世界杯公益短剧
规格：竖屏 9:16，约 60 秒，带配音、台词、烧录字幕。
核心钩子：儿子以为母亲被骗，最后发现母亲是在拖住骗子，也是在救差点被骗的自己。

## 分场剧本

"@
foreach ($shot in $shots) {
    $scriptText += "### 镜头 $($shot.No)：$($shot.Title)`n"
    $scriptText += "- 画面：$($shot.Visual)`n"
    $scriptText += "- 台词：$($shot.Speaker)：$($shot.Line)`n"
    $scriptText += "- 动态层级：$($shot.Tier)`n`n"
}
Write-Utf8 (Join-Path $OutPath "script.md") $scriptText

$characters = @"
# 角色设定

## 林舟
- 年龄：28
- 身份：外卖骑手，世界杯球迷，近期经济压力大。
- 外观锁定：短黑发，深色连帽外套，外卖头盔放在身边，左手戴旧运动手环。
- 性格：嘴硬、急躁，但孝顺；反转点是他差点成为真正被骗的人。
- 配音：Microsoft Kangkang，语速正常或略慢。

## 母亲 李梅
- 年龄：55
- 身份：社区反诈志愿者，表面唠叨，实际冷静。
- 外观锁定：低马尾，米色针织开衫，老花镜，旧手机开免提录音。
- 性格：温柔克制，关键时刻非常稳。
- 配音：Microsoft Huihui Desktop，语速略慢。

## 骗子
- 身份：伪装成世界杯竞猜平台客服。
- 外观锁定：只以手机语音、聊天界面、收款码出现，不露脸。
- 声音：男声，语速略快，催促感强。

## 民警
- 身份：社区民警。
- 外观锁定：不强调露脸，以证件、警灯反光、门口轮廓表现。
"@
Write-Utf8 (Join-Path $OutPath "characters.md") $characters

$continuity = @"
# 镜头连续性规则

1. 林舟服装固定为深色连帽外套，外卖头盔始终是关键道具。
2. 母亲固定米色针织开衫、老花镜、旧手机，旧手机始终用于录音和免提。
3. 世界杯元素只做电视蓝光、倒计时、解说声和球赛氛围，不使用真实赛事 Logo。
4. 骗局元素统一为“竞猜返利、保证金、验证码、收款码”，避免题材发散。
5. 反转线索提前埋：母亲说“补时不能孤注一掷”，她不是不会用手机，而是在录音。
6. A 档动态镜头优先生成：2、3、5、6；B 档可用轻动态；C 档可用静帧加字幕与环境运动。
7. ComfyUI 视频负面约束：禁止换脸、换衣、加新人、字幕入画、Logo、水印、线稿化、漫画化、整帧漂移。
"@
Write-Utf8 (Join-Path $OutPath "continuity-rules.md") $continuity

$comfyPlan = @"
# ComfyUI 生成方案

当前本机 8188 和 8080 接口返回 502，本脚本先产出可播放合成样片。ComfyUI 恢复后按以下方案替换动态镜头。

## 推荐工作流
- 首选：backend/src/main/resources/comfyui/workflows/video_wan2_2_14B_i2v_series_balanced.json
- 质量优先：video_wan2_2_14B_i2v_quality_long.json
- 快速预览：video_ltx2_i2v_short_drama_consistency.json

## A 档镜头
1. 镜头 2：林舟手指停在充值按钮，手机界面反光，眼神犹豫。
2. 镜头 3：母亲来电和验证码并列，林舟神情从烦躁到迟疑。
3. 镜头 5：骗子催促，旧手机收款码与倒计时制造压迫。
4. 镜头 6：母亲推近录音界面，警灯反光出现，完成亲情反转。

## Wan2.2 通用 Prompt
Commercial vertical short-drama video, one continuous 9:16 live-action shot. Use the input image as the exact first frame. Preserve face, age, outfit, props, phone layout, lighting and camera angle. Locked or nearly locked camera. Actor-local motion only: eye movement, breathing, small head turn, hand movement, cloth motion, screen glow. Clear beginning, middle and end. Photorealistic live action. No subtitles, no logo, no watermark, no new person, no outfit change, no face drift, no whole-frame pan, no Ken Burns zoom, no sketch, no manga, no monochrome.
"@
Write-Utf8 (Join-Path $OutPath "comfyui-generation-plan.md") $comfyPlan

$storyboardObjects = foreach ($shot in $shots) {
    [pscustomobject]@{
        shotNo = $shot.No
        title = $shot.Title
        visual = $shot.Visual
        speaker = $shot.Speaker
        line = $shot.Line
        motionTier = $shot.Tier
        voice = $shot.Voice
    }
}
$storyboardObjects | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutPath "storyboard.json") -Encoding UTF8

Push-Location $OutPath
try {
    $shotFiles = @()
    $absoluteStart = 0.0
    $srt = ""

    foreach ($shot in $shots) {
        $idx = "{0:00}" -f $shot.No
        $audio = "voice_$idx.wav"
        $ass = "shot_$idx.ass"
        $mp4 = "shot_$idx.mp4"
        Speak-Line -Text $shot.Line -Voice $shot.Voice -Rate $shot.Rate -Output (Join-Path $OutPath $audio)
        $audioDuration = Get-DurationSeconds (Join-Path $OutPath $audio)
        $duration = [math]::Max([double]$shot.Duration, $audioDuration + 1.25)
        $durationText = $duration.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
        $sourceDuration = ($duration + 3.2).ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
        $endAss = Format-AssTime $duration
        $line = Escape-Ass (Split-SubtitleLine "$($shot.Speaker)：$($shot.Line)" 16)
        $title = Escape-Ass $shot.Title
        $visual = Escape-Ass $shot.Visual
        $tier = Escape-Ass ("Motion Tier $($shot.Tier) / Niren Drama")
        $assText = @"
[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
Style: Title,Microsoft YaHei,62,&H00FFFFFF,&H000000FF,&HAA000000,&H66000000,-1,0,0,0,100,100,0,0,1,2,0,7,72,72,120,1
Style: Meta,Microsoft YaHei,30,&H00B9C4D6,&H000000FF,&HAA000000,&H44000000,0,0,0,0,100,100,0,0,1,1,0,7,72,72,205,1
Style: Visual,Microsoft YaHei,34,&H00B9C4D6,&H000000FF,&HAA000000,&H44000000,0,0,0,0,100,100,0,0,1,1,0,7,72,72,1510,1
Style: Sub,Microsoft YaHei,46,&H00FFFFFF,&H000000FF,&HCC000000,&H99000000,-1,0,0,0,100,100,0,0,1,4,1,2,80,80,170,1

[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
Dialogue: 0,0:00:00.00,$endAss,Title,,0,0,0,,$title
Dialogue: 0,0:00:00.00,$endAss,Meta,,0,0,0,,$tier
Dialogue: 0,0:00:00.00,$endAss,Visual,,0,0,0,,$visual
Dialogue: 0,0:00:00.35,$endAss,Sub,,0,0,0,,$line
"@
        Write-Utf8 (Join-Path $OutPath $ass) $assText

        $color = $shot.Color
        $source = "testsrc2=s=1080x1920:r=30:d=${sourceDuration}"
        $filter = "[0:v]trim=start=3.2,setpts=PTS-STARTPTS,boxblur=48:1,eq=saturation=0.28:contrast=0.68:brightness=-0.24,hue=h='8*sin(t*0.4)',noise=alls=6:allf=t,drawbox=x=60:y=430:w=960:h=650:color=0x090f1cee:t=fill,drawbox=x=90:y=470:w=900:h=3:color=0x18d8ff80:t=fill,drawbox=x='120+46*sin(t*1.2)':y=620:w=160:h=160:color=0x8b5cf650:t=fill,drawbox=x='780-44*sin(t*1.1)':y=790:w=210:h=18:color=0xff4fa390:t=fill,drawbox=x=90:y=1350:w=900:h=130:color=0x0e182bcc:t=fill,ass=$ass[v];[1:a]adelay=350|350,apad,atrim=0:$durationText,loudnorm=I=-16:TP=-1.5:LRA=11[a]"
        & $Ffmpeg -y -f lavfi -i $source -i $audio -filter_complex $filter -map "[v]" -map "[a]" -r 30 -c:v libx264 -preset veryfast -crf 28 -pix_fmt yuv420p -c:a aac -b:a 160k -shortest $mp4
        if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed on shot $idx" }
        $shotFiles += $mp4

        $srtStart = [TimeSpan]::FromSeconds($absoluteStart + 0.35)
        $srtEnd = [TimeSpan]::FromSeconds($absoluteStart + $duration)
        $srt += "$($shot.No)`r`n"
        $srt += ("{0:00}:{1:00}:{2:00},{3:000} --> {4:00}:{5:00}:{6:00},{7:000}`r`n" -f [int]$srtStart.TotalHours,$srtStart.Minutes,$srtStart.Seconds,$srtStart.Milliseconds,[int]$srtEnd.TotalHours,$srtEnd.Minutes,$srtEnd.Seconds,$srtEnd.Milliseconds)
        $srt += "$($shot.Speaker)：$($shot.Line)`r`n`r`n"
        $absoluteStart += $duration
    }

    Write-Utf8 (Join-Path $OutPath "subtitles.srt") $srt
    $concat = ($shotFiles | ForEach-Object { "file '$($_)'" }) -join "`n"
    Write-Utf8 (Join-Path $OutPath "concat.txt") $concat
    & $Ffmpeg -y -f concat -safe 0 -i concat.txt -c copy voice_only.mp4
    if ($LASTEXITCODE -ne 0) { throw "concat failed" }

    $totalDuration = Get-DurationSeconds (Join-Path $OutPath "voice_only.mp4")
    $totalText = $totalDuration.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
    $mix = "[1:a]atrim=0:$totalText,volume=0.11[bgm];[0:a]loudnorm=I=-16:TP=-1.5:LRA=11[voice];[bgm][voice]sidechaincompress=threshold=0.028:ratio=10:attack=8:release=260[duck];[voice][duck]amix=inputs=2:duration=first:weights='1 0.45'[a]"
    & $Ffmpeg -y -i voice_only.mp4 -stream_loop -1 -i $BgmPath -filter_complex $mix -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k final_worldcup_antifraud_family.mp4
    if ($LASTEXITCODE -ne 0) { throw "final mix failed" }
}
finally {
    Pop-Location
}

$final = Join-Path $OutPath "final_worldcup_antifraud_family.mp4"
$probe = & $Ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate,duration -show_entries format=duration,size -of json $final
Write-Utf8 (Join-Path $OutPath "ffprobe.json") ($probe -join "`n")
Write-Host "FINAL_VIDEO=$final"
