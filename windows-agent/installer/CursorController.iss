; Installer Cursor Controller untuk Windows 10/11 (Inno Setup 6).
; CI: iscc /DAppVersion=0.6.0 /DArch=x64 /DSourceDir=<folder publish> /DOutputName=<nama file> /O<folder keluaran> CursorController.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef Arch
  #define Arch "x64"
#endif
#ifndef SourceDir
  #define SourceDir "..\publish\" + Arch
#endif
#ifndef OutputName
  #define OutputName "cursor-controller-windows-" + Arch
#endif

[Setup]
AppId={{6F1B2C3D-4E5F-4A6B-9C7D-8E9F0A1B2C3D}
AppName=Cursor Controller
AppVersion={#AppVersion}
AppPublisher=Cursor Controller
AppPublisherURL=https://github.com/WailanTirajoh/cursor-controller
DefaultDirName={autopf}\Cursor Controller
DisableProgramGroupPage=yes
; Per-mesin, supaya aturan firewall bisa ditambahkan.
PrivilegesRequired=admin
OutputBaseFilename={#OutputName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\src\Agent.Windows\Assets\AppIcon.ico
UninstallDisplayIcon={app}\CursorController.exe
UninstallDisplayName=Cursor Controller
#if Arch == "arm64"
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
MinVersion=10.0.17763
; Menutup app yang sedang berjalan saat update, lalu membukanya lagi.
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "autostart"; Description: "Jalankan Cursor Controller saat login"; GroupDescription: "Tambahan:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{autoprograms}\Cursor Controller"; Filename: "{app}\CursorController.exe"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Cursor Controller"; ValueData: """{app}\CursorController.exe"""; Tasks: autostart; Flags: uninsdeletevalue

[Run]
; HP menyambung ke port 47810. Aturan hanya untuk jaringan Private dan Domain, bukan jaringan Public (kafe, bandara).
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Cursor Controller"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Cursor Controller"" dir=in action=allow program=""{app}\CursorController.exe"" enable=yes profile=private,domain"; Flags: runhidden
Filename: "{app}\CursorController.exe"; Description: "Jalankan Cursor Controller"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Cursor Controller"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"
Filename: "{sys}\taskkill.exe"; Parameters: "/IM CursorController.exe /F"; Flags: runhidden; RunOnceId: "StopApp"
