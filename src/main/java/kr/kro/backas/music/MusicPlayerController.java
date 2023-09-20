package kr.kro.backas.music;

import kr.kro.backas.SharedConstant;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MusicPlayerController extends ListenerAdapter {
    private final Set<MusicPlayerClient> clients;
    // key: memberId
    private final Map<Long, MusicLoader> searchData;

    public MusicPlayerController() {
        this.clients = new HashSet<>();
        this.searchData = new HashMap<>();
    }

    public void register(String botToken) throws InterruptedException {
        JDABuilder builder = JDABuilder
                .createDefault(botToken)
                .setChunkingFilter(ChunkingFilter.ALL) // enable member chunking for all guilds
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .enableCache(CacheFlag.ROLE_TAGS);
        MusicPlayerClient client = new MusicPlayerClient(builder.build().awaitReady());
        this.clients.add(client);
    }

    public void search(Identifier id, String query, Message message) {
        Member member = message.getMember(); // member cannot be null if bot services only guild channels.
        VoiceChannel joinedVoiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (joinedVoiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("음악을 검색할 수 없습니다.")
                    .setDescription("음악을 재생하려면 음성채팅방에 먼저 참여해주세요.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.getChannel()
                    .sendMessageEmbeds(builder.build())
                    .queue();
            return;
        }
        MusicPlayerClient client = findFromVoiceChannel(joinedVoiceChannel);
        if (client == null) {
            for (MusicPlayerClient registered : clients) {
                if (registered.getJoinedVoiceChannel() == null) {
                    client = registered;
                    break;
                }
            }
        }
        if (client == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("음악을 재생할 수 없습니다.")
                    .setDescription("현재 모든 노래봇이 음악을 재생중 입니다. 나중에 다시 시도해주세요.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.getChannel()
                    .sendMessageEmbeds(builder.build())
                    .queue();
            return;
        }
        MusicLoader loader = new MusicLoader(client, new MusicSearchQueryInfo(
                id,
                query,
                member,
                message
        ));
        loader.loadMusic();
        searchData.put(member.getIdLong(), loader);
    }

    public Set<MusicPlayerClient> getRegisteredClients() {
        return clients;
    }

    // the user select a track from search list. enqueue it.
    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if (event.getComponentId().equals(MusicLoader.PLAYLIST_STRING_SELECT_MENU_ID)) {
            // member cannot be null if bot services only guild channels.
            Member member = event.getMember();

            MusicLoader loader = searchData.get(member.getIdLong());
            if (loader == null) return;
            if (!loader.isTrackLoaded()) { // code may not reach here
                event.getMessage()
                        .getChannel()
                        .sendMessage("서버에서 요청하신 음악을 가져오고 있습니다. 잠시만 기다려주세요")
                        .queue();
                return;
            }
            try {
                // enqueue or play
                EmbedBuilder result = findClientAndEnqueue(new MusicSelection(
                        loader.getQueryInfo(),
                        loader.getLoadedTracks().get(event.getValues().get(0))
                ));
                event.editSelectMenu(event.getComponent().asDisabled()).queue();
                event.getMessage().replyEmbeds(result.build()).queue();
            } catch (MusicPlayerException e) {
                switch (e.getErrorType()) {
                    case NOT_IN_VOICE_CHANNEL -> {
                        EmbedBuilder builder = new EmbedBuilder()
                                .setColor(Color.decode("#f1554a"))
                                .setAuthor(MemberUtil.getName(member))
                                .setTitle("음악을 검색할 수 없습니다.")
                                .setDescription("음악을 재생하려면 음성채팅방에 먼저 참여해주세요.")
                                .setFooter(SharedConstant.RELEASE_VERSION);
                        event.getMessage()
                                .getChannel()
                                .sendMessageEmbeds(builder.build())
                                .queue();
                    }
                    case NO_AVAILABLE_CLIENTS_FOUND -> {
                        EmbedBuilder builder = new EmbedBuilder()
                                .setColor(Color.decode("#f1554a"))
                                .setAuthor(MemberUtil.getName(member))
                                .setTitle("음악을 재생할 수 없습니다.")
                                .setDescription("현재 모든 노래봇이 음악을 재생중 입니다. 나중에 다시 시도해주세요.")
                                .setFooter(SharedConstant.RELEASE_VERSION);
                        event.getMessage()
                                .getChannel()
                                .sendMessageEmbeds(builder.build())
                                .queue();
                    }
                }
            }
        }
    }

    // result message 를 build
    public EmbedBuilder findClientAndEnqueue(MusicSelection selection) throws MusicPlayerException {
        Member requestedMember = selection.getRequestedMember();
        AudioChannelUnion joinedAudioChannel = MemberUtil.getJoinedAudioChannel(requestedMember);
        if (joinedAudioChannel == null) {
            throw new MusicPlayerException(MusicPlayerException.Type.NOT_IN_VOICE_CHANNEL);
        }
        MusicPlayerClient client = findFromVoiceChannel(joinedAudioChannel.asVoiceChannel());
        if (client == null) {
            for (MusicPlayerClient registered : clients) {
                if (registered.getJoinedVoiceChannel() == null) {
                    client = registered;
                    break;
                }
            }
        }
        if (client == null) {
            throw new MusicPlayerException(MusicPlayerException.Type.NO_AVAILABLE_CLIENTS_FOUND);
        }
        searchData.remove(requestedMember.getIdLong());
        return client.enqueue(selection, joinedAudioChannel.asVoiceChannel());
    }

    public void expireSearchData(long memberId) {
        this.searchData.remove(memberId);
    }

    public void expireSearchData(Member member) {
        this.expireSearchData(member.getIdLong());
    }

    public @Nullable MusicPlayerClient findFromVoiceChannel(VoiceChannel channel) {
        for (MusicPlayerClient client : clients) {
            if (client.getJoinedVoiceChannel() == null) return client;
            if (client.getJoinedVoiceChannel().getId().equals(channel.getId())) {
                return client;
            }
        }
        return null;
    }

    public void shutdownGracefully() {
        for (MusicPlayerClient client : clients) {
            try {
                client.shutdownGracefully();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
