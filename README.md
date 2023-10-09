# JDA-Luffia
Provide various features with Java Discord API (JDA)

## Information
이 봇은 Discord bagkaseu(박카스#9970) 에 의해 제작되었습니다.

* 해당 봇의 오류나 건의, 문의사항이 있을시 제작자에게 문의 또는 Issue 바랍니다.
[Issue 등록하기](https://github.com/Backas03/JLuffia/issues)

* Luffia 봇은 MIT licence 를 따르고 있습니다.
[Licence 확인](https://github.com/Backas03/JLuffia/blob/master/LICENSE)
* Luffia 봇 개발에 기여를 해보세요! 당신의 만든 서비스가 Luffia에 적용될수도?
[Pull Request](https://github.com/Backas03/JLuffia/pulls)
  * 모든 Pull Request(PR) 코드에는 시작과 끝 부분에 아래 형식과 같이 표기하여야 합니다.  아래는 예시입니다.
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

