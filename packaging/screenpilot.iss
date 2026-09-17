; AppFleet-compatible Inno Setup 6 installer. Values are supplied by Gradle/ISCC.
#ifndef SourceDir
  #error SourceDir must point to the prepared jpackage app-image.
#endif
#ifndef OutputDir
  #error OutputDir must point to the release artifact directory.
#endif
#ifndef IconFile
  #error IconFile must point to the ScreenPilot ICO file.
#endif
#ifndef AppId
  #error AppId must be the permanent ScreenPilot AppFleet UUID.
#endif
#ifndef AppVersion
  #error AppVersion must use the Gradle release version.
#endif
#ifndef RepositoryUrl
  #error RepositoryUrl must point to the canonical ScreenPilot repository.
#endif

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Pavel Kuzmin
AppPublisherURL={#RepositoryUrl}
AppSupportURL={#RepositoryUrl}
AppUpdatesURL={#RepositoryUrl}
UninstallDisplayName={#AppName}
DefaultDirName={localappdata}\Programs\PashaApps\{#TechnicalName}
DefaultGroupName={#AppName}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UsePreviousAppDir=yes
UsePreviousGroup=yes
UsePreviousTasks=yes
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
OutputDir={#OutputDir}
OutputBaseFilename={#TechnicalName}-Setup-{#AppVersion}-x64
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\{#TechnicalName}.exe
VersionInfoVersion={#AppVersion}
VersionInfoCompany=Pavel Kuzmin
VersionInfoDescription=ScreenPilot installer
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#AppVersion}

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Дополнительные ярлыки:"; Flags: unchecked

[InstallDelete]
; Only replaceable app-image content is cleared before the new image is copied.
Type: files; Name: "{app}\{#TechnicalName}.exe"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\icons"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#TechnicalName}.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#TechnicalName}.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Registry]
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "SchemaVersion"; ValueData: "1"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "AppId"; ValueData: "{#AppId}"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Name"; ValueData: "{#AppName}"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "TechnicalName"; ValueData: "{#TechnicalName}"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Version"; ValueData: "{#AppVersion}"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallLocation"; ValueData: "{app}"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Executable"; ValueData: "{app}\{#TechnicalName}.exe"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "ProcessName"; ValueData: "{#TechnicalName}.exe"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "RepositoryUrl"; ValueData: "{#RepositoryUrl}"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallerType"; ValueData: "inno"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstalledBy"; ValueData: "installer"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Publisher"; ValueData: "Pavel Kuzmin"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "UninstallString"; ValueData: """{uninstallexe}"""
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "QuietUninstallString"; ValueData: """{uninstallexe}"" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART"
Root: HKCU64; Subkey: "Software\PashaApps\{#AppId}"; Flags: uninsdeletekey

[Run]
; Never launch the app from a silent installation.
Filename: "{app}\{#TechnicalName}.exe"; Description: "Запустить {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; User data under AppData remains intact. Only replaceable app-image files are removed.
Type: files; Name: "{app}\{#TechnicalName}.exe"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\icons"
Type: dirifempty; Name: "{app}"
