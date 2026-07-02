param(
    [int]$StartEpisode = 3,
    [int]$EndEpisode = 15,
    [long]$ProjectId = 2066509892478795777,
    [string]$BaseUrl = $env:NIREN_API_BASE_URL,
    [string]$AdminUser = $env:NIREN_ADMIN_USER,
    [string]$AdminPassword = $env:NIREN_ADMIN_PASSWORD,
    [string]$CaptchaCode = $env:NIREN_CAPTCHA_CODE,
    [string]$DbHost = $env:NIREN_DB_HOST,
    [string]$DbPort = $env:NIREN_DB_PORT,
    [string]$DbName = $env:NIREN_DB_NAME,
    [string]$DbUser = $env:NIREN_DB_USER,
    [string]$DbPassword = $env:NIREN_DB_PASSWORD,
    [int]$PollSeconds = 60,
    [int]$MaxRepairAttempts = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
try {
    chcp.com 65001 | Out-Null
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [Console]::InputEncoding = $utf8NoBom
    [Console]::OutputEncoding = $utf8NoBom
    $OutputEncoding = $utf8NoBom
} catch {
}

if (-not $BaseUrl) { $BaseUrl = "http://127.0.0.1:8080/api" }
if (-not $AdminUser) { $AdminUser = "admin" }
if (-not $CaptchaCode) { $CaptchaCode = "PASSIVE:82:1200:6" }
if (-not $DbHost) { $DbHost = "127.0.0.1" }
if (-not $DbPort) { $DbPort = "3306" }
if (-not $DbName) { $DbName = "niren_drama" }
if (-not $DbUser) { $DbUser = "root" }

if (-not $AdminPassword) { throw "NIREN_ADMIN_PASSWORD is required" }
if ($null -eq $DbPassword) { throw "NIREN_DB_PASSWORD is required" }

$Root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$OutputDir = Join-Path $Root "output\pipeline"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogFile = Join-Path $OutputDir "full-drama-$Stamp.log"
$StateFile = Join-Path $OutputDir "full-drama-$Stamp.jsonl"

function Write-Log {
    param([string]$Message)
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $Message"
    $line | Tee-Object -Append -FilePath $LogFile | Out-Null
}

trap {
    Write-Log ("pipeline failed: " + $_.Exception.Message)
    if ($_.ScriptStackTrace) {
        Write-Log $_.ScriptStackTrace
    }
    exit 1
}

function Invoke-MySql {
    param([string]$Sql, [switch]$Raw)
    $oldMysqlPwd = $env:MYSQL_PWD
    $env:MYSQL_PWD = $DbPassword
    $args = @("--host=$DbHost", "--port=$DbPort", "--user=$DbUser")
    if (-not $Raw) { $args += "-N" }
    $args += @("-e", "USE $DbName; $Sql")
    try {
        & mysql @args
    } finally {
        $env:MYSQL_PWD = $oldMysqlPwd
    }
}

function Sql-Escape {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return $Value.Replace('\', '\\').Replace("'", "''")
}

function Invoke-ApiJson {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = ""
    )
    $url = if ($Path.StartsWith("http")) { $Path } else { "$BaseUrl$Path" }
    $args = @("--noproxy", "*", "-s", "-X", $Method, $url)
    if ($Token) { $args += @("-H", "Authorization: Bearer $Token") }
    if ($null -ne $Body) {
        $tmp = [System.IO.Path]::GetTempFileName()
        try {
            $json = $Body | ConvertTo-Json -Compress -Depth 12
            Set-Content -LiteralPath $tmp -Value $json -NoNewline -Encoding UTF8
            $args += @("-H", "Content-Type: application/json;charset=utf-8", "--data-binary", "@$tmp")
            $raw = & curl.exe @args
        } finally {
            Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        }
    } else {
        $raw = & curl.exe @args
    }
    if (-not $raw) { throw "Empty API response: $Method $url" }
    $response = $raw | ConvertFrom-Json
    if ($response.code -ne 200) {
        throw "API failed: $Method $url code=$($response.code) message=$($response.message)"
    }
    return $response
}

function Get-Prop {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object.PSObject.Properties.Name -contains $Name) { return $Object.$Name }
    return $null
}

