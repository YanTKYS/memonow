#requires -Version 5.1
<#
.SYNOPSIS
  memonow リリース署名鍵の作成・確認と GitHub Environment への登録（Windows用・v2）。
.EXAMPLE
  .\setup-memonow-signing.ps1
.EXAMPLE
  .\setup-memonow-signing.ps1 -RegisterGitHub
.NOTES
  - 鍵を上書き・自動更新しません。初回に作成し、再実行時は同じ鍵を検証します。
  - 署名鍵・パスワード・base64を画面・ファイル・コマンド引数に出力しません。
  - ただし、スクリプト実行中はパスワードがプロセスメモリに一時的に存在します。
  - GitHub登録前に Settings > Environments > release-signing の保護設定が必要です。
#>
[CmdletBinding()]
param(
    [switch]$RegisterGitHub,
    [string]$KeystorePath = (Join-Path $env:USERPROFILE '.memonow-signing\memonow-release.jks'),
    [string]$OfflineBackupDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Repository = 'YanTKYS/memonow'
$EnvironmentName = 'release-signing'
$Alias = 'memonow'
$TempPasswordName = 'MEMONOW_SIGNING_SETUP_PASSWORD'
$Password = $null

function Get-PlaintextFromSecureString {
    param([System.Security.SecureString]$Secret)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Find-Keytool {
    $cmd = Get-Command 'keytool.exe' -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    if ($env:ProgramFiles) {
        $candidate = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'keytool.exe が見つかりません。JDK 17/21 または Android Studio をインストールしてください。'
}

function Invoke-GhWithStdin {
    param(
        [ValidateSet('secret', 'variable')][string]$Kind,
        [string]$Name,
        [string]$Value,
        [string]$GhPath
    )
    # 値をコマンド引数や一時ファイルに渡さず、改行なしの標準入力で送ります。
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $GhPath
    $info.Arguments = "$Kind set $Name --repo $Repository --env $EnvironmentName"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $proc = [System.Diagnostics.Process]::Start($info)
    try {
        $proc.StandardInput.Write($Value)  # WriteLineは使わない（末尾改行がパスワードになることを防止）
        $proc.StandardInput.Close()
        $null = $proc.StandardOutput.ReadToEnd()
        $null = $proc.StandardError.ReadToEnd()
        $proc.WaitForExit()
        if ($proc.ExitCode -ne 0) {
            throw "GitHub への $Name 登録に失敗しました。gh auth status / Environmentの権限を確認してください。"
        }
    }
    finally {
        $proc.Dispose()
    }
}

function Assert-GitHubEnvironment {
    param([string]$GhPath)
    # ghは認証状態などをstderrへ出力するので、PowerShell 5.1では終了コードで判定します。
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $GhPath auth status --hostname github.com *> $null
        if ($LASTEXITCODE -ne 0) { throw 'GitHub CLIが未認証です。先に gh auth login を実行してください。' }

        $infoText = & $GhPath api "repos/$Repository/environments/$EnvironmentName" 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "Environment が見つからない、または取得できません。先に https://github.com/$Repository/settings/environments で release-signing を作成してください。"
        }
        $info = ($infoText -join "`n") | ConvertFrom-Json
        if ($null -eq $info.deployment_branch_policy -or -not $info.deployment_branch_policy.custom_branch_policies) {
            throw 'EnvironmentのDeployment branches/tagsを「Selected branches and tags」に設定してください（main / v*）。'
        }
        $policiesText = & $GhPath api "repos/$Repository/environments/$EnvironmentName/deployment-branch-policies" 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw 'Environmentのbranches/tags制限を確認できません。GitHubの画面で設定・API権限を確認してください。'
        }
        $policies = ($policiesText -join "`n") | ConvertFrom-Json
        $patterns = @($policies.branch_policies | ForEach-Object { $_.name })
        if ($patterns -notcontains 'main' -or $patterns -notcontains 'v*') {
            throw "Environmentの許可パターンに main と v* の両方が必要です。現在: $($patterns -join ', ')"
        }
        $reviewers = @($info.protection_rules | Where-Object { $_.type -eq 'required_reviewers' })
        if ($reviewers.Count -eq 0) {
            Write-Warning 'Environmentに必須レビュアーを確認できません。利用プランで可能ならGitHub側で設定してください。'
        }
        Write-Host 'Environmentの存在とmain/v*制限を確認しました。' -ForegroundColor Green
    }
    finally {
        $ErrorActionPreference = $previousErrorPreference
    }
}

