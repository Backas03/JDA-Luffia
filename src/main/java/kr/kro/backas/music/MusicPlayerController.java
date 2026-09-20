package kr.kro.backas.music;

import kr.kro.backas.music.source.MusicSourceRegistry;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class MusicPlayerController extends ListenerAdapter {
    private final MusicSourceRegistry sourceRegistry;
    private final Set<MusicPlayerClient> clients;

    private final Map<Long, MusicLoader> searchData;

    public MusicPlayerController(MusicSourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
        this.clients = new CopyOnWriteArraySet<>();
        this.searchData = new ConcurrentHashMap<>();
    }

    public void register(String botToken) throws InterruptedException {
        JDABuilder builder = JDABuilder
                .createDefault(botToken)
                .setChunkingFilter(ChunkingFilter.ALL)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .enableCache(CacheFlag.ROLE_TAGS);
        register(builder.build().awaitReady());
    }

    public void register(JDA discordAPI) {
        this.clients.add(new MusicPlayerClient(discordAPI, sourceRegistry.getAudioPlayerManager()));
    }

    public MusicSourceRegistry getSourceRegistry() {
        return sourceRegistry;
    }

    public void search(Identifier id, String query, Member member, SlashCommandInteractionEvent slashEvent) {
        VoiceChannel joinedVoiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (joinedVoiceChannel == null) {
            slashEvent.replyEmbeds(MusicEmbeds.error(member,
                    "음악을 검색할 수 없습니다.",
                    "음악을 재생하려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
            return;
        }
        MusicPlayerClient client = findAvailableClient(joinedVoiceChannel);
        if (client == null) {
            slashEvent.replyEmbeds(MusicEmbeds.error(member,
                    "음악을 재생할 수 없습니다.",
                    "현재 모든 노래봇이 음악을 재생중 입니다. 나중에 다시 시도해주세요.").build()).queue();
            return;
        }
        MusicLoader loader = new MusicLoader(this, client, new MusicSearchQueryInfo(
                id,
                query,
                member,
                slashEvent
        ));

        searchData.put(member.getIdLong(), loader);
        loader.loadMusic();
    }

    public Set<MusicPlayerClient> getRegisteredClients() {
        return clients;
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals(MusicLoader.PLAYLIST_STRING_SELECT_MENU_ID)) return;

        Member member = event.getMember();
        if (member == null) return;

        MusicLoader loader = searchData.get(member.getIdLong());
        if (loader == null) {
            event.reply("본인이 검색한 결과만 선택할 수 있습니다. 검색 결과가 만료되었다면 다시 검색해주세요.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (!loader.isTrackLoaded()) {
            event.reply("서버에서 요청하신 음악을 가져오고 있습니다. 잠시만 기다려주세요")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        try {
            EmbedBuilder result = findClientAndEnqueue(new MusicSelection(
                    loader.getQueryInfo(),
                    loader.getLoadedTracks().get(event.getValues().get(0))
            ));
            event.editSelectMenu(event.getComponent().asDisabled()).queue();
            event.getMessage()
                    .replyEmbeds(result.build())
                    .mentionRepliedUser(false)
                    .queue();
        } catch (MusicPlayerException e) {
            EmbedBuilder builder = switch (e.getErrorType()) {
                case NOT_IN_VOICE_CHANNEL -> MusicEmbeds.error(member,
                        "음악을 검색할 수 없습니다.",
                        "음악을 재생하려면 음성채팅방에 먼저 참여해주세요.");
                case NO_AVAILABLE_CLIENTS_FOUND -> MusicEmbeds.error(member,
                        "음악을 재생할 수 없습니다.",
                        "현재 모든 노래봇이 음악을 재생중 입니다. 나중에 다시 시도해주세요.");
            };
            event.replyEmbeds(builder.build()).queue();
        }
    }

    public EmbedBuilder findClientAndEnqueue(MusicSelection selection) throws MusicPlayerException {
        Member requestedMember = selection.getRequestedMember();
        AudioChannelUnion joinedAudioChannel = MemberUtil.getJoinedAudioChannel(requestedMember);
        if (joinedAudioChannel == null) {
            throw new MusicPlayerException(MusicPlayerException.Type.NOT_IN_VOICE_CHANNEL);
        }
        VoiceChannel voiceChannel = joinedAudioChannel.asVoiceChannel();
        MusicPlayerClient client = findAvailableClient(voiceChannel);
        if (client == null) {
            throw new MusicPlayerException(MusicPlayerException.Type.NO_AVAILABLE_CLIENTS_FOUND);
        }
        searchData.remove(requestedMember.getIdLong());
        return client.enqueue(selection, voiceChannel);
    }

    public void expireSearchData(long memberId) {
        this.searchData.remove(memberId);
    }

    public void expireSearchData(Member member) {
        this.expireSearchData(member.getIdLong());
    }

    public @Nullable MusicPlayerClient findFromVoiceChannel(VoiceChannel channel) {
        for (MusicPlayerClient client : clients) {
            VoiceChannel joined = client.getJoinedVoiceChannel();
            if (joined != null && joined.getIdLong() == channel.getIdLong()) {
                return client;
            }
        }
        return null;
    }

    public @Nullable MusicPlayerClient findAvailableClient(VoiceChannel channel) {
        MusicPlayerClient inChannel = findFromVoiceChannel(channel);
        if (inChannel != null) return inChannel;
        for (MusicPlayerClient client : clients) {
            if (client.getJoinedVoiceChannel() == null) {
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
                Thread.currentThread().interrupt();
            }
        }
        sourceRegistry.shutdown();
    }
}