function Get-Token {
    $captcha = Invoke-ApiJson -Method GET -Path "/auth/captcha"
    $body = @{
        username = $AdminUser
        password = $AdminPassword
        captchaId = $captcha.data.captchaId
        captchaCode = $CaptchaCode
    }
    $login = Invoke-ApiJson -Method POST -Path "/auth/login" -Body $body
    if (-not $login.data.token) { throw "Login did not return token" }
    return $login.data.token
}

function Get-ShotIds {
    param([int]$Episode)
    $rows = Invoke-MySql "SELECT id FROM drama_storyboard WHERE project_id=$ProjectId AND episode_no=$Episode AND deleted=0 ORDER BY shot_no;"
    return @($rows | Where-Object { $_ } | ForEach-Object { [long]$_ })
}

function Submit-Task {
    param([string]$Path, [object]$Body, [string]$Token)
    $response = Invoke-ApiJson -Method POST -Path $Path -Body $Body -Token $Token
    $task = $null
    if ($null -ne $response.data -and ($response.data.PSObject.Properties.Name -contains "task")) {
        $task = $response.data.task
    }
    if ($null -eq $task) {
        $task = $response.data
    }
    if (-not $task.id) { throw "No task id returned for $Path" }
    return [string]$task.id
}

function Wait-Task {
    param([string]$TaskId, [string]$Label)
    while ($true) {
        $row = Invoke-MySql "SELECT status,progress,message FROM drama_task_record WHERE id=$TaskId;"
        if (-not $row) { throw "Task not found: $TaskId" }
        $parts = $row -split "`t", 3
        $status = $parts[0]
        $progress = if ($parts.Length -gt 1) { $parts[1] } else { "" }
        $message = if ($parts.Length -gt 2) { $parts[2] } else { "" }
        Write-Log "$Label task=$TaskId status=$status progress=$progress message=$message"
        if ($status -eq "SUCCESS") { return }
        if ($status -eq "FAILED") { throw "$Label failed: $message" }
        Start-Sleep -Seconds $PollSeconds
    }
}

function Invoke-QualityCheck {
    param([long[]]$ShotIds, [string]$Token)
    $response = Invoke-ApiJson -Method POST -Path "/production/$ProjectId/quality-check" -Body @{ shotIds = $ShotIds } -Token $Token
    $issues = @()
    $data = Get-Prop $response "data"
    $responseIssues = Get-Prop $data "issues"
    if ($responseIssues) { $issues = @($responseIssues) }
    Write-Log "quality checked=$(Get-Prop $data 'checkedShots') issues=$($issues.Count)"
    return $issues
}

function Add-RepairPrompt {
    param([long[]]$ShotIds, [int]$Attempt)
    $suffix = (' CASR repair pass {0}: locked or nearly locked camera, anchored background and props. Add clear actor-local action progression: hand movement, head turn, step back, stand up, point, sleeve slide, hair motion, facial expression change. The shot must have a beginning, middle, and end. No whole-frame pan, zoom, drift, Ken Burns movement, animated still, or subtitle-only change.' -f $Attempt)
    $escapedSuffix = Sql-Escape $suffix
    $idList = ($ShotIds -join ",")
    $q = [char]39
    $sql = @(
        "UPDATE drama_storyboard",
        "SET video_prompt = CONCAT(COALESCE(video_prompt, $q$q), $q$escapedSuffix$q),",
        "    motion_level = ${q}high${q},",
        "    motion_tier = ${q}B${q},",
        "    video_url = NULL,",
        "    video_task_id = NULL,",
        "    video_task_status = NULL,",
        "    video_task_status_url = NULL",
        "WHERE id IN ($idList);"
    ) -join " "
    Invoke-MySql $sql | Out-Null
}

