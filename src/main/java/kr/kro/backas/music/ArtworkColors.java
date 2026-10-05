package kr.kro.backas.music;

import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ArtworkColors {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtworkColors.class);
    private static final int CACHE_SIZE = 120;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int SAMPLE_SIZE = 64;
    private static final float MIN_SATURATION = 0.18f;
    private static final float MIN_BRIGHTNESS = 0.12f;
    private static final float MAX_BRIGHTNESS = 0.97f;
    private static final float DISPLAY_MIN_BRIGHTNESS = 0.45f;
    private static final float DISPLAY_MIN_SATURATION = 0.35f;
    private static final double WIDE_RATIO = 1.4;
    private static final double MAX_BAND = 0.45;
    private static final int MIN_BAND_PX = 4;
    private static final int PLACEHOLDER_MAX = 120;
    private static final double FLAT_SIDE_DEVIATION = 14;
    static final int BANNER_WIDTH = 960;
    static final int BANNER_HEIGHT = 540;
    private static final int BLUR_SIZE = 24;
    private static final float BACKDROP_SHADE = 0.55f;
    private static final float JPEG_QUALITY = 0.85f;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    record Artwork(Color color, boolean available, @Nullable byte[] banner) {
    }

    public record Banner(@Nullable String url, @Nullable byte[] data) {
        public boolean isFile() {
            return data != null;
        }
    }

    private static final Artwork FALLBACK = new Artwork(MusicEmbeds.PRIMARY, false, null);
    private static final Map<String, Artwork> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Artwork> eldest) {
            return size() > CACHE_SIZE;
        }
    });
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private ArtworkColors() {
    }

    public static Color of(@Nullable String url) {
        if (url == null || url.isBlank()) return MusicEmbeds.PRIMARY;
        Artwork cached = CACHE.get(url);
        if (cached != null) return cached.color();
        warm(url);
        return MusicEmbeds.PRIMARY;
    }

    public static boolean isAvailable(@Nullable String url) {
        if (url == null || url.isBlank()) return false;
        Artwork cached = CACHE.get(url);
        if (cached == null) warm(url);
        return cached != null && cached.available();
    }

    @Nullable
    public static Banner bannerFor(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        Artwork cached = CACHE.get(url);
        if (cached == null) {
            warm(url);
            return null;
        }
        if (!cached.available()) return null;
        return cached.banner() == null ? new Banner(url, null) : new Banner(null, cached.banner());
    }

    @Nullable
    public static Banner await(@Nullable String url, long timeoutMs) {
        if (url == null || url.isBlank()) return null;
        long deadline = System.currentTimeMillis() + timeoutMs;
        warm(url);
        while (!CACHE.containsKey(url) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return bannerFor(url);
    }

    public static void warm(@Nullable String url) {
        if (url == null || url.isBlank() || CACHE.containsKey(url) || !LOADING.add(url)) return;
        TranslationJobs.EXECUTOR.execute(() -> {
            try {
                CACHE.put(url, fetch(url));
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("artwork lookup failed for {}: {}", url, e.toString());
                CACHE.put(url, FALLBACK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                LOADING.remove(url);
            }
        });
    }

    private static Artwork fetch(String url) throws IOException, InterruptedException {
        BufferedImage image = null;
        boolean substituted = false;
        for (String candidate : candidates(url)) {
            image = download(candidate);
            if (image != null) break;
            substituted = true;
        }
        if (image == null) throw new IOException("artwork download failed for " + url);
        BufferedImage cropped = cropFlatBands(image);
        boolean changed = substituted || cropped != image;
        boolean wide = cropped.getHeight() > 0 && (double) cropped.getWidth() / cropped.getHeight() >= WIDE_RATIO;
        byte[] banner;
        if (!wide) banner = composeBanner(cropped);
        else banner = changed ? coverBanner(cropped) : null;
        return new Artwork(dominant(cropped), true, banner);
    }

    @Nullable
    private static BufferedImage download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0 || response.body().length > MAX_BYTES) return null;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
        if (image == null || image.getWidth() <= PLACEHOLDER_MAX || image.getHeight() <= PLACEHOLDER_MAX) return null;
        return image;
    }

    static List<String> candidates(String url) {
        List<String> candidates = new ArrayList<>();
        candidates.add(url);
        if (url.endsWith("/maxresdefault.jpg")) {
            String base = url.substring(0, url.length() - "maxresdefault.jpg".length());
            candidates.add(base + "sddefault.jpg");
            candidates.add(base + "hqdefault.jpg");
        }
        return candidates;
    }

    static BufferedImage cropFlatBands(BufferedImage image) {
        int top = flatRows(image, true);
        int bottom = flatRows(image, false);
        int left = flatColumns(image, true);
        int right = flatColumns(image, false);
        int width = image.getWidth() - left - right;
        int height = image.getHeight() - top - bottom;
        if ((top == 0 && bottom == 0 && left == 0 && right == 0) || width < 16 || height < 16) return image;
        return image.getSubimage(left, top, width, height);
    }

    private static int flatRows(BufferedImage image, boolean fromTop) {
        int limit = (int) (image.getHeight() * MAX_BAND);
        int flat = 0;
        for (int i = 0; i < limit; i++) {
            int y = fromTop ? i : image.getHeight() - 1 - i;
            if (!isFlat(image, 0, image.getWidth(), y, y + 1)) break;
            flat++;
        }
        return flat < MIN_BAND_PX ? 0 : flat;
    }

    private static int flatColumns(BufferedImage image, boolean fromLeft) {
        int limit = (int) (image.getWidth() * MAX_BAND);
        int flat = 0;
        for (int i = 0; i < limit; i++) {
            int x = fromLeft ? i : image.getWidth() - 1 - i;
            if (!isFlat(image, x, x + 1, 0, image.getHeight())) break;
            flat++;
        }
        return flat < MIN_BAND_PX ? 0 : flat;
    }

    private static boolean isFlat(BufferedImage image, int fromX, int toX, int fromY, int toY) {
        int stepX = Math.max(1, (toX - fromX) / SAMPLE_SIZE);
        int stepY = Math.max(1, (toY - fromY) / SAMPLE_SIZE);
        double sum = 0;
        double squares = 0;
        int count = 0;
        for (int y = fromY; y < toY; y += stepY) {
            for (int x = fromX; x < toX; x += stepX) {
                int rgb = image.getRGB(x, y);
                double luma = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
                sum += luma;
                squares += luma * luma;
                count++;
            }
        }
        if (count == 0) return false;
        double mean = sum / count;
        return Math.sqrt(Math.max(0, squares / count - mean * mean)) < FLAT_SIDE_DEVIATION;
    }

    static byte[] coverBanner(BufferedImage image) throws IOException {
        BufferedImage canvas = new BufferedImage(BANNER_WIDTH, BANNER_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double cover = Math.max((double) BANNER_WIDTH / image.getWidth(), (double) BANNER_HEIGHT / image.getHeight());
            int width = (int) Math.ceil(image.getWidth() * cover);
            int height = (int) Math.ceil(image.getHeight() * cover);
            graphics.drawImage(image, (BANNER_WIDTH - width) / 2, (BANNER_HEIGHT - height) / 2, width, height, null);
        } finally {
            graphics.dispose();
        }
        return jpeg(canvas);
    }

    static byte[] composeBanner(BufferedImage image) throws IOException {
        BufferedImage canvas = new BufferedImage(BANNER_WIDTH, BANNER_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double cover = Math.max((double) BANNER_WIDTH / image.getWidth(), (double) BANNER_HEIGHT / image.getHeight());
            int coverWidth = (int) Math.ceil(image.getWidth() * cover);
            int coverHeight = (int) Math.ceil(image.getHeight() * cover);
            int blurHeight = Math.max(1, BLUR_SIZE * image.getHeight() / Math.max(1, image.getWidth()));
            Image blurred = image.getScaledInstance(BLUR_SIZE, blurHeight, Image.SCALE_AREA_AVERAGING);
            graphics.drawImage(blurred, (BANNER_WIDTH - coverWidth) / 2, (BANNER_HEIGHT - coverHeight) / 2, coverWidth, coverHeight, null);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, BACKDROP_SHADE));
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, BANNER_WIDTH, BANNER_HEIGHT);
            graphics.setComposite(AlphaComposite.SrcOver);
            int fitWidth = Math.max(1, image.getWidth() * BANNER_HEIGHT / Math.max(1, image.getHeight()));
            graphics.drawImage(image, (BANNER_WIDTH - fitWidth) / 2, 0, fitWidth, BANNER_HEIGHT, null);
        } finally {
            graphics.dispose();
        }
        return jpeg(canvas);
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IOException("no jpeg writer");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
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
