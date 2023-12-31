package kr.kro.backas.command.certification.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;

public class SlashCertificationCommand implements SlashCommandSource {

    public static final String COMMAND_NAME = "인증";
    public static final String COMMAND_ARGUMENT_CODE = "인증코드";
    public static final String COMMAND_ARGUMENT_EMAIL = "대구대이메일";

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash("인증", getDescription())
                .addOption(OptionType.INTEGER,
                        COMMAND_ARGUMENT_CODE,
                        "이메일로 받은 6자리 인증 코드를 입력하여 인증을 마무리합니다",
                        false)
                .addOption(OptionType.STRING,
                        COMMAND_ARGUMENT_EMAIL,
                        "해당 이메일 주소로 인증코드를 받습니다",
                        false);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        User user = event.getUser();
        OptionMapping option = event.getOption(COMMAND_ARGUMENT_CODE);
        CertificationManager certificationManager = Main.getLuffia().getCertificationManager();
        if (option != null) {
            int code = option.getAsInt();
            certificationManager.responseCertification(event, user, code);
            return;
        }
        option = event.getOption(COMMAND_ARGUMENT_EMAIL);
        if (option != null) {
            certificationManager.requestCertification(option.getAsString(), event, user);
            return;
        }
        event.replyEmbeds(new EmbedBuilder()
                .setAuthor(MemberUtil.getName(user), user.getAvatarUrl())
                .setColor(Color.decode("#ff7547"))
                .setTitle("명령어가 올바르지 않습니다. \"/" + event.getName() + "\"")
                .setDescription("대구대학교 이메일(@daegu.ac.kr) 또는 인증 번호를 입력해 주시기 바랍니다")
                .addField(
                        "/인증 [대구대학교 이메일]",
                        "```해당 대구대학교 이메일로 인증 코드를 받습니다\n" +
                                "ex) /인증 abc123@daegu.ac.kr```", false)
                .addField(
                        "/인증 [6자리 인증코드]",
                        "```이메일로 전송받은 코드로 대학교 인증을 완료합니다\n" +
                                "ex) /인증 714256```", false)
                .setFooter(SharedConstant.RELEASE_VERSION)
                .build()
        ).queue();
    }

    @Override
    public String getDescription() {
        return "대구대학교 이메일로 재학생 인증을 진행합니다. \n" +
               "인증 완료로 학교 인증이 필요한 모든 이벤트에 참여하실 수 있습니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + "[인증코드]\n" +
               "/" + COMMAND_NAME + "[이메일]";
    }
}
