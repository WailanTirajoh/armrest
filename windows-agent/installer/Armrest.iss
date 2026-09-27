; Installer Armrest untuk Windows 10/11 (Inno Setup 6).
; CI: iscc /DAppVersion=0.6.0 /DArch=x64 /DSourceDir=<folder publish> /DOutputName=<nama file> /O<folder keluaran> Armrest.iss

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
  #define OutputName "armrest-windows-" + Arch
#endif

[Setup]
AppId={{20E836D6-FD41-4D28-BB8D-DF7035E3A91E}
AppName=Armrest
AppVersion={#AppVersion}
AppPublisher=Armrest
AppPublisherURL=https://github.com/WailanTirajoh/armrest
DefaultDirName={autopf}\Armrest
DisableProgramGroupPage=yes
; Per-mesin, supaya aturan firewall bisa ditambahkan.
PrivilegesRequired=admin
OutputBaseFilename={#OutputName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\src\Agent.Windows\Assets\AppIcon.ico
UninstallDisplayIcon={app}\Armrest.exe
UninstallDisplayName=Armrest
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
Name: "autostart"; Description: "Start Armrest when I sign in"; GroupDescription: "Additional tasks:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{autoprograms}\Armrest"; Filename: "{app}\Armrest.exe"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Armrest"; ValueData: """{app}\Armrest.exe"""; Tasks: autostart; Flags: uninsdeletevalue

[Run]
; HP menyambung ke port 47810. Aturan hanya untuk jaringan Private dan Domain, bukan jaringan Public (kafe, bandara).
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Armrest"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Armrest"" dir=in action=allow program=""{app}\Armrest.exe"" enable=yes profile=private,domain"; Flags: runhidden
Filename: "{app}\Armrest.exe"; Description: "Launch Armrest"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Armrest"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"
Filename: "{sys}\taskkill.exe"; Parameters: "/IM Armrest.exe /F"; Flags: runhidden; RunOnceId: "StopApp"
