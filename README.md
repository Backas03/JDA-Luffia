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
  ㄴ 가사 표시를 설정합니다. 실시간 가사는 항상 켜져 있어 곡이 시작되면 재생을 요청한 채널에 타임스탬프 가사를 이전/현재/다음 줄로 자동 갱신합니다. 실시간(기본): 이 채널로 표시 채널을 바꾸거나 끈 것을 다시 켬, 전체: 가사 전문, 끄기: 지금 곡의 표시만 중단(다음 곡부터 다시 자동 표시). 곡이 끝나면 그 곡의 가사 메시지는 삭제되고, 가사를 찾지 못한 곡은 아무것도 표시하지 않습니다. 오프셋(ms)으로 싱크를 조정합니다. (가사 출처: LRCLIB)
  ㄴ 유튜브 영상처럼 제목이 자유 형식이라 제목 정제만으로 가사를 못 찾으면, AI 서버(LLM)에 영상 제목과 채널명을 주고 곡명과 아티스트를 받아 다시 찾습니다 (예: ``「곡명」（アニメ「작품명」オープニング映像）``, ``【歌ってみた】곡명 / 아티스트``). 곡명이 정확히 같고 아티스트가 AI 가 답한 이름이거나 영상 제목·채널명에 들어 있는 이름일 때만 같은 곡으로 봅니다. 이렇게 찾은 곡의 길이가 영상과 5초 넘게 다르면(TV 사이즈, 인트로가 붙은 MV 등) 타임스탬프가 맞지 않을 수 있어 실시간 가사 대신 전체 가사로 표시합니다. 스포티파이 곡과, AI 서버가 LLM 이 아닐 때는 이 단계를 건너뜁니다. 제목 정제로 찾는 것과 AI 로 찾는 것을 동시에 진행하고, AI 쪽이 같은 곡을 먼저 확인하면 그 결과를 바로 씁니다.
  ㄴ 가사 조회는 번역과 따로 미리 해 둡니다. 곡이 대기열에 들어오거나 순서가 바뀌면 대기열 앞 20곡(CPU 예비 서버만 남았으면 3곡)의 가사를 한 번에 3곡씩 먼저 찾아 두므로, 다음 곡이 시작될 때는 이미 찾은 가사를 바로 씁니다. 찾지 못한 곡은 10분 동안 다시 찾지 않습니다.
/볼륨 (퍼센트)
  ㄴ 노래 봇의 볼륨을 변경합니다. (0 ~ 200, 기본 10) 값을 비우면 현재 볼륨을 보여줍니다.
/나가기
  ㄴ 노래 대기열과 설정들을 모두 초기화하고 음성채팅방에서 퇴장시킵니다.
/대기열
  ㄴ 현재 재생중인 곡, 재생 설정, 대기열, 다음 추천을 한 창에서 확인합니다. 대기열은 앞 5곡, 다음 추천은 2곡까지 썸네일과 함께 보여 주고 나머지는 "외 N곡" 으로 접습니다.
/일시정지
  ㄴ 재생 중인 노래를 멈추거나 다시 재생합니다.
/스킵 (곡수)
  ㄴ 재생 중인 노래를 건너뜁니다. 곡수를 주면 현재 곡 포함 그만큼 건너뜁니다.
/셔플
  ㄴ 대기열의 곡 순서를 무작위로 섞습니다. 현재 재생 중인 곡은 그대로 유지됩니다.
