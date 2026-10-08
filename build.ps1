# 构建 spike APK（使用本机已有的 Gradle 9.7.1，避免下载 wrapper 发行版）
param(
    [string]$Task = 'assembleDebug',
    [switch]$Install,
    [string]$Device = '101.42.15.60:6000'
)

$ErrorActionPreference = 'Continue'

$GradleBin = "D:\AndroidDev\gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat"
if (-not (Test-Path $GradleBin)) {
    $GradleBin = Get-ChildItem "D:\AndroidDev\gradle\wrapper\dists\gradle-9.7.1-bin" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $GradleBin -or -not (Test-Path $GradleBin)) { throw 'gradle launcher not found' }

$env:JAVA_HOME = 'D:\ReverseTools\jdk21'
$env:ANDROID_HOME = 'D:\AndroidDev\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\AndroidDev\android-sdk'
$env:GRADLE_USER_HOME = 'D:\AndroidDev\gradle'

Set-Location 'd:\Proot-PiDurable-Android\app'

Write-Host "== gradle $Task ==" -ForegroundColor Cyan
& $GradleBin $Task --console=plain
$code = $LASTEXITCODE
Write-Host "== gradle exit=$code ==" -ForegroundColor Cyan
if ($code -ne 0) { exit $code }

if ($Install) {
    $apk = 'd:\Proot-PiDurable-Android\app\app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw "apk not found: $apk" }
    $adb = 'D:\AndroidDev\android-sdk\platform-tools\adb.exe'
    Write-Host '== push apk ==' -ForegroundColor Cyan
    & $adb -s $Device push $apk /data/local/tmp/spike-app.apk | Out-Null
    Write-Host '== install (root) ==' -ForegroundColor Cyan
    & $adb -s $Device shell "su -c 'pm install -r -t /data/local/tmp/spike-app.apk'"
    Write-Host "== install exit=$LASTEXITCODE ==" -ForegroundColor Cyan
}