function Repair-Issues {
    param([object[]]$Issues, [int]$Episode, [int]$Attempt, [string]$Token)
    $shotIds = @($Issues | ForEach-Object { Get-Prop $_ "shotId" } | Where-Object { $_ } | ForEach-Object { [long]$_ } | Sort-Object -Unique)
    if ($shotIds.Count -eq 0) { return }
    $shotIdText = $shotIds -join ","
    $issueTypeText = (@($Issues | ForEach-Object { Get-Prop $_ "issueType" } | Where-Object { $_ } | Sort-Object -Unique) -join ",")
    Write-Log "episode=$Episode repairAttempt=$Attempt shotIds=$shotIdText issueTypes=$issueTypeText"
    Add-RepairPrompt -ShotIds $shotIds -Attempt $Attempt
    if ($Attempt -gt 1) {
        $imageTask = Submit-Task -Path "/videos/generate-images/$ProjectId" -Body @{ shotIds = $shotIds } -Token $Token
        Wait-Task -TaskId $imageTask -Label "episode=$Episode repair-images"
    }
    $videoTask = Submit-Task -Path "/videos/generate-dynamic/$ProjectId" -Body @{ shotIds = $shotIds } -Token $Token
    Wait-Task -TaskId $videoTask -Label "episode=$Episode repair-videos"
}

function Get-TaskResultJson {
    param([string]$TaskId)
    $row = Invoke-MySql "SELECT COALESCE(result,'') FROM drama_task_record WHERE id=$TaskId;"
    if (-not $row) { return $null }
    try { return $row | ConvertFrom-Json } catch { return $null }
}

function Resolve-VideoPath {
    param([string]$VideoUrl)
    if (-not $VideoUrl) { return $null }
    $name = [System.IO.Path]::GetFileName($VideoUrl)
    if ($VideoUrl -like "*/generated-videos/*") {
        return Join-Path $Root "backend\uploads\generated-videos\$name"
    }
    return Join-Path $Root "backend\uploads\videos\$name"
}

function Convert-FrameRate {
    param([string]$Rate)
    if (-not $Rate) { return 0.0 }
    if ($Rate -match "^([0-9.]+)/([0-9.]+)$") {
        $num = [double]$Matches[1]
        $den = [double]$Matches[2]
        if ($den -eq 0) { return 0.0 }
        return $num / $den
    }
    try { return [double]$Rate } catch { return 0.0 }
}

function Test-EffectiveMotion {
    param([string]$Path, [double]$Duration, [int]$Episode)
    $sampleFps = 8
    $expected = [math]::Max(1, [int][math]::Round($Duration * $sampleFps))
    $scan = & ffmpeg -hide_banner -nostats -i $Path -vf "fps=$sampleFps,scale=160:-1,format=gray,mpdecimate=hi=768:lo=320:frac=0.33,showinfo" -an -f null - 2>&1
    $kept = @($scan | Select-String -Pattern "\]\s+n:\s*\d+").Count
    if ($kept -le 0) { throw "episode=$Episode final media effective motion scan failed" }
    $ratio = $kept / [double]$expected
    if ($ratio -lt 0.55) {
        throw ("episode=$Episode final media low effective fps/animated-still risk kept={0} expected={1} ratio={2:N2}" -f $kept, $expected, $ratio)
    }
    return @{
        sampleFps = $sampleFps
        keptFrames = $kept
        expectedFrames = $expected
        keptRatio = [math]::Round($ratio, 4)
    }
}

