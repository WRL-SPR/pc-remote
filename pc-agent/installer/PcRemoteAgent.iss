; PC 리모트 에이전트 설치 프로그램 (Inno Setup 6)
; Build: ISCC.exe PcRemoteAgent.iss  ->  ..\..\dist\PcRemoteAgent-Setup-<version>.exe

#define AppName "PC 리모트 에이전트"
#define AppVersion "1.0.0"
#define DataDir "{commonappdata}\PcRemoteAgent"

[Setup]
AppId={{8F3B6C2A-5D1E-4A7B-9C3D-2E6F1A0B4C5D}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=WRL-SPR
AppPublisherURL=https://github.com/WRL-SPR/pc-remote
DefaultDirName={autopf}\PC Remote Agent
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
#ifdef PREVIEW
; ISCC /DPREVIEW: a non-admin build that only shows the uninstall options dialog, for screenshots.
PrivilegesRequired=lowest
#else
PrivilegesRequired=admin
#endif
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\..\dist
OutputBaseFilename=PcRemoteAgent-Setup-{#AppVersion}
SetupIconFile=app.ico
UninstallDisplayIcon={app}\app.ico
UninstallDisplayName={#AppName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=no
VersionInfoVersion={#AppVersion}

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Tasks]
Name: "wol"; Description: "Wake-on-LAN 설정 (빠른 시작 끄기, 유선 랜카드 '매직 패킷으로 깨우기' 켜기)"; GroupDescription: "전원 설정:"
Name: "desktopicon"; Description: "바탕 화면에 '연결 정보 보기' 아이콘 만들기"; GroupDescription: "바로 가기:"; Flags: unchecked

[Files]
Source: "..\src\PcAgent.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\src\agent-common.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\src\agent-setup.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\src\agent-remove.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\src\show-info.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "app.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\연결 정보 보기"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\show-info.ps1"""; \
    IconFilename: "{app}\app.ico"; Comment: "휴대폰 앱에 입력할 토큰, IP, MAC 주소 보기"
Name: "{group}\{#AppName} 제거"; Filename: "{uninstallexe}"
Name: "{autodesktop}\PC 리모트 연결 정보"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\show-info.ps1"""; \
    IconFilename: "{app}\app.ico"; Tasks: desktopicon

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\show-info.ps1"""; \
    Description: "연결 정보 보기 (휴대폰 앱에 입력할 토큰·IP·MAC)"; Flags: postinstall nowait skipifsilent

[Code]
var
  PortPage: TInputQueryWizardPage;
  PurgeData: Boolean;
  RestorePower: Boolean;

function PowerShellExe(): String;
begin
  Result := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
end;

{ ---------- Install ---------- }

procedure InitializeWizard();
begin
  PortPage := CreateInputQueryPage(wpSelectTasks,
    '에이전트 포트',
    '휴대폰 앱이 이 PC에 접속할 때 사용할 포트를 정하세요.',
    '특별한 이유가 없으면 기본값 9876을 그대로 쓰세요. 바꾸면 휴대폰 앱의 PC 설정에도 같은 포트를 입력해야 합니다.');
  PortPage.Add('포트:', False);
  PortPage.Values[0] := GetPreviousData('Port', '9876');
end;

procedure RegisterPreviousData(PreviousDataKey: Integer);
begin
  SetPreviousData(PreviousDataKey, 'Port', PortPage.Values[0]);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Port: Integer;
begin
  Result := True;
  if CurPageID = PortPage.ID then
  begin
    Port := StrToIntDef(Trim(PortPage.Values[0]), 0);
    if (Port < 1024) or (Port > 65535) then
    begin
      MsgBox('포트는 1024에서 65535 사이의 숫자로 입력하세요.', mbError, MB_OK);
      Result := False;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Params: String;
  ResultCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    WizardForm.StatusLabel.Caption := '에이전트를 설정하는 중...';
    Params := '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{app}\agent-setup.ps1') + '"' +
      ' -InstallDir "' + ExpandConstant('{app}') + '"' +
      ' -Port ' + IntToStr(StrToIntDef(Trim(PortPage.Values[0]), 9876));
    if WizardIsTaskSelected('wol') then
      Params := Params + ' -SetupWol';
    if not Exec(PowerShellExe(), Params, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) or (ResultCode <> 0) then
      MsgBox('에이전트를 설정하는 중 문제가 생겼습니다 (코드 ' + IntToStr(ResultCode) + ').' + #13#10 +
        ExpandConstant('{#DataDir}') + '\setup.log 파일을 확인하세요.', mbError, MB_OK);
  end;
end;

{ ---------- Uninstall ---------- }

function AddNote(Form: TSetupForm; Top: Integer; const Text: String): TNewStaticText;
begin
  Result := TNewStaticText.Create(Form);
  Result.Parent := Form;
  Result.Left := ScaleX(36);
  Result.Top := Top;
  Result.Width := Form.ClientWidth - ScaleX(52);
  Result.AutoSize := False;
  Result.WordWrap := True;
  Result.Height := ScaleY(32);
  Result.Font.Color := clGray;
  Result.Caption := Text;
end;

{ Asks what to remove, like other uninstallers: data (token, logs) and the power settings we changed. }
function AskUninstallOptions(): Boolean;
var
  Form: TSetupForm;
  Msg: TNewStaticText;
  PurgeBox, RestoreBox: TNewCheckBox;
  OkButton, CancelButton: TNewButton;
  HasWolState: Boolean;
  Y: Integer;
begin
  Result := True;
  PurgeData := False;
  RestorePower := True;
  HasWolState := FileExists(ExpandConstant('{#DataDir}\wol-state.json'));
#ifdef PREVIEW
  HasWolState := True;
#endif

  Form := CreateCustomForm(ScaleX(440), ScaleY(260), False, False);
  try
    Form.Caption := '{#AppName} 제거';

    Msg := TNewStaticText.Create(Form);
    Msg.Parent := Form;
    Msg.Left := ScaleX(16);
    Msg.Top := ScaleY(16);
    Msg.Width := Form.ClientWidth - ScaleX(32);
    Msg.AutoSize := False;
    Msg.WordWrap := True;
    Msg.Height := ScaleY(36);
    Msg.Caption := '에이전트, 부팅 시 자동 실행, 방화벽 규칙을 제거합니다. 휴대폰 앱에서 이 PC를 끄거나 절전시킬 수 없게 됩니다.';
    Y := ScaleY(62);

    PurgeBox := TNewCheckBox.Create(Form);
    PurgeBox.Parent := Form;
    PurgeBox.Left := ScaleX(16);
    PurgeBox.Top := Y;
    PurgeBox.Width := Form.ClientWidth - ScaleX(32);
    PurgeBox.Height := ScaleY(20);
    PurgeBox.Caption := '설정, 토큰, 로그도 모두 삭제';
    AddNote(Form, Y + ScaleY(22), '체크하지 않으면 다시 설치했을 때 같은 토큰을 그대로 써서 휴대폰 앱을 다시 설정할 필요가 없습니다.');
    Y := Y + ScaleY(62);

    RestoreBox := TNewCheckBox.Create(Form);
    RestoreBox.Parent := Form;
    RestoreBox.Left := ScaleX(16);
    RestoreBox.Top := Y;
    RestoreBox.Width := Form.ClientWidth - ScaleX(32);
    RestoreBox.Height := ScaleY(20);
    RestoreBox.Caption := '설치할 때 바꾼 전원 설정을 원래대로 되돌리기';
    RestoreBox.Checked := HasWolState;
    RestoreBox.Enabled := HasWolState;
    if HasWolState then
      AddNote(Form, Y + ScaleY(22), '빠른 시작과 랜카드의 매직 패킷 깨우기 설정을 설치 전 상태로 되돌립니다. 되돌리면 꺼진 상태에서 WOL이 안 될 수 있습니다.')
    else
      AddNote(Form, Y + ScaleY(22), '설치할 때 Wake-on-LAN 설정을 선택하지 않아 되돌릴 설정이 없습니다.');
    Y := Y + ScaleY(70);

    OkButton := TNewButton.Create(Form);
    OkButton.Parent := Form;
    OkButton.Width := ScaleX(90);
    OkButton.Height := ScaleY(26);
    OkButton.Left := Form.ClientWidth - ScaleX(16 + 90 + 8 + 90);
    OkButton.Top := Y;
    OkButton.Caption := '제거';
    OkButton.ModalResult := mrOk;
    OkButton.Default := True;

    CancelButton := TNewButton.Create(Form);
    CancelButton.Parent := Form;
    CancelButton.Width := ScaleX(90);
    CancelButton.Height := ScaleY(26);
    CancelButton.Left := Form.ClientWidth - ScaleX(16 + 90);
    CancelButton.Top := Y;
    CancelButton.Caption := '취소';
    CancelButton.ModalResult := mrCancel;
    CancelButton.Cancel := True;

    Form.ClientHeight := Y + ScaleY(26 + 16);
    Form.ActiveControl := OkButton;

    if Form.ShowModal() = mrOk then
    begin
      PurgeData := PurgeBox.Checked;
      RestorePower := HasWolState and RestoreBox.Checked;
    end
    else
      Result := False;
  finally
    Form.Free();
  end;
end;

function InitializeUninstall(): Boolean;
begin
  PurgeData := False;
  RestorePower := FileExists(ExpandConstant('{#DataDir}\wol-state.json'));
  if UninstallSilent() then
    Result := True
  else
    Result := AskUninstallOptions();
end;

#ifdef PREVIEW
function InitializeSetup(): Boolean;
begin
  AskUninstallOptions();
  Result := False;
end;
#endif

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Params: String;
  ResultCode: Integer;
begin
  { Runs before the program files are deleted, while agent-remove.ps1 still exists. }
  if CurUninstallStep = usUninstall then
  begin
    Params := '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{app}\agent-remove.ps1') + '"';
    if PurgeData then
      Params := Params + ' -PurgeData';
    if RestorePower then
      Params := Params + ' -RestorePower';
    Exec(PowerShellExe(), Params, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;
