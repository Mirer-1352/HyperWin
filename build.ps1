param(
    [string] $Sdk = $env:ANDROID_HOME,
    [string] $Jdk = $env:JAVA_HOME,
    [string] $Platform = 'android-37.0',
    [string] $BuildTools = '36.0.0',
    [string] $OutputDirectory = (Join-Path $PSScriptRoot 'dist'),
    [string] $WorkDirectory = (Join-Path $PSScriptRoot 'build'),
    [string] $Keystore,
    [string] $KeyAlias
)

$ErrorActionPreference = 'Stop'
$moduleRoot = $PSScriptRoot
if (-not $Sdk) { throw 'Set ANDROID_HOME or pass -Sdk.' }
if (-not $Jdk) { throw 'Set JAVA_HOME or pass -Jdk.' }
if ($Keystore) {
    $Keystore = (Resolve-Path -LiteralPath $Keystore).Path
    if (!$KeyAlias -or !$env:HYPERWIN_STORE_PASSWORD -or !$env:HYPERWIN_KEY_PASSWORD) {
        throw 'Signing requires -KeyAlias, HYPERWIN_STORE_PASSWORD and HYPERWIN_KEY_PASSWORD.'
    }
}
$sourceRoot = Join-Path $moduleRoot 'src\main'
$versionPath = Join-Path $moduleRoot 'version.properties'
$metadataRoot = Join-Path $sourceRoot 'resources\META-INF\xposed'
$workRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($WorkDirectory)
$stage = Join-Path $workRoot (
    'apk_' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $stage 'classes'
$dex = Join-Path $stage 'dex'
New-Item -ItemType Directory -Path $classes,$dex,$OutputDirectory -Force | Out-Null
$buildLog = Join-Path $stage 'build.log'
$toolRoot = Join-Path $Sdk "build-tools\$BuildTools"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
$apiJar = Join-Path $moduleRoot 'vendor\api-102.0.0.jar'
$apiAar = Join-Path $moduleRoot 'vendor\api-102.0.0.aar'
foreach ($required in @($androidJar,$apiJar,$apiAar,(Join-Path $Jdk 'bin\javac.exe'),
    (Join-Path $toolRoot 'aapt2.exe'),(Join-Path $toolRoot 'd8.bat'),
    (Join-Path $toolRoot 'apksigner.bat'),(Join-Path $toolRoot 'zipalign.exe'),(Join-Path $toolRoot 'dexdump.exe'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing tool or dependency: $required" }
}
$officialHash = '423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0'
if ((Get-FileHash -LiteralPath $apiAar -Algorithm SHA256).Hash -cne $officialHash) {
    throw 'Official API AAR checksum mismatch.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apiArchive = [IO.Compression.ZipFile]::OpenRead($apiAar)
try {
    $apiEntry = $apiArchive.GetEntry('classes.jar')
    if ($null -eq $apiEntry) { throw 'API AAR classes.jar missing.' }
    $apiStream = $apiEntry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $jarHash = [BitConverter]::ToString($sha.ComputeHash($apiStream)).Replace('-','') }
    finally { $apiStream.Dispose(); $sha.Dispose() }
    if ((Get-FileHash -LiteralPath $apiJar -Algorithm SHA256).Hash -cne $jarHash) {
        throw 'Compile JAR does not match classes.jar from official API AAR.'
    }
} finally { $apiArchive.Dispose() }

$serviceArtifacts = [ordered]@{
    'interface-102.0.0.jar' = '08711ECEB0FFB64CBADE3536FA7BB48A5FE3420DFDB1E3EDDE12CEA006AE207F'
    'service-102.0.0.jar' = 'B07BC86B9DA1E9ADC4FD967D5F27A37F1907E8234594A20E6928C78981F0F37F'
}
$runtimeJars = foreach ($name in $serviceArtifacts.Keys) {
    $runtimeJar = Join-Path $moduleRoot "vendor\$name"
    if ((Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash -cne $serviceArtifacts[$name]) {
        throw "Official runtime JAR checksum mismatch: $name"
    }
    $runtimeJar
}

function Invoke-Checked([string] $Exe, [string[]] $Arguments) {
    $lines = & $Exe @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $lines | ForEach-Object { $line = $_.ToString(); Write-Host $line; Add-Content -LiteralPath $buildLog -Value $line -Encoding utf8 }
    if ($exitCode -ne 0) { throw "Command failed ($exitCode): $Exe" }
}

$previousJavaHome = $env:JAVA_HOME
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
try {
    $env:JAVA_HOME = $Jdk
    $env:TEMP = $stage
    $env:TMP = $stage
    & (Join-Path $moduleRoot 'test.ps1') -Jdk $Jdk -WorkDirectory (Join-Path $stage 'tests') |
        Tee-Object -FilePath $buildLog

    $version = @{}
    foreach ($line in (Get-Content -LiteralPath $versionPath)) {
        if ($line -match '^([^#=]+)=(.*)$') { $version[$matches[1]] = $matches[2].Trim() }
    }
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'java') -Recurse -Filter '*.java' -File |
        ForEach-Object FullName)
    $manifestText = Get-Content -LiteralPath (Join-Path $sourceRoot 'AndroidManifest.xml') -Raw
    $manifestText = $manifestText.Replace('<manifest xmlns:', ('<manifest package="' + $version.applicationId + '" xmlns:'))
    $manifest = Join-Path $stage 'AndroidManifest.xml'
    [IO.File]::WriteAllText($manifest,$manifestText,[Text.UTF8Encoding]::new($false))
    $classJar = Join-Path $stage 'module-classes.jar'
    $resources = Join-Path $stage 'resources.zip'
    $unsigned = Join-Path $stage 'unsigned.apk'
    $aligned = Join-Path $stage 'aligned.apk'
    $signed = Join-Path $stage 'signed.apk'

    $classPath = (@($androidJar,$apiJar) + $runtimeJars) -join ';'
    Invoke-Checked (Join-Path $Jdk 'bin\javac.exe') (@('--release','17','-encoding','UTF-8','-classpath',$classPath,'-d',$classes) + $sources)
    Invoke-Checked (Join-Path $Jdk 'bin\jar.exe') @('cf',$classJar,'-C',$classes,'.')
    Invoke-Checked (Join-Path $toolRoot 'd8.bat') (@('--lib',$androidJar,'--classpath',$apiJar,'--min-api',$version.minSdk,'--output',$dex,$classJar) + $runtimeJars)
    # aapt2 on Windows cannot reliably open Unicode absolute resource-directory paths.
    # Run on relative paths in the task-local stage; Java compilation still uses originals.
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'res') -Destination (Join-Path $stage 'res') -Recurse
    Push-Location -LiteralPath $stage
    try {
        Invoke-Checked (Join-Path $toolRoot 'aapt2.exe') @('compile','--dir','res','-o','resources.zip')
        Invoke-Checked (Join-Path $toolRoot 'aapt2.exe') @('link','-o','unsigned.apk','-I',$androidJar,'--manifest','AndroidManifest.xml',
            '--min-sdk-version',$version.minSdk,'--target-sdk-version',$version.targetSdk,
            '--version-code',$version.versionCode,'--version-name',$version.versionName,'resources.zip')
    } finally { Pop-Location }

    $archive = [IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($file in (Get-ChildItem -LiteralPath $dex -Filter 'classes*.dex' -File)) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$file.Name) | Out-Null
        }
        foreach ($file in (Get-ChildItem -LiteralPath $metadataRoot -File)) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,"META-INF/xposed/$($file.Name)") | Out-Null
        }
        $license = Join-Path $sourceRoot 'resources\META-INF\libxposed-service-LICENSE.txt'
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$license,'META-INF/libxposed-service-LICENSE.txt') | Out-Null
    } finally { $archive.Dispose() }

    Invoke-Checked (Join-Path $toolRoot 'zipalign.exe') @('-f','4',$unsigned,$aligned)
    $candidate = $aligned
    $suffix = '-unsigned'
    if ($Keystore) {
        Invoke-Checked (Join-Path $toolRoot 'apksigner.bat') @('sign','--ks',$Keystore,'--ks-key-alias',$KeyAlias,
            '--ks-pass','env:HYPERWIN_STORE_PASSWORD','--key-pass','env:HYPERWIN_KEY_PASSWORD','--out',$signed,$aligned)
        Invoke-Checked (Join-Path $toolRoot 'apksigner.bat') @('verify','--verbose','--print-certs',$signed)
        $candidate = $signed
        $suffix = ''
    }
    Invoke-Checked (Join-Path $toolRoot 'zipalign.exe') @('-c','4',$candidate)
    Invoke-Checked (Join-Path $toolRoot 'aapt2.exe') @('dump','badging',$candidate)
    $permissions = & (Join-Path $toolRoot 'aapt2.exe') dump permissions $candidate
    if ($LASTEXITCODE -ne 0) { throw 'Permissions dump failure.' }
    $requested = @($permissions | Where-Object { $_ -match '^uses-permission' })
    if ($requested.Count -ne 1 -or $requested[0] -notmatch "name='pub.chara.miuipadmeta.modern.permission.CONFIGURE'") {
        throw "Only the module's own signature configuration permission is expected: $requested"
    }

    $archive = [IO.Compression.ZipFile]::OpenRead($candidate)
    try {
        foreach ($name in @('java_init.list','scope.list','module.prop')) {
            $entry = $archive.GetEntry("META-INF/xposed/$name")
            if ($null -eq $entry) { throw "Modern module metadata missing: $name" }
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $packed = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
            $source = (Get-Content -LiteralPath (Join-Path $metadataRoot $name) -Raw).Trim()
            if ($packed -cne $source) { throw "Modern metadata mismatch: $name" }
            if ($name -eq 'scope.list' -and $packed -cne 'system') {
                throw 'A modern system_server module must declare exactly the virtual package system.'
            }
        }
        if ($null -ne $archive.GetEntry('assets/xposed_init')) { throw 'Legacy module entry must not be packaged.' }
    } finally { $archive.Dispose() }

    Push-Location -LiteralPath $dex
    try {
        $dexText = & (Join-Path $toolRoot 'dexdump.exe') 'classes.dex'
        if ($LASTEXITCODE -ne 0) { throw 'DEX inspection failed.' }
    } finally { Pop-Location }
    $classNames = @([regex]::Matches(($dexText -join "`n"), "Class descriptor\s+:\s+'([^']+)'") |
        ForEach-Object { $_.Groups[1].Value })
    $unexpectedClasses = @($classNames | Where-Object {
        -not $_.StartsWith('Lpub/chara/miuipadmeta/modern/') -and
        -not $_.StartsWith('Lio/github/libxposed/service/') -and
        ($_ -cne 'Lcom/android/tools/r8/RecordTag;')
    })
    if ($classNames.Count -lt 3 -or $unexpectedClasses.Count -ne 0) {
        throw "Unexpected DEX classes (API must be compile-only): $classNames"
    }
    $entryClass = (Get-Content -LiteralPath (Join-Path $metadataRoot 'java_init.list') -Raw).Trim()
    $entryDescriptor = 'L' + $entryClass.Replace('.','/') + ';'
    if ($entryDescriptor -cnotin $classNames) { throw "Entry class missing from DEX: $entryClass" }
    $classNames | Add-Content -LiteralPath $buildLog -Encoding utf8
    foreach ($requiredClass in @('Lio/github/libxposed/service/XposedProvider;', 'Lio/github/libxposed/service/IXposedService$Stub;', 'Lpub/chara/miuipadmeta/modern/ScopeServiceProvider;')) {
        if ($requiredClass -cnotin $classNames) { throw "Scope service runtime class missing: $requiredClass" }
    }
    'PASS: modern entry, system_server scope (system), no legacy entry; only own signature configuration permission; Xposed API compile-only.' |
        Add-Content -LiteralPath $buildLog -Encoding utf8

    $final = Join-Path $OutputDirectory ('HyperWin-' + $version.versionName + $suffix + '.apk')
    Copy-Item -LiteralPath $candidate -Destination $final -Force
    $hash = (Get-FileHash -LiteralPath $final -Algorithm SHA256).Hash
    "APK SHA256: $hash" | Add-Content -LiteralPath $buildLog -Encoding utf8
    "Official API AAR SHA256: $officialHash" | Add-Content -LiteralPath $buildLog -Encoding utf8
    'Validation is local compilation/packaging only. Device behavior: NOT TESTED.' |
        Add-Content -LiteralPath $buildLog -Encoding utf8
    Copy-Item -LiteralPath $buildLog -Destination (Join-Path $OutputDirectory 'BUILD_REPORT.txt') -Force
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'SHA256SUMS.txt'),
        "$hash  $([IO.Path]::GetFileName($final))`r`n",[Text.UTF8Encoding]::new($false))
    Get-Item -LiteralPath $final | Select-Object FullName,Length
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
}
