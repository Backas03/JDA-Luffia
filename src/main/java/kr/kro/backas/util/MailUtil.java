package kr.kro.backas.util;

import kr.kro.backas.secret.BotSecret;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

public class MailUtil {

    public static final String SMTP_HOST = "smtp.gmail.com";
    public static final int SMTP_PORT = 465;  // TLS : 587, SSL : 465

    public static void sendCertificationMessage(String email, int code) throws Exception {
        Properties props = System.getProperties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", SMTP_PORT);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.ssl.trust", SMTP_HOST);

        Session session = Session.getInstance(props,
                new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(BotSecret.EMAIL, BotSecret.APP_PASSWORD);
                    }
                });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(BotSecret.EMAIL));

        // 받는 이메일
        message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(email)
        );
        // 제목
        message.setSubject("대구대학교 게임서버 전공 대학생 인증 메일입니다.");

        String[] split = String.valueOf(code).split("");
        StringBuilder stringBuilder = new StringBuilder(split[0]);
        for (int i=1; i<split.length; i++) {
            stringBuilder.append(" ").append(split[i]);
        }

        message.setText("인증 코드는 " + stringBuilder + " 입니다\n\n!인증 " + code + "\n명령어를 디스코드 채팅방에 입력해주세요.");
        // 발송
        Transport.send(message);
    }
}
