# PC 리모트 (갤럭시용 WOL + 원격 전원 + Artemis)

![화면 미리보기](docs/design_preview.png)

**APK 다운로드:** 이 저장소의 [Releases](../../releases) 페이지에서 `PcRemote.apk`를 받으세요.

바형 갤럭시와 폴드 1~8의 커버·메인 화면, 라이트·다크 모드를 지원합니다.

| 기능 | 동작 방식 |
|---|---|
| 켜기 (WOL) | 매직 패킷을 UDP 브로드캐스트로 전송 (집 밖에서는 ipTIME DDNS로 전송) |
| 절전 / 최대 절전 / 재시작 / 종료 | PC에 설치한 에이전트(`pc-agent`)에 토큰 인증된 HTTP 요청 |
| 부팅 확인 | 전원 켜기 후 2초마다 확인: 켜는 중 → 윈도우 켜짐 → 원격 접속 가능 (최대 3분) |
| Artemis 접속 | Apollo(포트 47989)가 응답할 때만 버튼 활성화, 자동 실행 안 함 |
| 상태 표시 | 5초마다 에이전트 `/ping`과 Apollo 포트 확인 (초록 = 접속 가능, 노랑 = 켜는 중) |

```
android/    안드로이드 앱 (Kotlin + Jetpack Compose)
pc-agent/   윈도우 PC용 에이전트 (PowerShell, 추가 설치 프로그램 필요 없음)
```

---

## 1. PC 설정

### 1-1. BIOS에서 WOL 켜기
메인보드 BIOS에 들어가서 아래 항목을 켭니다. 이름은 제조사마다 다릅니다.
- `Wake on LAN`, `Power On By PCI-E`, `Resume by PCI-E Device`
- `ErP Ready` / `Deep Sleep`은 **꺼야** 합니다. 켜져 있으면 PC가 꺼진 뒤 랜카드 전원도 끊깁니다.

> WOL은 **유선 LAN**으로 연결해야 확실하게 동작합니다. 무선랜에서 WOL을 지원하는 경우는 드뭅니다.

