package kr.kro.backas.certification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.util.FileUtil;
import kr.kro.backas.util.MailUtil;
import kr.kro.backas.util.MemberUtil;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
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
        if (this.role == null && !SharedConstant.ON_DEV) throw new IOException("역할을 찾을 수 없습니다.");
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

    public boolean isValidEmail(String email) {
        return email.matches("^[0-9a-zA-Z]([-_.]?[0-9a-zA-Z])*@daegu\\.ac\\.kr");
    }

    public void requestCertification(String email, SlashCommandInteractionEvent event, User user) {

        if (!isValidEmail(email)) {
            event.replyEmbeds(new EmbedBuilder()
                    .setAuthor(MemberUtil.getName(user), user.getAvatarUrl())
                    .setColor(Color.decode("#ff7547"))
                    .setTitle("명령어가 올바르지 않습니다. \"/" + event.getName() + "\"")
                    .setDescription("이메일 형식을 확인해주세요.\n"+
                            "ex) ``/인증 " + email + "``")
                    .addField(
                            "/인증 [대구대학교 이메일]",
                            "```해당 대구대학교 이메일로 인증 코드를 받습니다\n" +
                                    "ex) /인증 abc123@daegu.ac.kr```", false)
                    .setFooter(SharedConstant.RELEASE_VERSION)
                    .build()
            ).queue();
            return;
        }

        // 대구대 역할이 있으면 인증이 되어있는 상태 (인증 데이터에는 없을 수 있음)
        Member member = Main.getLuffia()
                .getPublishedGuild()
                .getMember(user);
        if (member != null && member.getRoles().contains(this.role)) {
            event.reply(user.getName() + " 님은 이미 인증이 완료된 상태입니다.").queue();
            return;
        }

        if (certificationData.isCertificated(user)) {
            event.reply(user.getName() + " 님은 이미 인증이 완료된 상태입니다.").queue();
            return;
        }
        if (certificationData.isCertificated(email)) {
            event.reply("해당 이메일은 인증된 이메일 입니다.").queue();
            return;
        }
        if (emails.containsValue(email) && !email.equals(emails.get(user.getIdLong()))) {
            event.reply("현재 이 메일은 인증이 진행중인 상태입니다. 다른 이메일로 다시 시도해주세요.").queue();
            return;
        }
        if (!failed.contains(user.getIdLong()) && isCertificating(user.getIdLong())) {
            if (!codes.containsKey(email)) event.reply("인증이 진행중입니다. 잠시만 기다려주세요.").queue();
            else {
                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("이메일 인증이 진행중입니다")
                        .setColor(Color.decode("#ff9d00"))
                        .setDescription("""
                                        이메일을 받지 못하였거나 재발급을 원하시면
                                        아래 명령어를 다시 한번 쳐주세요
                                        
                                        """ +
                                "/인증 [대구대학교 이메일]");
                event.replyEmbeds(builder.build()).queue();
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
        InteractionHook reply = event.reply("메일을 발송중에 있습니다. 잠시만 기다려주세요.").complete();
        int finalCode = code;
        CompletableFuture.supplyAsync(() -> {
            try {
                MailUtil.sendCertificationMessage(email, finalCode); // 순서 중요
            } catch (Exception e) {
                StackTraceUtil.replyConvertedError("인증 메일을 보내는 도중 에러가 발생했습니다.", event, e);
            }
            return null;
        }).thenApply(a -> {
            sendCertification(reply, user);
            codes.put(email, finalCode);
            return null;
        });
    }

    private void sendCertification(InteractionHook hook, User user) {
        String author = user.getGlobalName() != null ?
                user.getGlobalName() :
                String.format("%#s", user);
        String url = user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getDefaultAvatarUrl();
        EmbedBuilder builder = new EmbedBuilder()
                .setAuthor(author, null, url)
                .setTitle("대구대학교 인증 확인 (클릭)", "https://outlook.com/daegu.ac.kr")
                .setDescription("해당 메일로 인증 코드를 보내드렸습니다.\n아래 절차를 따라 학교 인증을 완료해주세요.")
                .addField("아래 명령어를 입력하여 학교 인증을 완료합니다.", "/인증 인증코드:[6자리 인증코드]\nex) /인증 인증코드:116126", false)
                .addField("아이디 또는 비밀번호를 잊어버리셨다면?", "[대구대학교 이메일 아이디/비밀번호 찾기](https://office.daegu.ac.kr/Case1/FindPwd.aspx)", false)
                .addField("계정 중복인증 방지를 위해 아래 사진과 같이 개인정보를 수집하고 있습니다.", "해당 개인정보는 대구대학교 재학생 인증일로부터 해당 디스코드 커뮤니티 퇴장일까지 보관되며, 재학생 인증 절차 완료시 해당 동의서에 동의하는 것으로 간주합니다.", false)
                .setFooter("대구대학교 이메일만 가지고 있으면 인증이 가능합니다.")
                .setImage("attachment://agreement.png")
                .setColor(Color.decode("#9047ff"));
        hook.editOriginalEmbeds(builder.build())
                .setFiles(
                        FileUpload.fromData(
                                Main.class.getResourceAsStream("/image/agreement.png"),
                                "agreement.png")
                ).queue();

    }


    public void responseCertification(IReplyCallback callback, User user, int code) {
        if (!isCertificating(user.getIdLong())) {
            callback.reply("!인증 명령어를 사용하여 인증 절차를 먼저 시작해주세요.").queue();
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
                callback.replyEmbeds(builder.build()).queue();
                return;
            }

            // 역할 지급
            Member member = callback.getMember();
            if (member == null) {
                member = Main.getLuffia()
                                .getPublishedGuild()
                                .retrieveMemberById(user.getIdLong())
                                .complete();
            }
            if (member == null) {
                callback.reply("member 데이터를 찾을 수 없습니다. 관리자에게 문의해주세요.").queue();
                return;
            }
            if (!member.getRoles().contains(role)) {
                callback.getGuild().addRoleToMember(user, role).queue();
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
                                    new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS")
                                            .format(Date.from(Instant.now(Clock.system(ZoneId.of(SharedConstant.ZONE_ID))))),
                                    info.knownAs(),
                                    info.univCheck()),
                            false)
                    .setColor(Color.decode("#0ee111"))
                    .setFooter("이메일이 담긴 인증 명령어를 삭제하셔도 무관합니다.");
            callback.replyEmbeds(builder.build()).queue();

        } catch (Exception e) {
            StackTraceUtil.replyError("인증 절차를 완료하는 중 에러가 발생했습니다.", callback, e);
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

    public void giveRole(Member member) {
        // 역할 지급
        if (!member.getRoles().contains(role)) {
            Main.getLuffia().getPublishedGuild().addRoleToMember(member.getUser(), role).queue();
        }
    }

    public void removeCertification(long userId) throws IOException {
        certificationData.getData().remove(userId);
        Guild guild =  Main.getLuffia().getPublishedGuild();
        Member member = guild.getMemberById(userId);
        if (member != null) {
            guild.removeRoleFromMember(member, role).queue();
        }
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
