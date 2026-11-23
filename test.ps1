param(
    [string] $Jdk = $env:JAVA_HOME,
    [string] $WorkDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME or pass -Jdk.' }
if (-not $WorkDirectory) {
    $WorkDirectory = Join-Path $PSScriptRoot ('build\tests_' + [Guid]::NewGuid().ToString('N'))
}
New-Item -ItemType Directory -Path $WorkDirectory -Force | Out-Null
$mainSource = Join-Path $PSScriptRoot 'src\main\java\pub\chara\miuipadmeta\modern'
$sources = @('WinPolicy.java','WinPresses.java','ScopeSelection.java','AltTabPresses.java') | ForEach-Object { Join-Path $mainSource $_ }
$testSources = @('WinPolicyTest.java','WinScopeTest.java','AltTabTest.java') | ForEach-Object { Join-Path $PSScriptRoot "tests\$_" }
& (Join-Path $Jdk 'bin\javac.exe') --release 17 -encoding UTF-8 -d $WorkDirectory @sources @testSources
if ($LASTEXITCODE -ne 0) { throw 'Policy test compilation failed.' }
& (Join-Path $Jdk 'bin\java.exe') -cp $WorkDirectory pub.chara.miuipadmeta.modern.WinPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Policy tests failed.' }
& (Join-Path $Jdk 'bin\java.exe') -cp $WorkDirectory pub.chara.miuipadmeta.modern.WinScopeTest
if ($LASTEXITCODE -ne 0) { throw 'Scope tests failed.' }
& (Join-Path $Jdk 'bin\java.exe') -cp $WorkDirectory pub.chara.miuipadmeta.modern.AltTabTest
if ($LASTEXITCODE -ne 0) { throw 'Alt+Tab tests failed.' }