### 1-2. 에이전트 설치
`pc-agent` 폴더에서 **관리자 권한 PowerShell**을 열고 아래 명령을 실행합니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1 -SetupWol
```

`-SetupWol` 옵션은 아래 두 가지를 자동으로 설정합니다.
- 윈도우 **빠른 시작 끄기**: 빠른 시작이 켜져 있으면 완전히 꺼진 상태에서 WOL이 안 되는 경우가 많습니다.
- 유선 랜카드의 **Magic Packet 깨우기 켜기**

설치가 끝나면 **토큰, IP, MAC 주소**가 화면에 출력됩니다. 앱 설정에 이 값을 입력합니다.
- 설치 후에는 PC가 부팅될 때마다 에이전트가 SYSTEM 계정으로 자동 실행됩니다. 로그인하기 전에도 동작합니다.
- 방화벽은 **개인 네트워크**에서만 열립니다. 네트워크가 "공용"으로 설정되어 있으면 "개인"으로 바꿔주세요.
- 제거하려면 `.\uninstall.ps1`을 실행합니다.

### 1-3. 원격 접속용 Apollo
Artemis는 PC에 설치된 [Apollo](https://github.com/ClassicOldSong/Apollo) 또는 Sunshine에 접속합니다. Apollo를 설치한 뒤 Artemis에서 PC를 한 번 페어링해 두세요.

## 2. ipTIME 공유기 설정

1. **고정 IP**: `고급 설정 → 네트워크 관리 → DHCP 서버 설정`에서 PC의 MAC 주소에 IP를 고정합니다.
2. **(집 밖에서 켜려면) DDNS**: `고급 설정 → 특수기능 → DDNS 설정`에서 `myhome.iptime.org` 같은 주소를 등록합니다.
3. **(집 밖에서 켜려면) WOL 포트 포워딩**: `고급 설정 → NAT/라우터 관리 → 포트포워드 설정`에서 아래처럼 추가합니다.
   - 내부 IP: PC의 고정 IP / 프로토콜: **UDP** / 외부 포트: 예) `40009` / 내부 포트: `9`
   - 앱의 "외부 WOL 주소"에는 `myhome.iptime.org`, "WOL 포트"에는 `40009`를 입력합니다.
4. ipTIME 관리 페이지에도 `특수기능 → WOL` 메뉴가 있어서, 공유기 자체 기능으로 PC를 켤 수도 있습니다. 앱이 동작하지 않을 때 이 메뉴로 하드웨어 쪽 문제인지 확인할 수 있습니다.

> ⚠️ **에이전트 포트(9876)는 외부에 포트포워딩하지 마세요.** 집 밖에서 절전·종료나 원격 접속까지 하려면 ipTIME 내장 VPN 서버나 Tailscale 같은 VPN을 켠 상태에서 앱을 사용하세요. 이 방법이 안전하고, Artemis 접속도 같은 방식으로 됩니다.

## 3. 앱 빌드·설치

### 방법 A: Android Studio
1. Android Studio에서 `android` 폴더를 엽니다. Gradle 동기화는 자동으로 진행됩니다.
2. 갤럭시에서 `설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호`를 7번 눌러 개발자 모드를 켭니다.
3. `개발자 옵션 → USB 디버깅`을 켜고 USB로 연결한 뒤 ▶ Run을 누릅니다.

### 화면 스냅샷 (에뮬레이터 불필요)
`android` 폴더에서 `gradle recordPaparazziDebug`를 실행하면 바형·폴드 커버·폴드 메인 화면 크기별 스크린샷이 `app/src/test/snapshots/images/`에 생성됩니다.

### 방법 B: GitHub Actions (PC에 개발 도구 설치 불필요)
이 폴더를 GitHub 저장소에 push하면 `.github/workflows/build-apk.yml`이 APK를 빌드합니다. 빌드가 끝나면 Actions 탭 → Artifacts에서 `app-debug.apk`를 받아 휴대폰에 설치합니다. 설치할 때 "출처를 알 수 없는 앱" 허용이 필요합니다.

### Artemis
[Artemis 릴리스 페이지](https://github.com/ClassicOldSong/moonlight-android/releases)에서 APK를 설치합니다. 앱은 Artemis(`com.limelight.noir`)를 먼저 찾고, 없으면 Moonlight를 실행합니다.

## 4. 앱 사용법
1. **+** 버튼으로 PC를 추가합니다. 이름, IP, MAC, 토큰을 입력합니다.
2. **전원 켜기**: WOL을 보내고 부팅 상태를 보여줍니다. 완전히 켜지면 "원격 접속할 수 있습니다" 알림이 뜹니다.
3. **Artemis 접속**: PC가 원격 접속 가능 상태일 때만 활성화됩니다. 직접 눌러야 실행됩니다.
4. **절전 / 최대 절전 / 재시작 / 종료**: 윈도우가 켜져 있을 때만 활성화되고, 확인 창을 거친 뒤 실행됩니다.

## 문제 해결
| 증상 | 확인할 것 |
|---|---|
| WOL로 안 켜짐 | BIOS의 WOL 설정·ErP 끄기, 빠른 시작 끄기, 유선 연결 여부, ipTIME WOL 메뉴로 테스트 |
| 절전 후에는 켜지는데 완전히 끈 뒤에는 안 켜짐 | 빠른 시작·ErP 설정 확인, 장치 관리자 → 랜카드 → 고급의 "Shutdown Wake-On-Lan" 항목 |
| 상태가 계속 "꺼짐" | 공용 네트워크로 설정됐는지, `C:\ProgramData\PcRemoteAgent\agent.log` 내용, 휴대폰이 같은 Wi-Fi에 연결됐는지 |
| "토큰이 올바르지 않습니다" | `C:\ProgramData\PcRemoteAgent\agent-config.json`의 token 값 (관리자 권한으로 열기) |
