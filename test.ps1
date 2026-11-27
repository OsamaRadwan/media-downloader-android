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
    'src\com\codex\mediadownloader\DownloadIdentity.java',
    'src\com\codex\mediadownloader\MediaFileIdentity.java',
    'src\com\codex\mediadownloader\DownloadRetry.java',
    'src\com\codex\mediadownloader\DownloadRepository.java',
    'src\com\codex\mediadownloader\DownloadHistoryQuery.java',
    'src\com\codex\mediadownloader\DownloadsState.java',
    'src\com\codex\mediadownloader\DownloadsController.java',
    'src\com\codex\mediadownloader\OverlayOption.java',
    'src\com\codex\mediadownloader\OverlayGeometry.java',
    'src\com\codex\mediadownloader\Mp4SourceMetadata.java',
    'src\com\codex\mediadownloader\SourcePostUrl.java',
    'src\com\codex\mediadownloader\RedNoteVideoPolicy.java',
    'src\com\codex\mediadownloader\PostText.java',
    'src\com\codex\mediadownloader\PhotoQuality.java',
    'src\com\codex\mediadownloader\VideoQuality.java',
    'src\com\codex\mediadownloader\InstagramAlbumPage.java',
    'src\com\codex\mediadownloader\DiagnosticsFormat.java',
    'src\com\codex\mediadownloader\DiagnosticsRetention.java',
    'src\com\codex\mediadownloader\DiagnosticsFilePruner.java',
    'src\com\codex\mediadownloader\DiagnosticsDailyLog.java',
    'src\com\codex\mediadownloader\MediaRequestCookies.java',
    'src\com\codex\mediadownloader\MediaResponseLength.java',
    'src\com\codex\mediadownloader\NetworkRoute.java',
    'src\com\codex\mediadownloader\NetworkRouteStore.java',
    'src\com\codex\mediadownloader\HttpDeadline.java',
    'src\com\codex\mediadownloader\StreamResolver.java',
    'src\com\codex\mediadownloader\AlbumItemIdentity.java',
    'src\com\codex\mediadownloader\TikTokStoryBatch.java',
    'src\com\codex\mediadownloader\InstagramStoryBatch.java',
    'src\com\codex\mediadownloader\SharedPostLink.java',
    'tests\com\codex\mediadownloader\ArchitectureBoundaryTest.java',
    'tests\com\codex\mediadownloader\DownloadHistoryQueryTest.java',
    'tests\com\codex\mediadownloader\DownloadRetryTest.java',
    'tests\com\codex\mediadownloader\DownloadIdentityTest.java',
    'tests\com\codex\mediadownloader\MediaFileIdentityTest.java',
    'tests\com\codex\mediadownloader\Mp4SourceMetadataTest.java',
    'tests\com\codex\mediadownloader\PhotoRenditionTest.java',
    'tests\com\codex\mediadownloader\VideoQualityTest.java',
    'tests\com\codex\mediadownloader\InstagramAlbumPageTest.java',
    'tests\com\codex\mediadownloader\DiagnosticsFormatTest.java'
    'tests\com\codex\mediadownloader\DiagnosticsRetentionTest.java'
    'tests\com\codex\mediadownloader\DiagnosticsFilePrunerTest.java'
    'tests\com\codex\mediadownloader\DiagnosticsDailyLogTest.java'
    'tests\com\codex\mediadownloader\MediaRequestCookiesTest.java'
    'tests\com\codex\mediadownloader\MediaResponseLengthTest.java'
    'tests\com\codex\mediadownloader\NetworkRouteTest.java'
    'tests\com\codex\mediadownloader\HttpDeadlineTest.java'
    'tests\com\codex\mediadownloader\RedNoteVideoPolicyTest.java'
    'tests\com\codex\mediadownloader\TikTokStoryBatchTest.java',
    'tests\com\codex\mediadownloader\InstagramStoryBatchTest.java',
    'tests\com\codex\mediadownloader\SharedPostLinkTest.java',
    'src\com\codex\mediadownloader\StoryProgressBar.java',
    'tests\com\codex\mediadownloader\StoryProgressBarTest.java'
    'tests\com\codex\mediadownloader\AlbumMediaPlanTest.java'
) | ForEach-Object { Join-Path $project $_ }
$androidJar = 'D:\Random\AndroidSdk\platforms\android-35\android.jar'
& javac -encoding UTF-8 --release 8 -cp $androidJar -d $target @sources
if ($LASTEXITCODE -ne 0) { throw 'Architecture test compilation failed' }
& java -cp $target com.codex.mediadownloader.ArchitectureBoundaryTest
if ($LASTEXITCODE -ne 0) { throw 'Architecture tests failed' }
& java -cp $target com.codex.mediadownloader.DownloadHistoryQueryTest
if ($LASTEXITCODE -ne 0) { throw 'Download history query tests failed' }
& java -cp $target com.codex.mediadownloader.DownloadRetryTest
if ($LASTEXITCODE -ne 0) { throw 'Download retry tests failed' }
& java -cp $target com.codex.mediadownloader.DownloadIdentityTest
if ($LASTEXITCODE -ne 0) { throw 'Download identity tests failed' }
& java -cp $target com.codex.mediadownloader.MediaFileIdentityTest
if ($LASTEXITCODE -ne 0) { throw 'Media file identity tests failed' }
& java -cp $target com.codex.mediadownloader.Mp4SourceMetadataTest
if ($LASTEXITCODE -ne 0) { throw 'MP4 metadata tests failed' }
& java -cp "$target;$androidJar" com.codex.mediadownloader.PhotoRenditionTest
if ($LASTEXITCODE -ne 0) { throw 'Photo rendition tests failed' }
& java -cp $target com.codex.mediadownloader.VideoQualityTest
if ($LASTEXITCODE -ne 0) { throw 'Video quality tests failed' }
& java -cp $target com.codex.mediadownloader.InstagramAlbumPageTest
if ($LASTEXITCODE -ne 0) { throw 'Instagram album page tests failed' }
& java -cp $target com.codex.mediadownloader.DiagnosticsFormatTest
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic format tests failed' }
& java -cp $target com.codex.mediadownloader.DiagnosticsRetentionTest
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic retention tests failed' }
& java -cp $target com.codex.mediadownloader.DiagnosticsFilePrunerTest
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic file pruning tests failed' }
& java -cp $target com.codex.mediadownloader.DiagnosticsDailyLogTest
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic daily log tests failed' }
& java -cp $target com.codex.mediadownloader.MediaRequestCookiesTest
if ($LASTEXITCODE -ne 0) { throw 'Media request cookie tests failed' }
& java -cp $target com.codex.mediadownloader.MediaResponseLengthTest
if ($LASTEXITCODE -ne 0) { throw 'Media response length tests failed' }
& java -cp $target com.codex.mediadownloader.NetworkRouteTest
if ($LASTEXITCODE -ne 0) { throw 'Network route tests failed' }
& java -cp $target com.codex.mediadownloader.HttpDeadlineTest
if ($LASTEXITCODE -ne 0) { throw 'HTTP deadline tests failed' }
& java -cp $target com.codex.mediadownloader.RedNoteVideoPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'RedNote video policy tests failed' }
& java -cp $target com.codex.mediadownloader.TikTokStoryBatchTest
if ($LASTEXITCODE -ne 0) { throw 'TikTok Story batch tests failed' }
& java -cp $target com.codex.mediadownloader.InstagramStoryBatchTest
if ($LASTEXITCODE -ne 0) { throw 'Instagram Story batch tests failed' }
& java -cp $target com.codex.mediadownloader.StoryProgressBarTest
if ($LASTEXITCODE -ne 0) { throw 'Story progress bar tests failed' }
& java -cp $target com.codex.mediadownloader.AlbumMediaPlanTest
if ($LASTEXITCODE -ne 0) { throw 'Album media plan tests failed' }
& java -cp $target com.codex.mediadownloader.SharedPostLinkTest
if ($LASTEXITCODE -ne 0) { throw 'Shared post link tests failed' }
