package kr.kro.backas.config;

import java.util.ArrayList;
import java.util.List;

public record LuffiaConfig(Bot bot,
                           Discord discord,
                           Riot riot,
                           Sources sources,
                           MusicPlayer musicPlayer,
                           Autoplay autoplay,
                           Llm llm,
                           Lyrics lyrics,
                           Whisper whisper,
                           Cache cache,
                           Scheduler scheduler) {

    public boolean isServiceGuild(long guildId) {
        List<Long> service = discord.serviceGuilds();
        return service == null || service.isEmpty() || service.contains(guildId);
    }

    public record Bot(boolean dev, String version, String zone, String activity, String github, String license, Shutdown shutdown) {
    }

    public record Shutdown(int gracefulSeconds, int forcedSeconds, int haltAfterSeconds) {
    }

    public record Discord(String token, String devToken, List<String> musicBotTokens, List<Long> serviceGuilds) {
        public String activeToken(boolean dev) {
            return dev ? devToken : token;
        }
    }

    public record Riot(String apiKey) {
    }

    public record Sources(Youtube youtube, Spotify spotify) {
    }

    public record Youtube(int playlistPageCount) {
    }

    public record Spotify(String clientId, String clientSecret, String refreshToken, String country, int pageLimit) {
    }

    public record MusicPlayer(int defaultVolume, int connectGraceSeconds, Karaoke karaoke) {
    }

    public record Karaoke(double echoSeconds, double echoDecay, double centerGain) {
    }

    public record Autoplay(boolean enabledByDefault, int queueSize) {
    }

    public record Llm(List<Endpoint> endpoints, Fallback fallback, String modelOverride, int recheckSeconds, int translationCacheSize) {
        public String endpointSpec() {
            List<String> parts = new ArrayList<>();
            if (endpoints != null) {
                for (Endpoint endpoint : endpoints) {
                    if (endpoint.url() == null || endpoint.url().isBlank()) continue;
                    parts.add(endpoint.url().trim() + "|" + nullToEmpty(endpoint.label()) + "|" + nullToEmpty(endpoint.model()) + "|"
                            + Math.max(1, endpoint.slots()));
                }
            }
            if (fallback != null && fallback.url() != null && !fallback.url().isBlank()) {
                parts.add(fallback.url().trim() + "|" + nullToEmpty(fallback.label()) + "||fallback");
            }
            return String.join(",", parts);
        }
    }

    public record Endpoint(String url, String label, String model, int slots) {
    }

    public record Fallback(String url, String label, String startCommand, String stopCommand) {
    }

    public record Lyrics(Display display, Lead lead, RateLimit rateLimit, Prefetch prefetch) {
    }

    public record Display(long tickMs, long minEditIntervalMs, long clockEditIntervalMs, long postEditGapMs, int fullPreviewLines,
                          int cardTextBudget) {
    }

    public record Lead(long initialMs, long minMs, long maxMs, long renderMarginMs) {
    }

    public record RateLimit(int editsPerWindow, long windowMs, long editDeadlineMs, long inFlightTimeoutMs, long extrasPauseMs) {
    }

    public record Prefetch(int count, int countFast, int lookupThreads) {
    }

    public record Whisper(List<WhisperEndpoint> endpoints, int retryFailedSeconds, AutoSync autoSync, Convert convert) {
        public String endpointSpec() {
            List<String> parts = new ArrayList<>();
            if (endpoints != null) {
                for (WhisperEndpoint endpoint : endpoints) {
                    if (endpoint.url() == null || endpoint.url().isBlank()) continue;
                    parts.add(endpoint.url().trim() + "|" + nullToEmpty(endpoint.label()));
                }
            }
            return String.join(",", parts);
        }
    }

    public record WhisperEndpoint(String url, String label) {
    }

    public record AutoSync(boolean enabled, int captureSeconds, long trustLyricsWithinFineMs, long trustLyricsWithinCoarseMs, int noneRetryHours) {
    }

    public record Convert(boolean auto, int maxSeconds, int firstPassSeconds, int passStepSeconds) {
    }

    public record Cache(String dir) {
    }

    public record Scheduler(int lyricsThreads, Watchdog watchdog) {
    }

    public record Watchdog(long stallMs, long reportGapMs) {
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