/ai <요청>
  ㄴ 자연어로 음악 봇을 조작합니다. AI 가 요청을 읽고 필요한 기능을 골라 차례로 실행합니다. 재생 중인 곡이 없어도 사용할 수 있습니다. (AI 서버: TRANSLATOR_URL 의 LLM, 가사 번역과 같은 서버)
  ㄴ 할 수 있는 일: 대기열 확인, 조건에 맞는 곡 제거 / 조건에 맞는 곡만 남기기 / 번호로 곡 빼기, 조건에 맞는 곡 앞으로 모으기, 셔플(무작위 / 비슷한 곡끼리), 조건에 맞는 곡 골라 재생(최대 50곡), 나라별 실시간 인기 차트 재생·조회(Apple Music), 특정 곡 추가, 연속 추천 켜기/끄기, 스킵, 볼륨, 반복 모드, 일시정지, 재생 속도
  ㄴ 예) 요즘 유행하는 jpop 틀어줘 / 보카로곡 20곡 선정해서 틀어봐 / 대기열에서 한국 노래 다 빼줘 / 일본 노래만 남겨줘 / 3번이랑 5번 곡 빼줘 / 지금 일본 1위 곡이 뭐야? / 잔잔한 곡 10곡 틀고 그런 느낌으로 계속 추천해줘 / 볼륨 30으로 하고 두 곡 넘겨줘
  ㄴ 곡을 추가할 때 "다음 곡으로", "이 노래 끝나면 바로", "먼저" 처럼 말하면 대기열 맨 앞에 넣어 지금 곡 바로 다음에 재생합니다 (예: 다음 노래로 YOASOBI 아이돌 틀어줘 / 보카로 3곡 먼저 틀어줘). 그런 말이 없으면 대기열 끝에 추가하고, 답변에서 어디에 넣었는지 알려줍니다. "다음 노래를 … 로 바꿔줘" 도 그 곡을 다음 곡으로 넣는 요청으로 처리하며, 원래 다음이던 곡은 지우지 않고 한 칸 뒤로 밀립니다.
  ㄴ 조건으로 여러 곡을 지우거나 일부만 남기는 작업은 요청 문장에 지우거나 남기라는 표현(빼줘, 지워줘, 삭제, 제거, 비워줘, 남겨줘 등)이 있을 때만 실행합니다. AI 가 요청을 잘못 이해해 삭제를 시도해도 이 표현이 없으면 실행되지 않습니다.
  ㄴ 대기열은 둘입니다. 사용자가 넣은 곡의 대기열과 연속 추천이 미리 골라 둔 추천 대기열(최대 2곡)이 따로 있고, 사용자 대기열이 비었을 때만 추천 대기열에서 이어서 재생하므로 추천곡이 사용자 곡을 밀어내지 않습니다. /ai 의 대기열 조작은 사용자 대기열이 기본이고, 추천곡이나 추천 대기열을 말했을 때만 추천 대기열을 수정합니다 (예: 추천곡 중에 한국 노래 빼줘 / 추천 대기열 비워줘).
  ㄴ 연속 추천은 기본으로 켜져 있습니다 (AI 서버가 살아 있을 때만). 사용자 대기열이 비면 지금까지 들은 곡과 사용자가 고른 곡을 기준으로 2곡을 미리 골라 추천 대기열에 넣고, 사용자가 곡을 넣으면 추천 대기열을 비우고 새 곡 기준으로 다시 고릅니다. "추천 그만" 으로 끄면 그 세션 동안 꺼지고 봇이 음성채팅방을 나가면 다시 켜집니다.
  ㄴ 5곡 넘게 지울 때는 지울 곡 수를 보여주고 [제거] [취소] 버튼으로 확인합니다. 요청한 사람만 누를 수 있고 2분이 지나면 만료됩니다.
  ㄴ 조건으로 곡을 고를 때(일본 노래만 남겨줘, 케이팝 빼줘 등)는 곡마다 장르·언어 태그(AI 셔플과 같은 태그)를 먼저 붙이고 그 태그와 어긋나지 않게 판정합니다. 한 곡은 한 음악 씬에만 속하는 것으로 보므로, 멤버가 일본인이거나 일본에서 음원을 등록한 K-pop 그룹의 곡(XG, TWICE 일본 발매곡 등)은 일본 노래가 아니라 케이팝으로 판정합니다. 태그가 없는 곡이 많은 대기열은 처음 한 번 태그를 붙이느라 더 오래 걸립니다.
  ㄴ 처리 중에는 10초마다 진행률과 경과 시간을 갱신해 작업 중임을 알립니다. GPU 표시이름과 token/s 는 ``/ai관리 디버그 켜기`` 를 한 동안에만 함께 표시됩니다 (``/ai셔플`` 도 같습니다).
  ㄴ 곡 정보: "지금 나오는 노래 세부정보 알려줘", "이 노래 누가 만들었어?" 처럼 물으면 발매일·앨범·장르(iTunes 검색), 곡이나 아티스트의 위키백과 문서(일본어·한국어·영어), 가사를 찾아보고 누가 만들었는지, 언제 나왔는지, 어떤 내용의 곡인지, 배경 이야기를 자세히 답합니다. 찾은 자료에 없는 내용은 지어내지 않으므로 위키백과에 문서가 없는 곡은 답이 짧아집니다. 가사는 내용을 요약하는 데만 쓰고 그대로 옮기지 않습니다.
  ㄴ 할 수 없는 요청은 할 수 없다고 답합니다. AI 는 슬래시 명령어로 할 수 있는 일과 곡 정보 안내만 할 수 있습니다.
  ㄴ 사용 제한: 사용자별 20초에 1번, 서버별 시간당 60번. 한 요청에서 추가하는 곡은 최대 50곡이고 대기열은 200곡을 넘지 않게 합니다.
