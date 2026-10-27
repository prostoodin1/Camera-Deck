param(
    [Parameter(Mandatory=$true)][string]$File,
    [Parameter(Mandatory=$true)][string]$Report
)
$ErrorActionPreference='Stop'
$target=(Resolve-Path -LiteralPath $File).Path
$output=[System.IO.Path]::GetFullPath($Report)
if(Test-Path -LiteralPath $output){Remove-Item -LiteralPath $output}
$status=Get-MpComputerStatus
if(-not $status.AMServiceEnabled -or -not $status.AntivirusEnabled){throw 'Defender unavailable: release blocked'}
if(-not $status.AntivirusSignatureLastUpdated -or $status.AntivirusSignatureLastUpdated -lt (Get-Date).AddDays(-2)){throw 'Defender signatures older than 48 hours: release blocked'}
$scanner=Get-ChildItem -LiteralPath (Join-Path $env:ProgramData 'Microsoft/Windows Defender/Platform') -Directory |
    Where-Object {Test-Path -LiteralPath (Join-Path $_.FullName 'MpCmdRun.exe')} |
    Sort-Object Name -Descending | Select-Object -First 1
if(-not $scanner){throw 'MpCmdRun unavailable: release blocked'}
$hash=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
$started=[DateTimeOffset]::UtcNow
# Microsoft's custom-scan mode ignores exclusions and reports detections without
# remediation. It does not disable real-time protection or alter exclusions.
& (Join-Path $scanner.FullName 'MpCmdRun.exe') -Scan -ScanType 3 -File $target -DisableRemediation
$scanExit=$LASTEXITCODE
if($scanExit -ne 0){throw "Defender detection or scan error ($scanExit): release blocked"}
if(-not (Test-Path -LiteralPath $target) -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw 'Scanned file missing or changed: release blocked'}
$result=@{
    schema=1; scanner='Microsoft Defender'; result='clean'; exitCode=$scanExit
    fileName=[System.IO.Path]::GetFileName($target); sha256=$hash
    signatureVersion=$status.AntivirusSignatureVersion
    engineVersion=$status.AMEngineVersion
    signatureUpdatedAt=([DateTimeOffset]$status.AntivirusSignatureLastUpdated).ToUniversalTime().ToString('o')
    startedAt=$started.ToString('o'); completedAt=[DateTimeOffset]::UtcNow.ToString('o')
    sourceCommit=(& git rev-parse HEAD).Trim()
}
$null=New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($output)) -Force
$result | ConvertTo-Json | Set-Content -LiteralPath $output -Encoding utf8
Write-Output "Defender scan passed: $hash"
