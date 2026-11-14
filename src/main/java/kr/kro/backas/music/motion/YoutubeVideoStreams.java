package kr.kro.backas.music.motion;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import kr.kro.backas.music.source.SourceQualities;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;

final class YoutubeVideoStreams {

    static final List<Integer> PREFERRED_ITAGS = List.of(134, 243, 18, 135, 244, 133, 242, 136, 247, 160, 278);
    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeVideoStreams.class);

    private YoutubeVideoStreams() {
    }

    @Nullable
    static URI find(YoutubeAudioSourceManager source, String videoId) {
        for (Client client : source.getClients()) {
            if (!client.supportsFormatLoading()) continue;
            try (HttpInterface http = source.getInterface()) {
                http.getContext().setAttribute(Client.OAUTH_CLIENT_ATTRIBUTE, client.supportsOAuth());
                TrackFormats formats = SourceQualities.withoutRecording(() -> client.loadFormats(source, http, videoId));
                StreamFormat format = formats == null ? null : pick(formats.getFormats());
                if (format == null) continue;
                if (!client.requirePlayerScript()) return format.getUrl();
                URI resolved = source.getCipherManager().resolveFormatUrl(http, formats.getPlayerScriptUrl(), format);
                return client.transformPlaybackUri(format.getUrl(), resolved, formats.getPoToken());
            } catch (CannotBeLoaded | IOException | RuntimeException e) {
                LOGGER.debug("youtube client {} gave no video stream for {}: {}", client.getIdentifier(), videoId, e.toString());
            }
        }
        return null;
    }

    @Nullable
    static StreamFormat pick(List<StreamFormat> formats) {
        StreamFormat best = null;
        int bestRank = Integer.MAX_VALUE;
        for (StreamFormat format : formats) {
            int rank = PREFERRED_ITAGS.indexOf(format.getItag());
            if (rank < 0 || rank >= bestRank || format.isSabr()) continue;
            best = format;
            bestRank = rank;
        }
        return best;
    }
}
