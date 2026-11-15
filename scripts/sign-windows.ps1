param(
    [Parameter(Mandatory = $true)]
    [string]$Executable
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:WINDOWS_CODE_SIGNING_PFX_BASE64) -or
    [string]::IsNullOrWhiteSpace($env:WINDOWS_CODE_SIGNING_PASSWORD)) {
    throw 'Secrets de signature Windows absents : consulte SIGNATURE-WINDOWS.md.'
}
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
    throw 'Exécutable Windows absent.'
}

$sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
if (-not (Test-Path -LiteralPath $sdkRoot -PathType Container)) {
    throw 'SDK Windows introuvable : signtool est requis.'
}
$versions = Get-ChildItem -LiteralPath $sdkRoot -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending
$signTool = $null
foreach ($version in $versions) {
    $candidate = Join-Path $version.FullName 'x64\signtool.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $signTool = $candidate; break }
}
if ($null -eq $signTool) { throw 'signtool.exe x64 introuvable dans le SDK Windows.' }

$certFile = Join-Path $env:RUNNER_TEMP ('maillage-sign-' + [guid]::NewGuid().ToString('N') + '.pfx')
try {
    $pfxBytes = [Convert]::FromBase64String($env:WINDOWS_CODE_SIGNING_PFX_BASE64)
    if ($pfxBytes.Length -eq 0) { throw 'Certificat de signature vide.' }
    [IO.File]::WriteAllBytes($certFile, $pfxBytes)
    [Array]::Clear($pfxBytes, 0, $pfxBytes.Length)

    # Timestamp responses are signed by the RFC 3161 timestamp authority.
    & $signTool sign /f $certFile /p $env:WINDOWS_CODE_SIGNING_PASSWORD `
        /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 $Executable
    if ($LASTEXITCODE -ne 0) { throw "Échec de signature ou d’horodatage (code $LASTEXITCODE)." }

    & $signTool verify /pa /all /v $Executable
    if ($LASTEXITCODE -ne 0) { throw "Signature non vérifiée ou non approuvée (code $LASTEXITCODE)." }

    $signature = Get-AuthenticodeSignature -LiteralPath $Executable
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.TimeStamperCertificate) {
        throw 'Signature invalide ou horodatage absent.'
    }
    Write-Output ('Signature valide. Éditeur : ' + $signature.SignerCertificate.Subject)
    Write-Output ('Empreinte du certificat : ' + $signature.SignerCertificate.Thumbprint)
} finally {
    if (Test-Path -LiteralPath $certFile) {
        Remove-Item -LiteralPath $certFile -Force -ErrorAction SilentlyContinue
    }
}