/ai관리 <켜기|끄기|상태|디버그 켜기|디버그 끄기> (봇 제작자 전용)
  ㄴ AI 기능(/ai, /ai셔플, 연속 추천)을 즉시 켜거나 끕니다. 가사 번역은 영향을 받지 않습니다. 재시작하면 다시 켜집니다.
  ㄴ 디버그 켜기: 가사 하단에 번역 상태 줄(GPU 표시이름, token/s, 진행률)을 표시합니다. 기본은 꺼짐이고 재시작하면 다시 꺼집니다.
/ai셔플
  ㄴ AI 가 대기열 곡의 장르, 언어, 분위기를 파악해 비슷한 곡끼리 이어지도록 섞습니다. 묶음 순서와 묶음 안 순서는 무작위이고, 같은 가수가 연달아 나오지 않게 조정합니다.
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

    // 가사 한국어 번역 서버 주소 (예: "http://127.0.0.1:8765"). 쉼표로 여러 개 적으면 앞에서부터 시도하고 안 되면 다음으로 넘어갑니다. 아래 "가사 번역 설정" 절 참고. 비워두면 번역만 비활성화됩니다.
    public static final String TRANSLATOR_URL = "";
}
```
### 2. SharedConstant.java 에서 서비스 서버를 설정합니다 </br>
음악 명령어는 봇이 초대된 모든 서버에서 동작합니다. 특정 서버에서만 받으려면 ``SERVICE_GUILD_IDS`` 에 서버 id 를 넣습니다 (비어 있으면 전체 허용). ``MAIN_GUILD_ID`` 는 이메일 인증 역할 부여와 롤 모집방처럼 서버 하나에 묶인 기능에만 쓰입니다.
- kr/kro/backas/SharedConstant.java
```java
package kr.kro.backas;

public final class SharedConstant {
    public static final long MAIN_GUILD_ID = 791974345965961237L; // 인증/롤 기능을 쓸 서버 id

