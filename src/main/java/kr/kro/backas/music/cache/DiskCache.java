package kr.kro.backas.music.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DiskCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DiskCache DEFAULT = new DiskCache(Path.of(System.getProperty("luffia.cache.dir", "cache")));

    private final Path root;

    public DiskCache(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static DiskCache defaultCache() {
        return DEFAULT;
    }

    public static String safeName(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Nullable
    public JsonNode read(String relative) {
        Path path = resolve(relative);
        if (!Files.isRegularFile(path)) return null;
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            LOGGER.warn("failed to read cache file {}: {}", path, e.toString());
            return null;
        }
    }

    @Nullable
    public <T> T read(String relative, Class<T> type) {
        JsonNode node = read(relative);
        if (node == null) return null;
        try {
            return MAPPER.treeToValue(node, type);
        } catch (IOException e) {
            LOGGER.warn("failed to decode cache file {}: {}", relative, e.toString());
            return null;
        }
    }

    public void write(String relative, Object value) {
        Path path = resolve(relative);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("failed to write cache file {}: {}", path, e.toString());
        }
    }

    private Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("cache path escapes the cache directory: " + relative);
        return path;
    }
}
