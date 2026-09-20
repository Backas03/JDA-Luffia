# JDA-Luffia
해당 디스코드 봇을 사용하여 길드 내 여러 음성채팅방에서 노래 재생, 이메일 본인인증, 게임 전적 검색 기능을 서비스 할 수 있습니다

## Information
### 이 봇은 Discord bagkaseu(박카스#9970) 에 의해 제작되었습니다.

* 지원 명령어
```
/재생 <검색어 또는 링크> (출처)
  ㄴ 유튜브/스포티파이 링크(곡, 앨범, 플레이리스트)는 바로 재생하고, 검색어는 선택한 출처(유튜브, 유튜브 뮤직, 스포티파이)에서 검색합니다.
  ㄴ 스포티파이는 오디오를 제공하지 않으므로 곡 정보(ISRC, 제목)로 유튜브에서 찾아 재생합니다.
  ㄴ 스포티파이 플레이리스트는 봇에 로그인된 Spotify 계정이 만들었거나 저장한 것만 재생됩니다. (아래 "스포티파이 플레이리스트 설정" 참고)
  ㄴ 스포티파이 아티스트 링크는 2026년 2월 API 개편으로 Development Mode 앱에서 읽을 수 없어 지원하지 않습니다.
/이퀄라이저 <모드 (자동완성 지원)>
  ㄴ 재생중인 노래에 이퀄라이저를 적용합니다. (일반, 저음 강조, 초저음 강조, 고음 강조, 초고음 강조, 풍성하게, 보컬 강조, 보컬 감소, 팝, 록, 힙합, 일렉트로닉, 어쿠스틱, 라우드니스, 노래방)
/노래방모드 (모드)
  ㄴ 노래방 모드를 설정합니다. 에코(보컬 위주 에코), 보컬 제거(센터 보컬 상쇄, 곡에 따라 잔여 보컬 있음), 끄기. 모드를 비우면 에코를 켜고 끕니다.
/속도 <배속(0.2배속 ~ 3.0 배속)>
  ㄴ 재생중인 노래의 재생속도를 변경합니다.
/가사 (모드) (오프셋)
  ㄴ 현재 곡의 가사를 표시합니다. 실시간(기본): 타임스탬프 가사를 재생 위치에 맞춰 이전/현재/다음 줄로 갱신, 전체: 가사 전문, 끄기. 오프셋(ms)으로 싱크를 조정합니다. (가사 출처: LRCLIB)
/볼륨 (퍼센트)
  ㄴ 노래 봇의 볼륨을 변경합니다. (0 ~ 200, 기본 10) 값을 비우면 현재 볼륨을 보여줍니다.
/나가기
  ㄴ 노래 대기열과 설정들을 모두 초기화하고 음성채팅방에서 퇴장시킵니다.
/대기열
  ㄴ 현재 재생중인 곡, 대기열과 같은 모든 정보를 확인합니다.
/일시정지
  ㄴ 재생 중인 노래를 멈추거나 다시 재생합니다.
/스킵 (곡수)
  ㄴ 재생 중인 노래를 건너뜁니다. 곡수를 주면 현재 곡 포함 그만큼 건너뜁니다.
/반복모드 <모드 (자동완성 지원)>
  ㄴ 노래의 반복 모드를 설정합니다. (현재 노래 반복, 모든 트랙 반복, 반복 없음)

/롤정보 <닉네임#태그>
  ㄴ 소환사의 롤 정보를 확인합니다.

/인증 <이메일>
  ㄴ 이메일로 본인인증을 위한 6자리 코드를 받습니다.
/인증 <인증코드>
  ㄴ 이메일로 받은 6자리 코드를 입력하여 이메일 인증을 완료합니다. 완료된 유저는 특정 역할이 부여됩니다. (*역할은 id로 설정가능)
```