function Test-FinalVideo {
    param([string]$VideoUrl, [int]$Episode)
    $path = Resolve-VideoPath $VideoUrl
    if (-not $path -or -not (Test-Path $path)) { throw "episode=$Episode final video missing: $VideoUrl" }
    $probeRaw = & ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate,duration -show_entries format=duration,size -of json $path
    $probe = $probeRaw | ConvertFrom-Json
    $video = @($probe.streams | Where-Object { $_.codec_type -eq "video" })[0]
    $audio = @($probe.streams | Where-Object { $_.codec_type -eq "audio" })[0]
    if ($null -eq $video -or $null -eq $audio) { throw "episode=$Episode missing stream" }
    $delta = [math]::Abs([double]$video.duration - [double]$audio.duration)
    if ($delta -gt 1.5) { throw "episode=$Episode av duration mismatch video=$($video.duration) audio=$($audio.duration)" }
    $declaredFps = Convert-FrameRate $video.avg_frame_rate
    if ($declaredFps -lt 24.0) { throw "episode=$Episode final video declared fps too low: $($video.avg_frame_rate)" }
    $scan = & ffmpeg -hide_banner -i $path -vf "freezedetect=n=0.003:d=1.2,blackdetect=d=0.45:pic_th=0.98" -an -f null - 2>&1
    $bad = @($scan | Select-String -Pattern "freeze_start|black_start")
    if ($bad.Count -gt 0) { throw "episode=$Episode final media freeze/black detected: $($bad[0])" }
    $motion = Test-EffectiveMotion -Path $path -Duration ([double]$probe.format.duration) -Episode $Episode
    return @{
        path = $path
        duration = [double]$probe.format.duration
        size = [long]$probe.format.size
        width = [int]$video.width
        height = [int]$video.height
        fps = [math]::Round($declaredFps, 4)
        motion = $motion
    }
}

function Write-State {
    param([hashtable]$State)
    ($State | ConvertTo-Json -Compress -Depth 8) | Add-Content -LiteralPath $StateFile -Encoding UTF8
}

Write-Log "pipeline start project=$ProjectId episodes=$StartEpisode-$EndEpisode"
$token = Get-Token

for ($episode = $StartEpisode; $episode -le $EndEpisode; $episode++) {
    Write-Log "episode=$episode start"
    $shotIds = Get-ShotIds -Episode $episode
    if ($shotIds.Count -eq 0) { throw "episode=$episode has no shots" }

    $imageTask = Submit-Task -Path "/videos/generate-images/$ProjectId" -Body @{ shotIds = $shotIds } -Token $token
    Wait-Task -TaskId $imageTask -Label "episode=$episode images"

    $videoTask = Submit-Task -Path "/videos/generate-dynamic/$ProjectId" -Body @{ shotIds = $shotIds } -Token $token
    Wait-Task -TaskId $videoTask -Label "episode=$episode videos"

    $issues = @(Invoke-QualityCheck -ShotIds $shotIds -Token $token)
    for ($attempt = 1; $attempt -le $MaxRepairAttempts -and @($issues).Count -gt 0; $attempt++) {
        Repair-Issues -Issues $issues -Episode $episode -Attempt $attempt -Token $token
        $issues = @(Invoke-QualityCheck -ShotIds $shotIds -Token $token)
    }
    if (@($issues).Count -gt 0) {
        throw "episode=$episode quality unresolved issues=$(@($issues).Count)"
    }

    $composeTask = Submit-Task -Path "/production/$ProjectId/repair" -Body @{ action = "composePublish"; shotIds = $shotIds } -Token $token
    Wait-Task -TaskId $composeTask -Label "episode=$episode compose"
    $result = Get-TaskResultJson -TaskId $composeTask
    if ($null -eq $result -or -not $result.videoUrl) { throw "episode=$episode compose result missing videoUrl" }
    $media = Test-FinalVideo -VideoUrl $result.videoUrl -Episode $episode
    Write-Log "episode=$episode complete videoUrl=$($result.videoUrl) duration=$($media.duration) path=$($media.path)"
    Write-State @{
        episode = $episode
        status = "complete"
        videoUrl = $result.videoUrl
        media = $media
        completedAt = (Get-Date).ToString("s")
    }
}

Write-Log "pipeline complete"
