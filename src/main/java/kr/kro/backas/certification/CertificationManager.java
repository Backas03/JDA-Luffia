package kr.kro.backas.certification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import kr.kro.backas.util.FileUtil;
import kr.kro.backas.util.MailUtil;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CertificationManager {

    public static final Logger LOGGER = LoggerFactory.getLogger(CertificationManager.class);

    private final Map<Long, String> emails;
    private final Map<String, Integer> codes;
    private final Set<Long> failed;
    private CertificationData certificationData;
    private final Role role;

    public CertificationManager(JDA discordAPI) throws IOException {
        this.role = discordAPI.getRoleById(1120505896297050152L);
        if (this.role == null) throw new IOException("역할을 찾을 수 없습니다.");
        this.emails = new HashMap<>();
        this.codes = new HashMap<>();
        this.failed = new HashSet<>();
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            this.certificationData = mapper.readValue(
                    FileUtil.checkAndCreateFile(getFile()),
                    CertificationData.class);
        } catch (MismatchedInputException ignore) {
            this.certificationData = new CertificationData();
        }
    }

    public CertificationData getCertificationData() {
        return certificationData;
    }

    public File getFile() {
        return new File("data/certification.yaml");
    }

    public void requestCertification(String email, Message message, User user) {
        if (!email.matches("^[0-9a-zA-Z]([-_.]?[0-9a-zA-Z])*@daegu\\.ac\\.kr")) {
            message.reply("올바른 학교 이메일을 입력해주세요.").queue();
            return;
        }
        if (certificationData.isCertificated(user)) {
            message.reply(user.getName() + " 님은 이미 인증이 완료된 상태입니다.").queue();
            return;
        }
        if (certificationData.isCertificated(email)) {
            message.reply("해당 이메일은 인증된 이메일 입니다.").queue();
            return;
        }
        if (emails.containsValue(email) && !email.equals(emails.get(user.getIdLong()))) {
            message.reply("현재 이 메일은 인증이 진행중인 상태입니다. 다른 이메일로 다시 시도해주세요.").queue();
            return;
        }
        if (!failed.contains(user.getIdLong()) && isCertificating(user.getIdLong())) {
            if (!codes.containsKey(email)) message.reply("인증이 진행중입니다. 잠시만 기다려주세요.").queue();
            else {
                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("이메일 인증이 진행중입니다")
                        .setColor(Color.decode("#ff9d00"))
                        .setDescription("""
                                        이메일을 받지 못하였거나 재발급을 원하시면
                                        아래 명령어를 다시 한번 쳐주세요
                                        
                                        """ +
                                "!인증 [대구대학교 이메일]");
                message.replyEmbeds(builder.build()).queue();
                failed.add(user.getIdLong());
            }
            return;
        }
        emails.remove(user.getIdLong());
        codes.remove(email);
        failed.remove(user.getIdLong());

        // 100000 ~ 999999
        int code = 100000 + (int) (Math.random() * ((999999 - 100000) + 1));
        while (codes.containsValue(code)) {
            code = 100000 + (int) (Math.random() * ((999999 - 100000) + 1));
        }
        failed.remove(user.getIdLong());
        emails.put(user.getIdLong(), email);
        Message reply = message.reply("메일을 발송중에 있습니다. 잠시만 기다려주세요.").complete();
        int finalCode = code;
        CompletableFuture.supplyAsync(() -> {
            try {
                MailUtil.sendCertificationMessage(email, finalCode); // 순서 중요
            } catch (Exception e) {
                message.reply(
                        "인증 메일을 보내는 도중 에러가 발생했습니다.\n```cs\n"
                                + StackTraceUtil.convert(e)
                                + "```"
                ).queue();
            }
            return null;
        }).thenApply(a -> {
            sendCertification(email, reply, user);
            codes.put(email, finalCode);
            return null;
        });
    }

    private void sendCertification(String email, Message message, User user) {
        String author = user.getGlobalName() != null ?
                user.getGlobalName() :
                String.format("%#s", user);
        String url = user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getDefaultAvatarUrl();
        EmbedBuilder builder = new EmbedBuilder()
                .setAuthor(author, null, url)
                .setFooter("대구대학교 이메일만 가지고 있으면 인증이 가능합니다.")
                .setTitle("대구대학교 인증 확인 (클릭)", "https://outlook.com/daegu.ac.kr")
                .setDescription("해당 메일로 인증 코드를 보내드렸습니다.\n아래 절차를 따라 학교 인증을 완료해주세요.")
                .addField("아래 명령어를 입력하여 학교 인증을 완료합니다.", "!인증 [6자리 인증 코드]", false)
                .setColor(Color.decode("#9047ff"));
        message.editMessageEmbeds(builder.build()).queue();
    }


    public void responseCertification(Message message, User user, int code) {
        if (!isCertificating(user.getIdLong())) {
            message.reply("!인증 명령어를 사용하여 인증 절차를 먼저 시작해주세요.").queue();
            return;
        }
        Instant now = Instant.now();
        String email = emails.get(user.getIdLong());
        String knownAs = user.getGlobalName() != null ?
                user.getGlobalName() :
                String.format("%#s", user);
        int realCode = codes.get(email);
        try {
            if (realCode != code) {
                failed.add(user.getIdLong());
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#d90000"))
                        .setTitle("인증에 실패했습니다.")
                        .addField("인증 코드가 일치하지 않습니다.", "코드를 다시 입력하거나 재인증을 원하시면 !인증 명령어를 다시 입력해주세요.", false);
                message.replyEmbeds(builder.build()).queue();
                return;
            }

            // 역할 지급
            Member member = message.getMember();
            if (member != null && !member.getRoles().contains(role)) {
                message.getGuild().addRoleToMember(user, role).queue();
            }

            // 인증 기록
            CertificationInfo info = new CertificationInfo(email, now.toEpochMilli(), knownAs, false);
            certificate(user.getIdLong(), info); // TODO: 비동기 처리? finally 에서 thread unsafe에 유의

            emails.remove(user.getIdLong());
            codes.remove(email);
            failed.remove(user.getIdLong());

            EmbedBuilder builder = new EmbedBuilder()
                    .setTitle("인증이 완료되었습니다!")
                    .setDescription("이제부터 학교 인증이 필요한 모든 이벤트에 참여하실 수 있습니다.")
                    .addField("인증 정보",
                            String.format(
                                    "```email=%s\nuserId=%d\ndate=%s\nknownAs=%s\nunivCheck=%b```",
                                    "비공개"/*email*/,
                                    user.getIdLong(),
                                    new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS").format(Date.from(now)),
                                    info.knownAs(),
                                    info.univCheck()),
                            false)
                    .setColor(Color.decode("#0ee111"))
                    .setFooter("이메일이 담긴 인증 명령어를 삭제하셔도 무관합니다.");
            message.replyEmbeds(builder.build()).queue();

        } catch (Exception e) {
            message.reply(
                    "인증 절차를 완료하는 중 에러가 발생했습니다.\n```cs\n"
                            + StackTraceUtil.convert(e)
                            + "```"
            ).queue();
        }
    }

    public boolean isCertificating(long userId) {
        return emails.containsKey(userId);
    }

    public boolean isCertificating(String email) {
        return emails.containsValue(email);
    }

    public void certificate(long userId, CertificationInfo info) throws IOException {
        certificationData.getData().put(userId, info);
        save();
        LOGGER.info(
                "certification succeed. {userId={}, knownAs={}, date={}, univCheck={}}",
                userId,
                info.knownAs(),
                info.date(),
                info.univCheck()
        );
    }

    public void removeCertification(long userId) throws IOException {
        certificationData.getData().remove(userId);
        save();
        LOGGER.info(
                "certification data removed. {userId={}}",
                userId
        );
    }

    public void save() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.writeValue(
                FileUtil.checkAndCreateFile(getFile()),
                certificationData);
    }
}