try {
    $keytool = Find-Keytool
    $directory = Split-Path -Path $KeystorePath -Parent
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -Path $directory -ItemType Directory -Force | Out-Null
    }

    Write-Host '=== memonow リリース署名鍵のセットアップ ===' -ForegroundColor Cyan
    Write-Host "リポジトリ : $Repository"
    Write-Host "鍵ファイル   : $KeystorePath"
    Write-Host "鍵の別名     : $Alias"
    if (Test-Path -LiteralPath $KeystorePath -PathType Leaf) {
        Write-Host '既存の鍵を使用します。上書きしません。' -ForegroundColor Yellow
    }
    else {
        Write-Host '新しいリリース署名鍵を生成します。これは今後も同じ鍵を使い続けてください。'
    }

    $first = Read-Host '署名鍵パスワード（12文字以上の半角英数字・記号）' -AsSecureString
    $second = Read-Host '署名鍵パスワード（確認のため再入力）' -AsSecureString
    $Password = Get-PlaintextFromSecureString $first
    $confirmation = Get-PlaintextFromSecureString $second
    if ($Password -cne $confirmation) { throw 'パスワードが一致しません。鍵やSecretsは変更していません。' }
    $confirmation = $null
    if ($Password.Length -lt 12 -or $Password -cnotmatch '^[\x21-\x7E]+$') {
        throw 'パスワードは12文字以上で空白のない半角ASCII文字を使ってください。'
    }

    # keytoolの :env 修飾子なら、パスワードを実行コマンド行に含めません。
    if (Test-Path "Env:$TempPasswordName") {
        throw "一時環境変数 $TempPasswordName が既に存在します。解消してから実行してください。"
    }
    $previousErrorPreference = $ErrorActionPreference
    try {
        [Environment]::SetEnvironmentVariable($TempPasswordName, $Password, 'Process')
        # Windows PowerShell 5.1はJavaの警告(stderr)をErrorRecord化するため、この区間のみ明示的に終了コードで判定します。
        $ErrorActionPreference = 'Continue'
        if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
            $null = & $keytool -genkeypair -storetype JKS -keystore $KeystorePath `
                -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
                -dname 'CN=memonow Release, OU=Personal, O=Personal, C=JP' `
                -storepass:env $TempPasswordName -keypass:env $TempPasswordName -noprompt 2>&1
            if ($LASTEXITCODE -ne 0) { throw 'keytoolによる鍵生成に失敗しました。出力ファイルを確認してください。' }
        }
        # Windows版keytool.exeでは -J-Duser.language=en が認識されない環境があるため、
        # ロケール依存の -list -v の表示文言からフィンガープリントを読み取らない。
        # PEM形式の公開証明書を取得し、DERバイト列をPowerShell自身でSHA-256計算する。
        $certificateText = & $keytool -exportcert -rfc -keystore $KeystorePath -alias $Alias `
            -storepass:env $TempPasswordName 2>&1
        if ($LASTEXITCODE -ne 0) {
            # 署名秘密値は標準出力には渡さず、keytoolのエラー内容だけ示す。
            $diagnostic = (($certificateText | ForEach-Object { [string]$_ }) -join ' ').Trim()
            if ($diagnostic.Length -gt 1200) { $diagnostic = $diagnostic.Substring(0, 1200) }
            throw "鍵の証明書を取得できませんでした。既存鍵・パスワード・別名を確認してください。keytool: $diagnostic"
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorPreference
        [Environment]::SetEnvironmentVariable($TempPasswordName, $null, 'Process')
    }

    $pem = [regex]::Match(($certificateText -join "`n"),
        '(?s)-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----')
    if (-not $pem.Success) { throw '公開証明書を読み取れません。鍵は変更していません。' }
    $der = [Convert]::FromBase64String(([regex]::Replace($pem.Groups[1].Value, '\s', '')))
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $fingerprint = [BitConverter]::ToString($hasher.ComputeHash($der)).Replace('-', '').ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
    $der = $null
    $certificateText = $null
    Write-Host '署名鍵の生成／読み取りと証明書SHA-256の取得に成功しました。' -ForegroundColor Green
    Write-Host "公開証明書 SHA-256: $fingerprint" # フィンガープリントは公開してよい値

    if ($OfflineBackupDirectory) {
        if (-not (Test-Path -LiteralPath $OfflineBackupDirectory -PathType Container)) {
            throw 'バックアップ先フォルダーがありません。暗号化済みの外部ドライブ等を準備してください。'
        }
        $copy = Join-Path $OfflineBackupDirectory 'memonow-release.jks'
        if (Test-Path -LiteralPath $copy) { throw "バックアップ先に同名ファイルがあります。上書きしません: $copy" }
        Copy-Item -LiteralPath $KeystorePath -Destination $copy -ErrorAction Stop
        if ((Get-FileHash -LiteralPath $KeystorePath -Algorithm SHA256).Hash -cne
            (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash) {
            throw 'コピーした署名鍵のSHA-256が一致しません。バックアップを確認してください。'
        }
        Write-Host "署名鍵をバックアップし、コピーの整合性を確認しました: $copy" -ForegroundColor Green
    }
    else {
        Write-Warning '鍵ファイルの別媒体バックアップは未実施です。安全な外部媒体にコピーし、パスワードは別途保管してください。'
    }

    if ($RegisterGitHub) {
        $gh = Get-Command 'gh.exe' -ErrorAction SilentlyContinue
        if (-not $gh) { throw 'gh.exe がありません。GitHub CLIを導入して gh auth login を実行してください。鍵はローカルに残っています。' }
        Assert-GitHubEnvironment -GhPath $gh.Source
        Write-Host 'GitHub Environmentへ4つのSecretと1つのVariableを登録します。'
        Write-Host 'パスワードとJKS本体は画面に表示しません。'
        if ((Read-Host '登録する場合は REGISTER と入力') -cne 'REGISTER') {
            Write-Host 'GitHubへの登録を中止しました。ローカルの署名鍵は保持されています。'
            return
        }
        $base64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($KeystorePath))
        try {
            Invoke-GhWithStdin -Kind secret -Name 'MEMONOW_RELEASE_KEYSTORE_BASE64' -Value $base64 -GhPath $gh.Source
        }
        finally { $base64 = $null }
        Invoke-GhWithStdin -Kind secret -Name 'MEMONOW_RELEASE_STORE_PASSWORD' -Value $Password -GhPath $gh.Source
        Invoke-GhWithStdin -Kind secret -Name 'MEMONOW_RELEASE_KEY_ALIAS' -Value $Alias -GhPath $gh.Source
        Invoke-GhWithStdin -Kind secret -Name 'MEMONOW_RELEASE_KEY_PASSWORD' -Value $Password -GhPath $gh.Source
        Invoke-GhWithStdin -Kind variable -Name 'MEMONOW_RELEASE_CERT_SHA256' -Value $fingerprint -GhPath $gh.Source
        Write-Host 'EnvironmentのSecrets 4件とVariable 1件を登録しました。' -ForegroundColor Green
        Write-Host '※ Secretsの値はGitHub画面から再表示できません。'
        & $gh.Source secret list --repo $Repository --env $EnvironmentName
        & $gh.Source variable list --repo $Repository --env $EnvironmentName
    }
    else {
        Write-Host 'GitHubへの登録はまだ行っていません。'
        Write-Host 'release-signingの保護設定後、同じスクリプトへ -RegisterGitHub を付けて再実行してください。'
    }

    Write-Host ''
    Write-Host '次の手順（勝手にリリースは実行しません）:' -ForegroundColor Cyan
    Write-Host '1. GitHub Actions → Signed release APK → Run workflow → main を手動実行する。'
    Write-Host '2. 生成された候補APKを実機で検証し、JSON保存／復元と将来の上書き更新を確かめる。'
    Write-Host '3. バージョン・ノート・確認記録を含むPRをレビューし、オーナーがmainへマージするとActionsが自動公開する。'
    Write-Host '重要: debug版から正式署名版へ上書きできない場合があります。アンインストール前にJSONを検証してください。'
}
finally {
    $Password = $null
    $first = $null
    $second = $null
    $certificateText = $null
    $der = $null
    [Environment]::SetEnvironmentVariable($TempPasswordName, $null, 'Process')
}
