package kr.kro.backas.music.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.Ios;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * youtube-source 의 재생 클라이언트들. 포맷을 고르는 동작은 그대로 두고, 포맷 목록을 만들 때
 * 원본 JSON 의 평균 비트레이트·샘플레이트를 {@link SourceQualities} 에 함께 남긴다.
 * 클라이언트 순서는 youtube-source 가 시도하는 순서다.
 */
final class QualityRecordingClients {

    private QualityRecordingClients() {
    }

    static Client[] create(SourceQualities qualities) {
        return create(qualities, false, false);
    }

    static Client[] create(SourceQualities qualities, boolean withTv) {
        return create(qualities, withTv, false);
    }

    static Client[] create(SourceQualities qualities, boolean withTv, boolean withPoToken) {
        List<Client> clients = new ArrayList<>();
        clients.add(new MusicWithThumbnail(playback(false)));
        if (withTv) {
            clients.add(new Tv() {
                @Override
                public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String videoId)
                        throws CannotBeLoaded, IOException {
                    return qualities.capture(videoId, () -> fullStream(super.loadFormats(source, http, videoId), false));
                }

                @Override
                protected boolean extractFormat(JsonBrowser json, List<StreamFormat> formats, boolean live, boolean sabrAvailable) {
                    int before = formats.size();
                    boolean result = super.extractFormat(json, formats, live, sabrAvailable);
                    SourceQualities.noteFormats(json, formats, before, live);
                    return result;
                }
            });
        }
        clients.add(new WebWithThumbnail(playback(withPoToken)) {
            @Override
            public synchronized TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String videoId)
                    throws CannotBeLoaded, IOException {
                return qualities.capture(videoId, () -> fullStream(super.loadFormats(source, http, videoId), true));
            }

            @Override
            protected boolean extractFormat(JsonBrowser json, List<StreamFormat> formats, boolean live, boolean sabrAvailable) {
                int before = formats.size();
                boolean result = super.extractFormat(json, formats, live, sabrAvailable);
                SourceQualities.noteFormats(json, formats, before, live);
                return result;
            }
        });
        clients.addAll(List.of(
                new TvHtml5Simply() {
                    @Override
                    public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String videoId)
                            throws CannotBeLoaded, IOException {
                        return qualities.capture(videoId, () -> fullStream(super.loadFormats(source, http, videoId), false));
                    }

                    @Override
                    protected boolean extractFormat(JsonBrowser json, List<StreamFormat> formats, boolean live, boolean sabrAvailable) {
                        int before = formats.size();
                        boolean result = super.extractFormat(json, formats, live, sabrAvailable);
                        SourceQualities.noteFormats(json, formats, before, live);
                        return result;
                    }
                },
                new AndroidVrWithThumbnail() {
                    @Override
                    public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String videoId)
                            throws CannotBeLoaded, IOException {
                        return qualities.capture(videoId, () -> fullStream(super.loadFormats(source, http, videoId), false));
                    }

                    @Override
                    protected boolean extractFormat(JsonBrowser json, List<StreamFormat> formats, boolean live, boolean sabrAvailable) {
                        int before = formats.size();
                        boolean result = super.extractFormat(json, formats, live, sabrAvailable);
                        SourceQualities.noteFormats(json, formats, before, live);
                        return result;
                    }
                },
                new MWebWithThumbnail(playback(false)),
                new WebEmbeddedWithThumbnail() {
                    @Override
                    public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String videoId)
                            throws CannotBeLoaded, IOException {
                        return qualities.capture(videoId, () -> fullStream(super.loadFormats(source, http, videoId), false));
                    }

                    @Override
                    protected boolean extractFormat(JsonBrowser json, List<StreamFormat> formats, boolean live, boolean sabrAvailable) {
                        int before = formats.size();
                        boolean result = super.extractFormat(json, formats, live, sabrAvailable);
                        SourceQualities.noteFormats(json, formats, before, live);
                        return result;
                    }
                },
                new Ios() {
                    @Override
                    public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String videoId)
                            throws CannotBeLoaded, IOException {
                        return qualities.capture(videoId, () -> fullStream(super.loadFormats(source, http, videoId), false));
                    }

                    @Override
                    protected boolean extractFormat(JsonBrowser json, List<StreamFormat> formats, boolean live, boolean sabrAvailable) {
                        int before = formats.size();
                        boolean result = super.extractFormat(json, formats, live, sabrAvailable);
                        SourceQualities.noteFormats(json, formats, before, live);
                        return result;
                    }
                }
        ));
        return clients.toArray(new Client[0]);
    }

    static TrackFormats fullStream(TrackFormats formats, boolean sabrAllowed) throws IOException {
        if (formats == null) return null;
        StreamFormat best = formats.getBestFormat();
        if (best == null || !best.isSabr()) return formats;
        if (!sabrAllowed) throw new IOException("SABR stream is only played through the WEB client");
        String poToken = formats.getPoToken();
        if (poToken == null || poToken.isBlank()) throw new IOException("SABR stream has no PO token and would stop after a minute");
        return formats;
    }

    private static ClientOptions playback(boolean enabled) {
        ClientOptions options = new ClientOptions();
        options.setPlayback(enabled);
        return options;
    }
}
