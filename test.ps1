$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$classes = Join-Path $project 'build\architecture-tests'
$root = [IO.Path]::GetFullPath($project).TrimEnd('\') + '\'
$target = [IO.Path]::GetFullPath($classes)
if (-not $target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test output outside project' }
if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
New-Item -ItemType Directory -Path $target | Out-Null
$sources = @(
    'src\com\codex\mediadownloader\DownloadItem.java',
    'src\com\codex\mediadownloader\DownloadRepository.java',
    'src\com\codex\mediadownloader\DownloadsState.java',
    'src\com\codex\mediadownloader\DownloadsController.java',
    'src\com\codex\mediadownloader\OverlayOption.java',
    'src\com\codex\mediadownloader\Mp4SourceMetadata.java',
    'src\com\codex\mediadownloader\PostText.java',
    'src\com\codex\mediadownloader\PhotoQuality.java',
    'src\com\codex\mediadownloader\StreamResolver.java',
    'tests\com\codex\mediadownloader\ArchitectureBoundaryTest.java',
    'tests\com\codex\mediadownloader\Mp4SourceMetadataTest.java',
    'tests\com\codex\mediadownloader\PhotoRenditionTest.java'
) | ForEach-Object { Join-Path $project $_ }
$androidJar = 'D:\Random\AndroidSdk\platforms\android-35\android.jar'
& javac -encoding UTF-8 --release 8 -cp $androidJar -d $target @sources
if ($LASTEXITCODE -ne 0) { throw 'Architecture test compilation failed' }
& java -cp $target com.codex.mediadownloader.ArchitectureBoundaryTest
if ($LASTEXITCODE -ne 0) { throw 'Architecture tests failed' }
& java -cp $target com.codex.mediadownloader.Mp4SourceMetadataTest
if ($LASTEXITCODE -ne 0) { throw 'MP4 metadata tests failed' }
& java -cp "$target;$androidJar" com.codex.mediadownloader.PhotoRenditionTest
if ($LASTEXITCODE -ne 0) { throw 'Photo rendition tests failed' }
