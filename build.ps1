$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$sdk = 'D:\Random\AndroidSdk'
$tools = Join-Path $sdk 'build-tools\35.0.0'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
$build = Join-Path $project 'build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$exifArchive = Join-Path $project 'libs\exifinterface-1.4.2.aar'
$exifZip = Join-Path $build 'exifinterface-1.4.2.zip'
$exifExpanded = Join-Path $build 'exifinterface-1.4.2'
$annotationJar = Join-Path $project 'libs\annotation-jvm-1.8.1.jar'
$projectRoot = [IO.Path]::GetFullPath($project).TrimEnd('\') + '\'
foreach ($target in @($classes, $dex)) {
    $absoluteTarget = [IO.Path]::GetFullPath($target)
    if (-not $absoluteTarget.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build path outside project' }
    if (Test-Path -LiteralPath $absoluteTarget) { Remove-Item -LiteralPath $absoluteTarget -Recurse -Force }
}
New-Item -ItemType Directory -Force $build,$classes,$dex | Out-Null
Copy-Item -LiteralPath $exifArchive -Destination $exifZip -Force
Expand-Archive -LiteralPath $exifZip -DestinationPath $exifExpanded -Force
$exifJar = Join-Path $exifExpanded 'classes.jar'
& (Join-Path $tools 'aapt2.exe') compile --dir (Join-Path $project 'res') -o (Join-Path $build 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed' }
& (Join-Path $tools 'aapt2.exe') link -o (Join-Path $build 'base-unsigned.apk') --manifest (Join-Path $project 'AndroidManifest.xml') -I $androidJar (Join-Path $build 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'APK linking failed' }
$sources = @(Get-ChildItem (Join-Path $project 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 8 -cp "$androidJar;$exifJar;$annotationJar" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$classJar = Join-Path $build 'compiled-classes.jar'
& jar cf $classJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Packaging compiled Java classes failed' }
& (Join-Path $tools 'd8.bat') --lib $androidJar --min-api 23 --output $dex $classJar $exifJar $annotationJar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
Push-Location $dex
try { & (Join-Path $tools 'aapt.exe') add (Join-Path $build 'base-unsigned.apk') 'classes.dex' }
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Adding DEX failed' }
& java -jar 'C:\apktool\uber-apk-signer.jar' -a (Join-Path $build 'base-unsigned.apk') -o (Join-Path $build 'release')
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
$signed = Join-Path $build 'release\base-aligned-debugSigned.apk'
if (-not (Test-Path $signed)) { throw 'Signed APK was not produced' }
Copy-Item -LiteralPath $signed -Destination (Join-Path $project 'MediaDownloader.apk') -Force
