package kr.kro.backas.music.llm;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LlmEndpoints {

    public static final int MAX_SLOTS = SpeedModel.MAX_LEVEL;
    private static final String FALLBACK_MARK = "fallback";

    private LlmEndpoints() {
    }

    public static List<LlmEndpoint> parse(@Nullable String urls) {
        List<String[]> entries = new ArrayList<>();
        if (urls != null) {
            for (String url : urls.split(",")) {
                String[] parts = url.trim().split("\\|", 4);
                String base = parts[0].trim().replaceAll("/+$", "");
                if (base.isBlank()) continue;
                entries.add(new String[]{
                        base,
                        parts.length > 1 ? parts[1].trim() : "",
                        parts.length > 2 ? parts[2].trim() : "",
                        parts.length > 3 ? parts[3].trim().toLowerCase(Locale.ROOT) : ""});
            }
        }
        boolean explicitFallback = entries.stream().anyMatch(entry -> FALLBACK_MARK.equals(entry[3]));
        List<LlmEndpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String[] entry = entries.get(i);
            boolean fallback = explicitFallback
                    ? FALLBACK_MARK.equals(entry[3])
                    : entries.size() >= 2 && i == entries.size() - 1;
            endpoints.add(new LlmEndpoint(entry[0], label(entry[0], entry[1]), entry[2], slots(entry[3]), fallback));
        }
        return List.copyOf(endpoints);
    }

    private static String label(String base, String label) {
        if (!label.isBlank()) return label;
        try {
            String host = URI.create(base).getHost();
            return host == null || host.isBlank() ? base : host;
        } catch (RuntimeException e) {
            return base;
        }
    }

    private static int slots(String value) {
        if (value.isBlank() || FALLBACK_MARK.equals(value)) return 1;
        try {
            return Math.max(1, Math.min(MAX_SLOTS, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