    public static final long DEV_GUILD_ID = 1121632283154202694L;
    public static final long PUBLISHED_GUILD_ID = MAIN_GUILD_ID;
    public static final Set<Long> SERVICE_GUILD_IDS = Set.of(); // 비어 있으면 모든 서버에서 명령어 허용

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

메인 봇 자체가 노래봇 역할을 하므로 별도 설정 없이 초대된 모든 서버에서 서버당 음성채팅방 하나씩 재생됩니다. 한 서버에서 여러 음성채팅방을 동시에 서비스하려면 봇 계정이 채널 수만큼 필요합니다. secret/BotSecret.java 파일의</br>
``public static final List<String> MUSIC_BOT_TOKENS`` 항목에 추가 봇 토큰을 넣고 그 봇들도 서버에 초대하면, 서버마다 초대된 봇 수만큼 음성채팅방을 동시에 쓸 수 있습니다. 로그인에 실패한 토큰은 경고만 남기고 건너뜁니다. </br>
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
`/가사` 표시에 한국어 번역을 붙이려면 번역 서버를 띄웁니다. 세 가지 중 하나를 고릅니다. A/B 는 GPU 가 필요 없고, C 는 GPU 머신에서 가장 빠릅니다.

**A. LLM (권장, 품질 좋음)**: llama.cpp + Tri-7B(트릴리온랩스, 한·영·일 특화) 4비트. RAM 약 6GB, 6코어 CPU 기준 5줄에 10초 안팎. 일본어·영어 가사를 자연스러운 한국어로 옮깁니다. 다른 GGUF 를 쓰려면 ``MODEL_URL``/``MODEL_FILE`` 환경변수로 바꿀 수 있습니다.
1. ``translator/llm/setup.sh`` 실행 (llama.cpp 바이너리 17MB + 모델 4.7GB 다운로드, 한 번만)
2. ``translator/llm/run.sh`` 로 실행 (기본 127.0.0.1:8765, ``TRANSLATOR_PORT`` 로 변경, ``LLM_THREADS`` 로 생성 스레드(기본 6, 물리 코어 수), ``LLM_THREADS_BATCH`` 로 프롬프트 처리 스레드(기본 12) 조정. ``nice`` 우선순위 10으로 실행되어 같은 머신의 다른 서버에 CPU 를 양보하며 ``LLM_NICE`` 로 조정. llama-server 의 호스트 메모리 프롬프트 캐시는 곡마다 프롬프트가 달라 쓸모가 없고 기본값 8GB 까지 계속 쌓이므로 ``--cache-ram 0`` 으로 꺼 두었으며 ``LLM_CACHE_RAM`` 으로 MiB 단위 조정 가능)
3. ``BotSecret.TRANSLATOR_URL`` 에 ``http://127.0.0.1:8765`` 를 넣고 봇 재시작

**GPU PC 를 같이 쓰는 경우**: 주소를 쉼표로 여러 개 적으면 GPU 서버들은 **동시에** 쓰이고, 목록의 마지막 주소는 CPU 예비 서버로 GPU 가 모두 꺼졌을 때만 씁니다. 예: ``"http://192.168.0.20:8765,http://127.0.0.1:8765"``. 꺼져 있거나 연결이 끊긴 서버는 30초 동안 건너뛰고, 번역 중에 끊기면 같은 요청을 다른 서버로 다시 보냅니다. 끊겼던 서버는 요청이 없어도 30초마다 다시 확인해, 살아나면 바로 다시 쓰고 속도를 측정한 적이 없으면 한 번 측정합니다. 재빌드 없이 바꾸려면 환경변수 ``TRANSLATOR_URL`` 또는 JVM 옵션 ``-Dtranslator.url=...`` 로 덮어쓸 수 있습니다. 서버를 적는 형식과 분배 방식은 아래 "여러 GPU 서버 동시 사용" 을 참고하세요.

GPU PC(Windows) 쪽은 llama.cpp 릴리스에서 ``llama-*-bin-win-cuda-*-x64.zip`` 과 같은 릴리스의 ``cudart-*.zip`` 을 받아 ``translator/llm/bin`` 에 풀고, 모델을 ``translator/llm/models`` 에 둔 뒤 ``translator/llm/run-gpu.bat`` 로 실행합니다. 이 스크립트는 ``--host 0.0.0.0`` 과 ``-ngl 99`` 로 띄우므로 Windows 방화벽에서 8765 포트 인바운드를 허용해야 하고, 공유기 포트포워딩은 하지 않습니다. 공유기에서 GPU PC 의 IP 를 고정해 두고 절전 모드를 끄세요.

**B. NLLB (가볍지만 가사 품질 낮음)**: Meta NLLB-200 을 CTranslate2 로 실행. Python 3.10 이상 필요.
1. ``translator/setup.sh`` 실행 (모델 다운로드와 int8 변환, 한 번만)
2. ``translator/run.sh`` 로 실행
3. ``BotSecret.TRANSLATOR_URL`` 에 같은 주소를 넣고 봇 재시작

**C. Ollama (GPU 권장, 가장 빠름)**: GPU 가 있는 머신에서 Ollama 로 실행. VRAM 16GB 기준 ``gemma4:12b`` 권장.
1. [Ollama 설치](https://ollama.com/download) 후 ``ollama pull gemma4:12b`` (약 7.6GB, 한 번만)
2. 환경변수 ``OLLAMA_CONTEXT_LENGTH=8192``, ``OLLAMA_KEEP_ALIVE=1h`` 를 설정하고 Ollama 재시작 (기본 컨텍스트가 짧아 긴 가사가 잘릴 수 있고, 유휴 시 모델이 내려가는 것을 방지)
3. ``BotSecret.TRANSLATOR_URL`` 에 ``http://127.0.0.1:11434`` 를 넣고 봇 재시작 (봇이 다른 머신에 있으면 Ollama 쪽에 ``OLLAMA_HOST=0.0.0.0`` 설정 후 해당 머신 주소 사용)

모델이 여러 개 설치되어 있으면 봇 쪽 환경변수 ``TRANSLATOR_MODEL`` 로 사용할 모델을 고정할 수 있습니다 (예: ``TRANSLATOR_MODEL=gemma4:12b``). 지정하지 않으면 서버가 알려주는 첫 번째 모델을 사용합니다.

#### 여러 GPU 서버 동시 사용
서버마다 ``주소|표시이름|모델명|자리수`` 를 쉼표로 이어 적습니다. 표시이름·모델명·자리수는 생략할 수 있습니다 (표시이름 기본값은 호스트 이름, 모델명을 생략하면 ``TRANSLATOR_MODEL`` 또는 서버의 첫 모델, 자리수 기본값 1). 서버마다 모델이 달라도 됩니다.
```
public static final String TRANSLATOR_URL = "http://192.168.0.2:11434|NVIDIA GeForce RTX 5080|luffia|4,http://192.168.0.30:11434|AMD Radeon RX 7800 XT|luffia|1,http://127.0.0.1:8765|AMD Ryzen 5 5600G";
```
- **자리수**: 그 서버에 동시에 넣을 요청 수 (1~8). Ollama 의 ``OLLAMA_NUM_PARALLEL`` 과 같은 값으로 맞춥니다. ``OLLAMA_NUM_PARALLEL`` 을 설정하지 않은 Ollama 는 요청을 한 번에 하나씩 처리하므로 1 로 둡니다. 자리수를 올리면 동시 요청마다 컨텍스트 메모리가 추가되므로 ``ollama ps`` 에서 ``100% GPU`` 로 표시되는지 확인하세요. 일부가 CPU 로 넘어가면 오히려 크게 느려집니다

  RTX 5080(16GB) + gemma4:12b(컨텍스트 8192) 측정값:

  | OLLAMA_NUM_PARALLEL | 모델 크기 | 동시 1개 | 동시 2개 | 동시 3개 | 동시 4개 |
  |---|---|---|---|---|---|
  | 1 | 8.1GB | 74 token/s | 합계 74 (나머지는 대기) | 합계 74 | 합계 74 |
  | 2 | 9.0GB | 80 | 각 75, 합계 142 | 대기 발생 | 대기 발생 |
  | 3 | 9.1GB | 80 | 각 76, 합계 146 | 각 71, 합계 약 200 | 대기 발생 |
  | 4 | 10GB | 74 | 각 68~76, 합계 약 140 | 각 75, 합계 약 210 | 각 70, 합계 약 260 |

  자리수 4 에서 12줄짜리 곡 6개 미리 번역: 자리수 1 일 때 44.9초 → 19.4초
- **CPU 예비 서버**: 주소가 2개 이상이면 마지막 주소가 예비입니다. 4번째 칸에 숫자 대신 ``fallback`` 을 적으면 그 서버가 예비가 되고, 나머지는 모두 GPU 로 취급합니다. 예비 서버는 GPU 가 하나라도 살아 있으면 쓰지 않습니다
- **분배 방식**: 봇이 켜질 때 GPU 마다 짧은 요청으로 혼자일 때의 속도와 자리수만큼 동시에 보냈을 때의 속도를 재고, 이후 요청이 끝날 때마다 "동시 요청 수별 token/s" 를 학습합니다. 새 요청은 "이 요청이 걸릴 시간 + 이미 돌고 있는 요청들이 느려지는 시간" 이 가장 작은 GPU 로 보냅니다. 예) 5080 에 1개가 돌고 있고 7800 XT 가 비어 있으면, 5080 에 겹쳐 둘 다 느려지는 것보다 7800 XT 로 보내는 쪽을 고릅니다
- **우선순위**: 현재 곡 가사 번역과 ``/ai``·``/ai셔플`` 은 사용자 앞 작업, 다음 곡 미리 번역과 연속 추천 선별은 백그라운드 작업입니다. 백그라운드는 사용자 앞 작업을 30% 넘게 느리게 만드는 자리에는 들어가지 않고 기다립니다. 자리가 모두 백그라운드로 차 있을 때 사용자 앞 작업이 오면 백그라운드 하나를 중단시키고 자리를 넘기며, 중단된 번역은 이어서 다시 진행됩니다
- ``/ai셔플``, ``/ai`` 의 곡 분류는 대기열 앞에서부터 GPU 당 300곡까지 분석합니다 (GPU 2대면 600곡, 넘는 곡은 뒤에 무작위로 둠). 20곡씩 묶어 살아 있는 GPU 들에 묶음을 번갈아 똑같이 나눠 주고, GPU 마다 자기 자리 수만큼 동시에 처리합니다. 맡은 GPU 가 도중에 꺼지면 그 묶음은 다른 서버로 넘깁니다. 미리 번역은 대기열 곡들을 전체 자리 수만큼 동시에 처리합니다
- ``/ai관리 상태`` 에서 서버별 연결 상태, 모델, 사용 중인 자리, 현재 속도, 동시 요청 수별 학습 속도를 볼 수 있습니다

GPU 서버(Ollama) 권장 설정: ``OLLAMA_HOST=0.0.0.0``, ``OLLAMA_NUM_PARALLEL=자리수``, ``OLLAMA_KEEP_ALIVE=-1`` (모델을 VRAM 에 계속 유지), ``OLLAMA_MAX_LOADED_MODELS=1`` (다른 모델이 올라와 VRAM 을 넘치는 것 방지) 후 Ollama 재시작. ``luffia`` 별칭은 아래 내용의 ``Modelfile`` 로 ``ollama create luffia -f Modelfile`` 을 실행해 만듭니다.
```
FROM gemma4:12b
PARAMETER num_ctx 8192
```

Linux GPU 서버에서 systemd 서비스 대신 tmux 로 띄우려면 ``sudo systemctl disable --now ollama`` 로 서비스를 끄고 ``bash translator/ollama/run.sh`` 를 실행합니다. ``ollama`` 라는 tmux 세션을 만들어 왼쪽에는 홈 디렉터리 셸, 오른쪽에는 ``ollama serve`` 를 띄우고 바로 붙으며, 이미 떠 있으면 붙기만 합니다 (``Ctrl+B`` 다음 ``D`` 로 빠져나옴). 위 권장 설정을 기본값으로 넣고 (``OLLAMA_HOST=0.0.0.0:11434``, ``OLLAMA_CONTEXT_LENGTH=8192``, ``OLLAMA_KEEP_ALIVE=-1``, ``OLLAMA_NUM_PARALLEL=1``, ``OLLAMA_MAX_LOADED_MODELS=1``), 같은 이름의 환경변수로 값을 바꿀 수 있습니다 (예: ``OLLAMA_NUM_PARALLEL=4 bash translator/ollama/run.sh``). 서비스로 받아 둔 모델은 ``ollama`` 계정 아래에 있어 보이지 않으므로 띄운 뒤 ``ollama pull`` 을 한 번 더 해야 합니다. 끌 때는 ``bash translator/ollama/run.sh stop``, 재부팅 후 자동 실행은 ``crontab -e`` 에 ``@reboot bash /경로/translator/ollama/run.sh`` 를 추가합니다.

**CPU 폴백 서버 자동 관리**: 봇 서버에서 환경변수 ``TRANSLATOR_FALLBACK_START`` 에 CPU llama-server 실행 명령(예: ``/home/유저/JDA-Luffia/translator/llm/run.sh``)을 넣어두면, 봇이 30초마다 GPU 서버들을 확인해 하나라도 살아 있으면 CPU 서버를 꺼서 코어를 돌려주고, GPU 가 모두 죽으면 CPU 서버를 자동으로 띄웁니다. 봇이 직접 띄우지 않은 기존 CPU 서버까지 끄려면 ``TRANSLATOR_FALLBACK_STOP`` 에 종료 명령(예: ``pkill -f llama-server``)을 추가로 지정합니다. 환경변수가 없으면 이 기능은 꺼져 있습니다. GPU 장애 시 CPU 서버가 모델을 로드하는 동안(수십 초)은 번역이 잠시 실패할 수 있고, 로드가 끝나면 자동으로 이어집니다.

가사 하단에는 ``곡 제목 · 표시이름 | N token/s | 진행% (완료곡/전체곡)`` 형태로 현재 번역 상태가 표시됩니다. GPU 여러 대가 함께 일하고 있으면 GPU 마다 ``표시이름 | N token/s`` 를 한 줄씩 보여 주고, 진행률은 마지막 줄 끝에 붙습니다. 진행률은 현재 곡과 미리 번역해 두는 대기열 곡들을 곡당 같은 비중으로 합산한 값으로, 곡 안에서는 번역된 줄 수만큼 소수점으로 올라갑니다. 미리 번역은 GPU 가 하나라도 살아 있으면 대기열 앞 20곡, CPU 예비 서버만 남았으면 3곡까지 해 둡니다.

봇은 주소에 접속해 서버 종류(LLM/NLLB)를 자동으로 구분합니다. 번역은 곡 전체를 한 요청으로 보내 스트리밍으로 받으며 줄이 도착하는 대로 표시합니다. 한국어 가사는 번역하지 않습니다.

실시간 가사 하단에는 곡 제목이, 그 아래 줄에 ``현재 위치 — 전체 길이`` (예: ``0:12 — 3:33``) 가 표시됩니다. 재생 시간은 가사 줄이 바뀌어 메시지를 고칠 때 함께 갱신되고, 그 사이에는 1초마다 시간만 갱신합니다. 디스코드의 채널당 메시지 수정 한도(약 5초에 5회) 때문에 매초 갱신과 가사 줄 전환을 모두 할 수는 없어서, 곧 나올 가사 줄들의 시각을 보고 그 줄들을 제때 표시하는 데 필요한 몫만 남긴 뒤 나머지 한도를 전부 시간 갱신에 씁니다. 그래서 간주처럼 가사가 없는 구간에서는 거의 매초 올라가고(60초에 2초 이하로 건너뜀), 가사가 4초마다 바뀌는 구간에서는 8초에 1초꼴로, 가사가 촘촘할수록 더 자주 건너뜁니다 (가사 줄은 항상 0.2초 안에 표시). 수정 한도는 디스코드와 같은 5초 창으로 계산합니다. 수정 요청이 2.5초 안에 처리되지 않으면 그 요청은 버리고 최신 내용으로 다시 보내며, 응답이 4초 넘게 없어도 다음 수정을 막지 않습니다. 이렇게 수정이 밀리는 것이 감지되면 그 채널의 시간 전용 갱신을 30초 동안 쉬고 가사 줄 갱신만 계속합니다 (로그에 ``lyrics message edits in channel ... are being held back`` 경고가 남습니다). 위의 번역 상태 줄은 ``/ai관리 디버그 켜기`` 를 한 동안에만 표시됩니다.

타임스탬프 가사가 없어 전체 가사로 표시하는 곡은 가사 줄이 바뀌는 시점이 없으므로, 푸터에 ``현재 위치 — 전체 길이`` 를 넣고 1초마다 갱신합니다 (일시정지 중에는 갱신하지 않습니다). 채널당 메시지 수정 한도(5초에 5회)를 거의 다 쓰기 때문에, 같은 채널에서 다른 메시지 수정이 겹치면 시간 갱신이 한두 번 건너뛸 수 있습니다. 수정이 밀릴 때의 처리(요청 버리기, 시간 갱신 30초 쉬기)는 실시간 가사와 같습니다. ``/가사`` 에서 전체 가사를 직접 고른 경우에는 시간을 표시하지 않습니다.

번역하는 곡에서는 화면이 흔들리지 않도록 번역 줄을 항상 채웁니다. 간주 구간은 ``♪``, 번역 중인 줄은 ``번역 중...``, 번역에 실패한 줄은 ``-`` 로 표시합니다. 실패한 줄은 그 곡을 다시 재생할 때 한 번 더 번역을 시도합니다.

영어 추임새·의성어·이모티콘만 있는 줄(``Oh, woo yeah``, ``Mwah!``, ``La la la``, ``:-D`` 등)은 한글로 옮기지 않고 번역 줄에도 영어 그대로 보여 줍니다. 처음 번역에서 영어 그대로 돌아온 줄만 모아 LLM 에 "소리뿐인 줄인지, 뜻이 있는 줄인지" 를 물어 보고, 소리뿐인 줄은 그대로 두고 뜻이 있는 줄은 다시 번역시킵니다(같은 줄은 한 번만 보냅니다). 그래도 한글 없이 돌아온 영어 줄은 영어 그대로 표시합니다. 뜻이 있는 영어 문장 속 추임새는 영어로 남깁니다(``Oh baby, 지금 떠나지 마``). 원문에서 같은 말이 반복되는 횟수(``はい はい はい``, ``ああああ``)와 번역의 반복 횟수가 다르면 원문 횟수에 맞춥니다. 한 줄에 여러 언어로 같은 말이 들어 있으면(``Merci 고마워 Thank you``) 부분마다 번역해 같은 횟수로 표시하고(``고마워 고마워 고마워``), 번역이 같은 말의 반복으로 나왔는데 횟수가 원문의 쉼표로 나뉜 부분 수나 문자 종류가 바뀌는 구간 수와 다르면 그 수에 맞춥니다. 번역 규칙이 바뀌어 이전 버전으로 저장된 번역 캐시(``cache/translations``)는 쓰지 않고 다시 번역합니다.

#### 디스크 캐시
봇 실행 폴더 아래 ``cache/`` 에 결과를 저장해 재시작 후에도 바로 씁니다. 위치는 JVM 옵션 ``-Dluffia.cache.dir=경로`` 로 바꿀 수 있고, 폴더를 지우면 캐시가 초기화됩니다.
- ``cache/translations/`` : 가사 번역 (원문 가사의 SHA-256 기준, 번역한 모델 이름 포함). 곡 번역이 끝나거나 중단될 때 저장
- ``cache/lyrics/`` : LRCLIB 가사 조회 결과
- ``cache/ai-classify.json``, ``cache/ai-tags.json`` : ``/ai`` 곡 분류와 ``/ai셔플`` 곡 태그 (각 2만 개까지). 바뀐 것이 있을 때 1분마다, 그리고 봇 종료 시 저장
- ``cache/llm/speeds.json`` : 번역 서버별로 학습한 "동시 요청 수별 token/s" (서버 주소와 모델 이름 기준). 바뀐 것이 있을 때 30초마다 저장. 봇이 켜지면 이 값으로 바로 분배를 시작하고, 서버가 연결될 때마다 다시 측정한 값을 섞어 보정합니다. 저장된 모델과 설정한 모델명이 다르면 쓰지 않습니다
타임스탬프가 없어 전체 가사로 표시되는 곡은 원문을 먼저 띄운 뒤 번역이 끝나면 같은 메시지를 수정해 각 줄 아래에 번역을 끼워 넣습니다. (임베드 글자 제한을 넘는 뒷부분은 생략 표시)</br>
</br>
**본 데모 영상은 6코어 CPU로 LLM 모델을 돌리는 영상으로, GPU 사용 시 더 나은 퍼포먼스를 기대할 수 있습니다**
</br>


https://github.com/user-attachments/assets/7bd6bd92-021b-4578-8bd5-e2e310b9ec6b

</br>


https://github.com/user-attachments/assets/5cb94339-9e2c-490b-86a9-42d63d0af4d4


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
