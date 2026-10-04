package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.MusicSelection;
import kr.kro.backas.music.RepeatMode;
import kr.kro.backas.music.llm.LlmPriority;
import kr.kro.backas.music.lyrics.TranslationClient;
import kr.kro.backas.music.lyrics.TranslationJobs;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AiAutoplay {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiAutoplay.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int TARGET_QUEUE_SIZE = 3;
    private static final int HISTORY_LIMIT = 200;
    private static final int SESSION_HISTORY = 10;
    private static final int SESSION_QUEUE = 10;
    private static final int MAX_CANDIDATES = 30;
    private static final int MAX_MIX_LOADS = 2;
    private static final int MAX_ROUNDS = 3;
    private static final int RECENT_ARTIST_WINDOW = 2;
    private static final int CONTEXT_CHANGED = -1;
    private static final String SCHEMA_NAME = "autoplay_picks";
    private static final String SYSTEM_PROMPT = String.join("\n",
            "You are a DJ continuing a music session for Korean listeners.",
            "You get the session tracks and numbered candidate tracks taken from YouTube's related mix.",
            "Each session line starts with a role in brackets. playing: the song playing now. queued: songs the listener chose that play next."
                    + " played: songs the listener chose earlier. auto: songs that were automatic recommendations, not the listener's choice.",
            "The listener's own choices set the direction. Follow the playing and queued songs first, and the most recent ones most of all."
                    + " When the session changed genre, language or mood, follow the new direction and ignore the older songs. Treat auto songs as weak hints only.",
            "Pick the candidates that best continue that direction: same genre, language, era and mood. If the listener gave a request, follow it first.",
            "Prefer original songs. Avoid covers, live or concert versions, remixes, sped up or nightcore edits, compilations, playlists, reaction or non-music videos, unless the request asks for them.",
            "Never pick the same song twice.",
            TrackHints.HINT_GUIDE,
            "Output JSON only: {\"picks\": [candidate numbers, best first]}");

    enum Role { PLAYED, PLAYING, QUEUED }

    record SessionTrack(AudioTrackInfo info, boolean youtube, boolean auto, Role role) {
        String label() {
            String name = role.name().toLowerCase(Locale.ROOT);
            if (!auto) return name;
            return role == Role.PLAYED ? "auto" : name + ", auto";
        }
    }

    private record PlayedTrack(AudioTrackInfo info, boolean youtube, boolean auto, int generation) {
    }

    private final MusicPlayerClient client;
    private final Deque<PlayedTrack> history = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean refilling = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean enabled = true;
    private volatile boolean explicit;
    private volatile String criteria = "";
    private volatile boolean diverse;
    private volatile Member member;
    private volatile SlashCommandInteractionEvent event;

    public AiAutoplay(MusicPlayerClient client) {
        this.client = client;
    }

    public void start(String criteria, boolean diverse, Member member, SlashCommandInteractionEvent event) {
        this.criteria = criteria == null ? "" : criteria.strip();
        this.diverse = diverse;
        this.member = member;
        this.event = event;
        this.enabled = true;
        this.explicit = true;
        LOGGER.info("ai autoplay started in guild {} with criteria '{}', diverse artists {}", client.getGuildId(), this.criteria, diverse);
        requestRefill();
    }

    public boolean stop() {
        boolean wasEnabled = enabled;
        enabled = false;
        explicit = false;
        return wasEnabled;
    }

    public void reset() {
        enabled = true;
        explicit = false;
        criteria = "";
        diverse = false;
        member = null;
        event = null;
        history.clear();
        generation.incrementAndGet();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isExplicit() {
        return explicit;
    }

    public String getCriteria() {
        return criteria;
    }

    public void onListenerTracksAdded(MusicSelection selection) {
        member = selection.getRequestedMember();
        event = selection.getSlashCommandInteractionEvent();
        generation.incrementAndGet();
        boolean hadRequest = !criteria.isBlank() || diverse;
        criteria = "";
        diverse = false;
        int removed = client.removeFromQueue(pendingPicks());
        if (removed > 0 || hadRequest) {
            LOGGER.info("ai autoplay follows the listener's new songs in guild {}: dropped {} pending pick(s){}",
                    client.getGuildId(), removed, hadRequest ? ", cleared the earlier request" : "");
        }
    }

    private Set<AudioTrack> pendingPicks() {
        Set<AudioTrack> picks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AudioTrack track : client.getTrackQueue()) {
            if (isAutoplay(track)) picks.add(track);
        }
        return picks;
    }

    static boolean isAutoplay(AudioTrack track) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        return selection != null && selection.isAutoplay();
    }

    public void onTrackStarted(AudioTrack track) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        boolean auto = selection != null && selection.isAutoplay();
        if (selection != null && !auto) {
            member = selection.getRequestedMember();
            event = selection.getSlashCommandInteractionEvent();
        }
        history.addLast(new PlayedTrack(track.getInfo(), YoutubeLookup.isYoutube(track), auto, generation.get()));
        while (history.size() > HISTORY_LIMIT) history.pollFirst();
        requestRefill();
    }

    public void onVoiceConnected() {
        requestRefill();
    }

    public boolean onQueueEmpty() {
        if (!isActive()) return false;
        requestRefill();
        return true;
    }

    private boolean isActive() {
        if (!enabled) return false;
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        return controller.getAiGuard().isEnabled() && controller.getTranslationClient().isAvailable();
    }

    private boolean needsRefill() {
        return isActive()
                && client.getRepeatMode() == RepeatMode.NO_REPEAT
                && client.hasJoinedToVoiceChannel()
                && client.getTrackQueue().size() < TARGET_QUEUE_SIZE;
    }

    private void requestRefill() {
        if (!needsRefill()) return;
        if (!refilling.compareAndSet(false, true)) return;
        TranslationJobs.EXECUTOR.execute(this::refillLoop);
    }

    private void refillLoop() {
        try {
            for (int round = 0; round < MAX_ROUNDS && needsRefill(); round++) {
                if (refillOnce() == 0) break;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("ai autoplay refill failed", e);
        } finally {
            refilling.set(false);
        }
        if (client.hasJoinedToVoiceChannel() && client.getCurrentPlaying() == null && client.getTrackQueue().isEmpty()) {
            LOGGER.info("ai autoplay found nothing to play, leaving voice channel");
            client.disconnectFromVoiceChannelAndResetTrack();
        }
    }

    private int refillOnce() {
        int startGeneration = generation.get();
        Member requester = member;
        SlashCommandInteractionEvent requestEvent = event;
        VoiceChannel channel = client.getJoinedVoiceChannel();
        if (requester == null || requestEvent == null || channel == null) return 0;
        List<AudioTrack> queue = client.getTrackQueue();
        int need = TARGET_QUEUE_SIZE - queue.size();
        if (need <= 0) return 0;
        AudioTrack current = client.getCurrentPlaying();
        List<SessionTrack> session = session(current, queue);
        if (session.isEmpty()) return 0;

        Set<String> seen = new HashSet<>();
        for (PlayedTrack played : history) seen.addAll(YoutubeLookup.keys(played.info()));
        for (AudioTrack track : queue) seen.addAll(YoutubeLookup.keys(track.getInfo()));
        if (current != null) seen.addAll(YoutubeLookup.keys(current.getInfo()));

        Set<String> recentArtists = recentArtists(session);
        List<AudioTrack> candidates = new ArrayList<>();
        for (SessionTrack seed : seeds(session, diverse ? MAX_MIX_LOADS + 1 : MAX_MIX_LOADS, ThreadLocalRandom.current())) {
            collectMix(seed, seen, candidates);
            if (withoutArtists(candidates, recentArtists).size() >= Math.max(need * 3, 10)) break;
        }
        if (candidates.isEmpty()) {
            LOGGER.info("ai autoplay found no new candidates");
            return 0;
        }
        List<AudioTrack> fresh = withoutArtists(candidates, recentArtists);
        if (fresh.isEmpty()) {
            LOGGER.info("ai autoplay candidates all share recent artists, allowing them");
            fresh = candidates;
        }

        List<AudioTrack> picked = ArtistVariety.limit(select(session, fresh, need),
                track -> ArtistVariety.names(track.getInfo()), 1, need);
        if (generation.get() != startGeneration) {
            LOGGER.info("ai autoplay dropped {} pick(s), the listener added songs meanwhile", picked.size());
            return CONTEXT_CHANGED;
        }
        int added = 0;
        for (AudioTrack track : picked) {
            if (!enabled || !client.hasJoinedToVoiceChannel() || generation.get() != startGeneration) break;
            boolean queued = client.enqueueOrPlay(MusicSelection.autoplay(requester, requestEvent, track), channel);
            if (!queued) announceNowPlaying(requestEvent, track);
            added++;
        }
        LOGGER.info("ai autoplay added {} track(s) from {} candidate(s)", added, candidates.size());
        return added;
    }

    private List<SessionTrack> session(@Nullable AudioTrack current, List<AudioTrack> queue) {
        int now = generation.get();
        String currentId = current == null ? null : current.getIdentifier();
        List<SessionTrack> played = new ArrayList<>();
        for (PlayedTrack track : history) {
            if (track.generation() != now) continue;
            if (currentId != null && currentId.equals(track.info().identifier)) continue;
            played.add(new SessionTrack(track.info(), track.youtube(), track.auto(), Role.PLAYED));
        }
        List<SessionTrack> session = new ArrayList<>(played.subList(Math.max(0, played.size() - SESSION_HISTORY), played.size()));
        if (current != null) {
            session.add(new SessionTrack(current.getInfo(), YoutubeLookup.isYoutube(current), isAutoplay(current), Role.PLAYING));
        }
        for (AudioTrack track : queue.subList(0, Math.min(queue.size(), SESSION_QUEUE))) {
            session.add(new SessionTrack(track.getInfo(), YoutubeLookup.isYoutube(track), isAutoplay(track), Role.QUEUED));
        }
        return session;
    }

    static List<SessionTrack> seeds(List<SessionTrack> session, int loads, Random random) {
        List<SessionTrack> playing = new ArrayList<>();
        List<SessionTrack> queued = new ArrayList<>();
        List<SessionTrack> played = new ArrayList<>();
        List<SessionTrack> autoPlaying = new ArrayList<>();
        List<SessionTrack> autoQueued = new ArrayList<>();
        List<SessionTrack> autoPlayed = new ArrayList<>();
        for (SessionTrack track : session) {
            switch (track.role()) {
                case PLAYING -> (track.auto() ? autoPlaying : playing).add(track);
                case QUEUED -> (track.auto() ? autoQueued : queued).add(track);
                case PLAYED -> (track.auto() ? autoPlayed : played).add(track);
            }
        }
        Collections.shuffle(queued, random);
        Collections.reverse(played);
        Collections.reverse(autoPlayed);
        List<SessionTrack> ordered = new ArrayList<>(playing);
        ordered.addAll(queued);
        ordered.addAll(played);
        ordered.addAll(autoPlaying);
        ordered.addAll(autoQueued);
        ordered.addAll(autoPlayed);
        List<SessionTrack> seeds = new ArrayList<>();
        Set<String> chosen = new HashSet<>();
        for (SessionTrack track : ordered) {
            if (seeds.size() >= loads) break;
            if (chosen.add(track.info().identifier)) seeds.add(track);
        }
        return seeds;
    }

    private Set<String> recentArtists(List<SessionTrack> session) {
        int window = diverse ? session.size() : RECENT_ARTIST_WINDOW;
        Set<String> artists = new HashSet<>();
        for (SessionTrack track : session.subList(Math.max(0, session.size() - window), session.size())) {
            artists.addAll(ArtistVariety.names(track.info()));
        }
        return artists;
    }

    private static List<AudioTrack> withoutArtists(List<AudioTrack> tracks, Set<String> artists) {
        List<AudioTrack> kept = new ArrayList<>();
        for (AudioTrack track : tracks) {
            if (!ArtistVariety.overlaps(ArtistVariety.names(track.getInfo()), artists)) kept.add(track);
        }
        return kept;
    }

    private void collectMix(SessionTrack seed, Set<String> seen, List<AudioTrack> candidates) {
        String videoId = seed.info().identifier;
        if (!seed.youtube()) {
            AudioTrack found = YoutubeLookup.searchFirst(client.getAudioPlayerManager(), seed.info().author + " " + seed.info().title);
            videoId = found == null ? null : found.getIdentifier();
        }
        if (videoId == null) return;
        YoutubeLookup.collectMix(client.getAudioPlayerManager(), videoId, seen, candidates, MAX_CANDIDATES,
                YoutubeLookup.wantsVariants(criteria));
    }

    private List<AudioTrack> select(List<SessionTrack> session, List<AudioTrack> candidates, int need) {
        List<AudioTrack> fallback = new ArrayList<>(candidates.subList(0, Math.min(need, candidates.size())));
        TranslationClient translator = Main.getLuffia().getMusicPlayerController().getTranslationClient();
        if (candidates.size() <= need || !translator.isEnabled()) return fallback;
        StringBuilder user = new StringBuilder();
        user.append("Listener request: <request>").append(criteria.isBlank() ? "none" : PromptSafe.data(criteria)).append("</request>\n");
        user.append("Session, oldest first:\n<session>\n");
        for (SessionTrack track : session) {
            user.append("- [").append(track.label()).append("] ").append(TrackHints.describe(track.info())).append('\n');
        }
        user.append("</session>\nCandidates:\n<candidates>\n");
        for (int i = 0; i < candidates.size(); i++) {
            user.append(i + 1).append(". ").append(TrackHints.describe(candidates.get(i).getInfo())).append('\n');
        }
        user.append("</candidates>\nPick up to ").append(need).append(" candidates, each by a different artist")
                .append(diverse ? ", preferring artists that are not in the session." : ".");
        try {
            JsonNode result = translator.requestJson(SYSTEM_PROMPT, user.toString(), SCHEMA_NAME,
                    schema(candidates.size(), need), 16 * need + 32, LlmPriority.BACKGROUND);
            List<AudioTrack> picked = new ArrayList<>();
            Set<Integer> used = new HashSet<>();
            for (JsonNode pick : result.path("picks")) {
                int n = pick.asInt(-1);
                if (n < 1 || n > candidates.size() || !used.add(n)) continue;
                picked.add(candidates.get(n - 1));
                if (picked.size() >= need) break;
            }
            if (!picked.isEmpty()) return picked;
            LOGGER.info("ai autoplay selection returned no picks, using mix order");
        } catch (IOException e) {
            LOGGER.warn("ai autoplay selection failed, using mix order: {}", e.toString());
        }
        return fallback;
    }

    private void announceNowPlaying(SlashCommandInteractionEvent requestEvent, AudioTrack track) {
        try {
            requestEvent.getMessageChannel()
                    .sendMessageEmbeds(MusicEmbeds.play(track, client.getGuild()).build())
                    .queue(null, e -> LOGGER.debug("failed to announce ai autoplay track", e));
        } catch (RuntimeException e) {
            LOGGER.debug("failed to announce ai autoplay track", e);
        }
    }

    private static ObjectNode schema(int candidates, int need) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode picks = schema.putObject("properties").putObject("picks");
        picks.put("type", "array");
        picks.put("minItems", 1);
        picks.put("maxItems", need);
        ObjectNode item = picks.putObject("items");
        item.put("type", "integer");
        item.put("minimum", 1);
        item.put("maximum", candidates);
        schema.putArray("required").add("picks");
        return schema;
    }
}
