package kr.kro.backas.music;

import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ArtworkColors {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtworkColors.class);
    private static final int CACHE_SIZE = 500;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int SAMPLE_SIZE = 64;
    private static final float MIN_SATURATION = 0.18f;
    private static final float MIN_BRIGHTNESS = 0.12f;
    private static final float MAX_BRIGHTNESS = 0.97f;
    private static final float DISPLAY_MIN_BRIGHTNESS = 0.45f;
    private static final float DISPLAY_MIN_SATURATION = 0.35f;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Map<String, Color> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Color> eldest) {
            return size() > CACHE_SIZE;
        }
    });
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private ArtworkColors() {
    }

    public static Color of(@Nullable String url) {
        if (url == null || url.isBlank()) return MusicEmbeds.PRIMARY;
        Color cached = CACHE.get(url);
        if (cached != null) return cached;
        warm(url);
        return MusicEmbeds.PRIMARY;
    }

    public static void warm(@Nullable String url) {
        if (url == null || url.isBlank() || CACHE.containsKey(url) || !LOADING.add(url)) return;
        TranslationJobs.EXECUTOR.execute(() -> {
            try {
                CACHE.put(url, fetch(url));
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("artwork color lookup failed for {}: {}", url, e.toString());
                CACHE.put(url, MusicEmbeds.PRIMARY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                LOADING.remove(url);
            }
        });
    }

    private static Color fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0 || response.body().length > MAX_BYTES) {
            throw new IOException("artwork download failed with status " + response.statusCode());
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
        if (image == null) throw new IOException("unsupported artwork format");
        return dominant(image);
    }

    static Color dominant(BufferedImage image) {
        int stepX = Math.max(1, image.getWidth() / SAMPLE_SIZE);
        int stepY = Math.max(1, image.getHeight() / SAMPLE_SIZE);
        Map<Integer, long[]> buckets = new HashMap<>();
        long[] all = new long[4];
        float[] hsb = new float[3];
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                if ((rgb >>> 24) < 128) continue;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                accumulate(all, r, g, b);
                Color.RGBtoHSB(r, g, b, hsb);
                if (hsb[1] < MIN_SATURATION || hsb[2] < MIN_BRIGHTNESS || hsb[2] > MAX_BRIGHTNESS) continue;
                int key = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
                accumulate(buckets.computeIfAbsent(key, k -> new long[4]), r, g, b);
            }
        }
        long[] best = null;
        for (long[] bucket : buckets.values()) {
            if (best == null || bucket[3] > best[3]) best = bucket;
        }
        if (best == null) best = all;
        if (best[3] == 0) return MusicEmbeds.PRIMARY;
        return displayable(new Color((int) (best[0] / best[3]), (int) (best[1] / best[3]), (int) (best[2] / best[3])));
    }

    private static void accumulate(long[] sums, int r, int g, int b) {
        sums[0] += r;
        sums[1] += g;
        sums[2] += b;
        sums[3]++;
    }

    static Color displayable(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float saturation = Math.max(hsb[1], hsb[1] < MIN_SATURATION ? hsb[1] : DISPLAY_MIN_SATURATION);
        float brightness = Math.max(hsb[2], DISPLAY_MIN_BRIGHTNESS);
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }
}
