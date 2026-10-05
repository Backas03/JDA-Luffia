package kr.kro.backas.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Config {

    private static final Logger LOGGER = LoggerFactory.getLogger(Config.class);
    public static final String DEFAULTS_RESOURCE = "/config.yaml";
    public static final String PATH_PROPERTY = "luffia.config";
    public static final String ENV_PREFIX = "LUFFIA_";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static volatile LuffiaConfig current;

    private Config() {
    }

    public static LuffiaConfig get() {
        LuffiaConfig loaded = current;
        if (loaded != null) return loaded;
        synchronized (Config.class) {
            if (current == null) current = load(externalPath());
            return current;
        }
    }

    public static Path externalPath() {
        return Path.of(System.getProperty(PATH_PROPERTY, "config.yaml"));
    }

    public static synchronized LuffiaConfig load(@Nullable Path external) {
        ObjectNode merged = defaults();
        if (external != null && Files.isRegularFile(external)) {
            try {
                JsonNode overrides = YAML.readTree(Files.readString(external));
                if (overrides != null && overrides.isObject()) merge(merged, (ObjectNode) overrides);
                LOGGER.info("설정 파일을 읽었습니다: {}", external.toAbsolutePath());
            } catch (IOException e) {
                throw new UncheckedIOException("설정 파일을 읽을 수 없습니다: " + external, e);
            }
        } else if (external != null) {
            LOGGER.info("설정 파일 {} 이 없어 기본값과 환경 변수만 사용합니다.", external.toAbsolutePath());
        }
        applyEnvironment(merged, System.getenv());
        LuffiaConfig config = convert(merged);
        current = config;
        return config;
    }

    public static boolean writeDefaultsIfMissing(Path external) {
        if (Files.exists(external)) return false;
        try (InputStream in = Config.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) return false;
            if (external.getParent() != null) Files.createDirectories(external.getParent());
            Files.copy(in, external);
            return true;
        } catch (IOException e) {
            LOGGER.warn("기본 설정 파일을 만들지 못했습니다: {}", external, e);
            return false;
        }
    }

    public static List<String> missingRequired(LuffiaConfig config) {
        List<String> missing = new ArrayList<>();
        String token = config.discord().activeToken(config.bot().dev());
        if (token == null || token.isBlank()) missing.add(config.bot().dev() ? "discord.dev-token" : "discord.token");
        return missing;
    }

    @Nullable
    public static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static ObjectNode defaults() {
        try (InputStream in = Config.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) throw new IllegalStateException("기본 설정 리소스가 없습니다: " + DEFAULTS_RESOURCE);
            JsonNode node = YAML.readTree(in);
            if (node == null || !node.isObject()) throw new IllegalStateException("기본 설정 리소스가 비어 있습니다");
            return (ObjectNode) node;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static LuffiaConfig convert(ObjectNode tree) {
        try {
            return YAML.treeToValue(tree, LuffiaConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("설정 형식이 잘못되었습니다", e);
        }
    }

    static void merge(ObjectNode base, ObjectNode overrides) {
        Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode existing = base.get(field.getKey());
            if (existing != null && existing.isObject() && field.getValue().isObject()) {
                merge((ObjectNode) existing, (ObjectNode) field.getValue());
            } else {
                base.set(field.getKey(), field.getValue());
            }
        }
    }

    static void applyEnvironment(ObjectNode root, Map<String, String> environment) {
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(ENV_PREFIX) || name.length() <= ENV_PREFIX.length()) continue;
            String[] segments = name.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT).split("_");
            if (!applyPath(root, segments, 0, entry.getValue())) {
                LOGGER.warn("환경 변수 {} 에 해당하는 설정 키가 없어 무시합니다.", name);
            }
        }
    }

    private static boolean applyPath(ObjectNode node, String[] segments, int from, String value) {
        for (int to = segments.length; to > from; to--) {
            String key = String.join("-", java.util.Arrays.copyOfRange(segments, from, to));
            JsonNode child = node.get(key);
            if (child == null) continue;
            if (to == segments.length) {
                node.set(key, scalar(child, value));
                return true;
            }
            if (child.isObject() && applyPath((ObjectNode) child, segments, to, value)) return true;
        }
        return false;
    }

    private static JsonNode scalar(JsonNode previous, String value) {
        if (previous.isArray()) {
            ArrayNode array = YAML.createArrayNode();
            for (String item : value.split(",")) {
                if (!item.isBlank()) array.add(item.trim());
            }
            return array;
        }
        if (previous.isBoolean()) return YAML.getNodeFactory().booleanNode(Boolean.parseBoolean(value.trim()));
        if (previous.isIntegralNumber()) {
            try {
                return YAML.getNodeFactory().numberNode(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (previous.isFloatingPointNumber()) {
            try {
                return YAML.getNodeFactory().numberNode(Double.parseDouble(value.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return YAML.getNodeFactory().textNode(value);
    }

    static void reset() {
        current = null;
    }
}
