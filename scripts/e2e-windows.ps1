# Uji end-to-end agent Windows sungguhan (TLS, SendInput tidak dipakai, encoder Media Foundation) melawan klien
# Android di JVM (OkHttp). Kontrak profil uji: protocol/E2E.md. Dipakai CI (windows.yml) dan bisa dijalankan di PC
# Windows mana pun yang punya .NET 10 SDK dan JDK 17. Semua data profil e2e dihapus di akhir.
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path "$PSScriptRoot\..").Path
$work = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $work | Out-Null
$pairing = Join-Path $work 'pairing.txt'
$focus = Join-Path $work 'focus.txt'
$log = Join-Path $work 'agent.log'

Write-Host '== Build agent'
dotnet build "$root\windows-agent\src\Agent.Windows" -c Release -o "$work\agent" --nologo -v quiet
if ($LASTEXITCODE) { throw 'build agent gagal' }

Write-Host '== Jalankan agent (profil e2e)'
$env:CURSORCTL_PROFILE = 'e2e'
$env:CURSORCTL_PORT = '47811'
$env:CURSORCTL_E2E_ADDRESS = '127.0.0.1'
$env:CURSORCTL_E2E_AUTO_APPROVE = '1'
$env:CURSORCTL_E2E_PAIRING_FILE = $pairing
$env:CURSORCTL_E2E_FOCUS_FILE = $focus
$env:CURSORCTL_E2E_LOG = '1'
$agent = Start-Process -FilePath "$work\agent\CursorController.exe" -PassThru -NoNewWindow `
    -RedirectStandardError $log -RedirectStandardOutput (Join-Path $work 'agent.out')

try {
    for ($i = 0; $i -lt 100 -and -not (Test-Path $pairing); $i++) { Start-Sleep -Milliseconds 200 }
    if (-not (Test-Path $pairing)) {
        Get-Content $log -ErrorAction SilentlyContinue
        throw 'Agent tidak siap dalam 20 detik'
    }
    Write-Host "QR: $(Get-Content $pairing)"

    # Windows Server tanpa Media Foundation tidak punya encoder H.264, jadi agent tidak mengumumkan fitur layar.
    $screen = (Select-String -Path $log -Pattern '^features: .*screen' -Quiet)
    $env:CURSORCTL_E2E_EXPECT_SCREEN = if ($screen) { '1' } else { '0' }
    Write-Host "Fitur layar: $($env:CURSORCTL_E2E_EXPECT_SCREEN)"

    Write-Host '== Klien JVM'
    Push-Location "$root\android-app"
    try {
        .\gradlew.bat -q :core:test --tests '*AgentConnectionE2ETest' --rerun
        if ($LASTEXITCODE) { throw 'uji JVM gagal' }
    }
    finally { Pop-Location }

    Write-Host '== Log agent'
    $lines = Get-Content $log
    $lines
    $expected = @('input: move(dx: 120, dy: -40)', 'input: click(left, count: 1)', 'input: scroll(dx: 0, dy: -60)',
        'input: text("Halo dunia', 'input: key(return, modifiers: 0)', 'input: key(c, modifiers: 8)', 'focus: true', 'focus: false')
    if ($screen) { $expected += @('screen: start', 'screen: streaming', 'screen: stop') }
    foreach ($text in $expected) {
        if (-not ($lines | Where-Object { $_.Contains($text) })) { throw "log agent tidak berisi: $text" }
    }
    Write-Host 'E2E OK'
}
finally {
    Stop-Process -Id $agent.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $env:APPDATA 'Cursor Controller-e2e') -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
