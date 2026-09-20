package kr.kro.backas.command.music.slash;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.MusicSelection;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.List;

public class QueueSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "대기열";
    private static final int MAX_QUEUE_ROWS = 5;

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription());
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열 정보를 확인할 수 없습니다.",
                    "대기열을 확인하려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        if (client == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "해당 채널에서 음악을 재생중인 노래 봇이 없습니다", null).build()).queue();
            return;
        }
        AudioTrack currentPlaying = client.getCurrentPlaying();
        if (currentPlaying == null) {
            event.replyEmbeds(MusicEmbeds.error(member, "현재 재생 대기 목록이 비어있습니다.", null)
                    .addField("노래 봇", MusicEmbeds.botName(client.getMusicBot()), false)
                    .build()).queue();
            return;
        }
        AudioTrackInfo currentPlayingInfo = currentPlaying.getInfo();
        MusicSelection currentMusicSelection = currentPlaying.getUserData(MusicSelection.class);
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(MusicEmbeds.PRIMARY)
                .setAuthor(currentPlayingInfo.author)
                .setTitle(currentPlayingInfo.title, currentPlayingInfo.uri)
                .setThumbnail(MusicEmbeds.thumbnailOf(currentPlaying))
                .setFooter(currentMusicSelection != null
                        ? MemberUtil.getName(currentMusicSelection.getRequestedMember())
                        : SharedConstant.RELEASE_VERSION)
                .addField(
                        "재생 시간",
                        DurationUtil.formatDurationColon((int) (client.getRealPositionMs() / 1000))
                                + " / "
                                + DurationUtil.formatDurationColon((int) (currentPlayingInfo.length / 1000)),
                        true
                ).addField("출처", MusicEmbeds.sourceLabel(currentPlaying), true)
                .addField("노래 봇", MusicEmbeds.botName(client.getMusicBot()), true)
                .addField("반복 모드", client.getRepeatModeName(), true)
                .addField("재생 속도", client.getCurrentPlaySpeed() + "배속", true)
                .addField("볼륨", client.getVolume() + "%", true)
                .addField("노래방모드", client.getKaraokeMode().getName(), true)
                .addField("이퀄라이저", client.getCurrentEqualizer().getName(), true);
        List<AudioTrack> queue = client.getTrackQueue();
        if (!queue.isEmpty()) {
            builder.addField("", "아래는 대기열 목록입니다 (" + queue.size() + "곡)", false);
            int rows = Math.min(queue.size(), MAX_QUEUE_ROWS);
            for (int i = 0; i < rows; i++) {
                AudioTrack track = queue.get(i);
                MusicSelection selection = track.getUserData(MusicSelection.class);
                String requester = selection != null ? MemberUtil.getName(selection.getRequestedMember()) : "";
                builder.addField(
                        (i + 1) + ". " + track.getInfo().title,
                        requester + " - " + track.getInfo().uri,
                        false
                );
            }
            if (queue.size() > rows) {
                builder.addField("", "... 외 " + (queue.size() - rows) + "곡", false);
            }
        }
        event.replyEmbeds(builder.build()).queue();
    }

    @Override
    public String getDescription() {
        return "현재 노래 재생정보와 대기열 정보를 확인합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME;
    }
}