* 해당 봇의 오류나 건의, 문의사항이 있을시 제작자에게 문의 또는 [Issue](https://github.com/Backas03/JDA-Luffia/issues) 바랍니다.
* 모든 Pull Request 코드에는 시작과 끝 부분에 아래 형식과 같이 표기하여야 합니다. 아래는 예시입니다. </br>
  [[../kr/kro/backas/Luffia.java]](https://github.com/Backas03/JLuffia/blob/master/src/main/java/kr/kro/backas/Luffia.java)
  ```java
  public Luffia(JDA discordAPI) throws IOException {
    this.discordAPI = discordAPI;
  
    this.commandManager = new CommandManager("!", discordAPI);
    this.commandManager.registerCommand("인증", new CertificationCommand());
    this.commandManager.registerCommand("정보", new CertificationInfoCommand());
    this.commandManager.registerCommand("인증해제", new CertificationRemoveCommand());
    this.commandManager.registerCommand("도움말", new HelpCommand());

    this.certificationManager = new CertificationManager(discordAPI);
  
    아래 4줄이 PR에서 추가된 부분 입니다
    /* [backas03] add command start */
    this.commandManager.registerCommand("테스트1", null);
    this.commandManager.registerCommand("테스트2", null);   
    /* [backas03] add command end */      

    this.discordAPI.getPresence().setActivity(Activity.playing("!도움말 명령어로 기능 확인"));
  }
  ```
## 봇 사용방법
### 1. src/main/java/kr/kro/backas/secret/ 폴더에 BotSecret.java 파일을 생성하고 아래와 같이 작성합니다
- src/main/java/kr/kro/backas/secret/BotSecret.java
```java
package kr.kro.backas.secret;

import java.util.List;

public final class BotSecret {
    // 대학교 인증 메일을 보내기 위한 구글 이메일 ID
    public static final String EMAIL = "emailcert@gmail.com";

    // 대학교 인증 메일을 보내기 위한구글 이메일 앱 비밀번호
    public static final String APP_PASSWORD = "asdqgdavxqwekwa";

    // 봇 토큰
    public static final String TOKEN = "AAA5NfMasdzwqeqw4MTM2NzExMg.GasdfgO.XijzuasdJDoasdfgOZzeeKNQ6tRz_I";

    // SharedConstant.ON_DEV == true 일때 사용할 봇 토큰 (입력하지 않아도 됩니다)
    public static final String DEV_TOKEN = "";

    // 롤 전적 검색 기능을 위한 라이엇 API key
    public static final String RIOT_API_KEY = "RGAPI-6asdhgas79-0qw1-4aa1-98dd-ede3asda137d76";

    // 하나의 서버에 여러 음성채팅방에서 음악을 재생시키기 위한 봇 토큰
    // (해당 토큰은 실제 존재하지 않는 토큰입니다)
    public static final List<String> MUSIC_BOT_TOKENS = List.of(
            "ggg1MzI1MzkasdExODMwNA.Gm3wvS.sfasfas4pPi9d_a4zrqHClEp3IxPpqkGWrYQ3h1t-Tk", // bot 1
            "eeeasdNzQ2NTEyNzAzNDg4MA.G6etwH.CSwxF9TkKeosfasfasfasasrqwrw" // bot 2
    );

    // 스포티파이 링크/검색 지원용. https://developer.spotify.com/dashboard 에서 앱을 만들고 발급받습니다.
    // 비워두면 스포티파이 기능만 꺼지고 유튜브는 정상 동작합니다.
    // 2026년 2월 이후 Development Mode 앱은 소유자 계정에 Spotify Premium 이 필요합니다.
    public static final String SPOTIFY_CLIENT_ID = "";
    public static final String SPOTIFY_CLIENT_SECRET = "";

    // 스포티파이 플레이리스트 링크 지원용. 아래 "스포티파이 플레이리스트 설정" 절을 참고해 발급받습니다. 비워두면 플레이리스트만 비활성화됩니다.
    public static final String SPOTIFY_REFRESH_TOKEN = "";

    // 가사 한국어 번역 사이드카 주소 (예: "http://127.0.0.1:8765"). 아래 "가사 번역 설정" 절 참고. 비워두면 번역만 비활성화됩니다.
    public static final String TRANSLATOR_URL = "";
}
```
### 2. SharedConstant.java 에서 MAIN_GUILD_ID 를 서비스 할 서버 guild id로 수정합니다 </br>
(해당 서버에서만 명령어를 작동하도록 설정하는 부분으로, 수정하지 않으면 명령어가 작동하지 않습니다.)
- kr/kro/backas/SharedConstant.java
```java
package kr.kro.backas;

public final class SharedConstant {
    public static final long MAIN_GUILD_ID = 791974345965961237L; // 봇을 초대할 서버 id 로 변경

    public static final long DEV_GUILD_ID = 1121632283154202694L;
    public static final long PUBLISHED_GUILD_ID = MAIN_GUILD_ID;

    public static final boolean ON_DEV = false;

    public static final String RELEASE_VERSION = "Luffia/2.1.0-Stable-Release";

    public static final String LICENSE = "MIT license";

    public static final String GITHUB = "https://github.com/Backas03/JDA-Luffia";
}
```
### 3. 봇을 실행하려면 ```./gradlew run``` 을 입력합니다 </br>
- JDK 25 이상이 필요합니다. Discord 음성 채널이 2026년 3월부터 DAVE(종단간 암호화)를 필수로 요구하며, 이를 구현한 JDAVE 라이브러리가 Java 25 이상에서만 동작합니다.
- ```./gradlew run``` 은 필요한 JVM 옵션(--enable-native-access=ALL-UNNAMED)을 자동으로 넣습니다.
- 서버에 배포할 때는 ```./gradlew fatJar``` 로 ```build/libs/JDA-Luffia-1.0.0-SNAPSHOT-all.jar``` 를 만들고 ```java -Dfile.encoding=UTF-8 -jar JDA-Luffia-1.0.0-SNAPSHOT-all.jar``` 로 실행합니다. (매니페스트에 네이티브 접근 옵션이 포함되어 있습니다)
## 기능 소개
### 1. 이메일 본인인증 기능
 - 이메일로 인증 코드를 받아 본인 인증을 진행 할 수 있습니다
 - 해당 기능을 사용하여 인증 역할을 부여할 수 있습니다
```
/인증 [이메일]: - 해당 학교 이메일로 인증 코드를 전송받습니다

---- 관리자 명령어 ----
!인증해제 [userId] - 해당 유저의 인증 데이터를 해제합니다.
!인증정보 [userId] - 해당 유저의 인증 데이터를 확인합니다.
!강제인증 [userId] [이메일] - 해당 유저를 관리자의 권한으로 강제 인증시킵니다.
```
### 2. 뮤직 플레이어 기능
 - LavaPlayer(youtube-source, LavaSrc) 라이브러리를 사용하여 유튜브/스포티파이 링크 또는 검색어로 디스코드에서 음악을 재생할 수 있습니다 </br>
 - 스포티파이 곡/앨범/플레이리스트 링크는 곡 정보를 읽어 유튜브에서 같은 곡을 찾아 재생합니다 (미러링) </br>
 - 추가적으로 봇을 추가하여 하나의 디코방에서 여려 음성채팅방에서 음악을 재생할 수 있습니다 </br>
   (봇 상태메시지에 현재 재생중인 음성채팅방의 이름을 표기합니다) </br>
![image](https://github.com/Backas03/JDA-Luffia/assets/71801733/8850d664-b12c-4569-b403-59e358bb796c)
![image](https://github.com/Backas03/JDA-Luffia/assets/71801733/95db993f-c22c-4c16-86cd-f8f3a28da4b5) </br>

해당 기능을 사용하기 위해서는 secret/BotSecret.java 파일의</br>
``public static final List<String> MUSIC_BOT_TOKENS`` 항목에 값을 추가해주시면 됩니다 </br>
- secret/BotSecret.java
``` java
// (해당 토큰은 실제 존재하지 않는 토큰입니다)
public static final List<String> MUSIC_BOT_TOKENS = List.of(
            "MTE1MzI1MzkasdExODMwNA.Gm3wvS.sfasfas4pPi9d_a4zrqHClEp3IxPpqkGWrYQ3h1t-Tk", // bot 1
            "MTEasdNzQ2NTEyNzAzNDg4MA.G6etwH.CSwxF9TkKeosfasfasfasasrqwrw" // bot 2
);
```


#### 스포티파이 플레이리스트 설정
2026년 2월 Spotify API 개편 이후 플레이리스트 곡 목록은 사용자 로그인 토큰이 있어야만 읽을 수 있습니다. 한 번만 아래 순서로 설정하면 됩니다.
1. Spotify 개발자 대시보드의 앱 설정에서 Redirect URI 에 ``http://127.0.0.1:8888/callback`` 을 추가합니다.
2. ``./gradlew spotifyLogin`` 을 실행하고 출력된 주소를 브라우저에서 열어 Spotify 계정으로 로그인합니다.
3. 터미널에 출력된 refresh token 을 ``BotSecret.SPOTIFY_REFRESH_TOKEN`` 에 넣고 봇을 다시 시작합니다.

로그인한 계정이 만들었거나 라이브러리에 저장한 플레이리스트만 읽을 수 있습니다. 다른 사람의 플레이리스트를 재생하려면 해당 계정에서 먼저 저장해 두어야 합니다.

#### 가사 번역 설정
`/가사` 실시간 표시에서 현재 줄 아래에 한국어 번역을 붙이려면 번역 사이드카(Python, CPU 전용, GPU 불필요)를 같은 서버에 띄웁니다. Meta NLLB-200 모델을 CTranslate2 int8 로 실행하며 RAM 약 2GB 를 사용합니다.
1. 서버에 Python 3.10 이상을 준비합니다.
2. ``translator/setup.sh`` 를 실행합니다. 의존성 설치와 모델 변환(약 2.5GB 다운로드)을 한 번만 합니다.
3. ``translator/run.sh`` 로 사이드카를 실행합니다. (기본 127.0.0.1:8765, ``TRANSLATOR_PORT`` 로 변경)
4. ``BotSecret.TRANSLATOR_URL`` 에 ``http://127.0.0.1:8765`` 를 넣고 봇을 다시 시작합니다.

번역은 곡마다 앞 5줄을 먼저, 이후 10줄씩 이어서 처리하며 결과는 메모리에 캐시됩니다. 한국어 가사는 번역하지 않습니다.
타임스탬프가 없어 전체 가사로 표시되는 곡은 원문을 먼저 띄운 뒤 번역이 끝나면 같은 메시지를 수정해 각 줄 아래에 번역을 끼워 넣습니다. (임베드 글자 제한을 넘는 뒷부분은 생략 표시)

### 3. 게임 전적 검색 기능 </br>
- !롤정보 [닉네임] 으로 정보를 검색할 수 있습니다 </br>
![image](https://github.com/Backas03/JDA-Luffia/assets/71801733/6d5395d0-db25-4b94-bb34-c98561824c17) </br>
- !메이플정보 [닉네임] 으로 정보를 검색할 수 있습니다 (unstable ~~지원 중단~~) </br>

## 명령어 비활성화 방법
Luffia.java에서 this.commandManager.registerCommand를 주석처리하면 됩니다
- kr/kro/backas/Luffia.java
```java
public Luffia(JDA discordAPI) throws IOException, InterruptedException {
    this.discordAPI = discordAPI;

    this.commandManager = new CommandManager("!", this);

    this.commandManager.registerSlashCommand(new SlashCertificationCommand());

    this.commandManager.registerCommand("인증정보", new CertificationInfoCommand());
    this.commandManager.registerCommand("인증해제", new CertificationRemoveCommand());
    this.commandManager.registerCommand("도움말", new HelpCommand());
    this.commandManager.registerCommand("강제인증", new ForceCertificationCommand());

    this.commandManager.registerCommand("재생", new PlayCommand());
    this.commandManager.registerCommand("나가기", new QuitCommand());
    this.commandManager.registerCommand("스킵", new SkipCommand());
    this.commandManager.registerCommand("일시정지", new PauseCommand());
    this.commandManager.registerCommand("일시정지해제", new ResumeCommand());
    //this.commandManager.registerCommand("전체반복", new RepeatAllCommand()); // 비활성화
    //this.commandManager.registerCommand("반복", new RepeatCurrentCommand()); // 비활성화
    //this.commandManager.registerCommand("반복해제", new NoRepeatCommand()); // 비활성화
    this.commandManager.registerCommand("대기열", new QueueCommand());

    this.commandManager.registerCommand("롤정보", new LOLUserInfoCommand());

    this.commandManager.registerCommand("메이플정보", new MapleUserInfoCommand());

    this.certificationManager = new CertificationManager(discordAPI);

    this.musicPlayerController = new MusicPlayerController();
    discordAPI.addEventListener(this.musicPlayerController);
    for (String botToken : BotSecret.MUSIC_BOT_TOKENS) {
        this.musicPlayerController.register(botToken);
    }


    this.discordAPI.addEventListener(new MusicListener());
    this.discordAPI.addEventListener(new CertificationListener());
    this.discordAPI.getPresence().setActivity(Activity.playing("!도움말 명령어로 기능 확인"));
}
```
## 커멘드 prefix 변경방법
Luffia.java에서 this.commandManager = new CommandManager("!", this); 의 "!" 부분을 원하는 prefix로 변경하면 됩니다.
- kr/kro/backas/Luffia.java
```java
public Luffia(JDA discordAPI) throws IOException, InterruptedException {
    this.discordAPI = discordAPI;

    /*
     * 커멘드 prefix를 ! 에서 && 으로 변경.
     * ex) !인증정보 >> &&인증정보
     */ 
    this.commandManager = new CommandManager("&&", this);

    this.commandManager.registerSlashCommand(new SlashCertificationCommand());

    this.commandManager.registerCommand("인증정보", new CertificationInfoCommand());
    this.commandManager.registerCommand("인증해제", new CertificationRemoveCommand());
    this.commandManager.registerCommand("도움말", new HelpCommand());
    this.commandManager.registerCommand("강제인증", new ForceCertificationCommand());

    this.commandManager.registerCommand("재생", new PlayCommand());
    this.commandManager.registerCommand("나가기", new QuitCommand());
    this.commandManager.registerCommand("스킵", new SkipCommand());
    this.commandManager.registerCommand("일시정지", new PauseCommand());
    this.commandManager.registerCommand("일시정지해제", new ResumeCommand());
    //this.commandManager.registerCommand("전체반복", new RepeatAllCommand());
    //this.commandManager.registerCommand("반복", new RepeatCurrentCommand());
    //this.commandManager.registerCommand("반복해제", new NoRepeatCommand());
    this.commandManager.registerCommand("대기열", new QueueCommand());

    this.commandManager.registerCommand("롤정보", new LOLUserInfoCommand());

    this.commandManager.registerCommand("메이플정보", new MapleUserInfoCommand());

    this.certificationManager = new CertificationManager(discordAPI);

    this.musicPlayerController = new MusicPlayerController();
    discordAPI.addEventListener(this.musicPlayerController);
    for (String botToken : BotSecret.MUSIC_BOT_TOKENS) {
        this.musicPlayerController.register(botToken);
    }


    this.discordAPI.addEventListener(new MusicListener());
    this.discordAPI.addEventListener(new CertificationListener());
    this.discordAPI.getPresence().setActivity(Activity.playing("!도움말 명령어로 기능 확인"));
}
```
