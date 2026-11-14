package kr.kro.backas.music.cache;

import kr.kro.backas.config.Config;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class DiskCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DiskCache DEFAULT = new DiskCache(Path.of(System.getProperty("luffia.cache.dir", Config.get().cache().dir())));

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

    @Nullable
    public byte[] readBytes(String relative) {
        Path path = resolve(relative);
        if (!Files.isRegularFile(path)) return null;
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            LOGGER.warn("failed to read cache file {}: {}", path, e.toString());
            return null;
        }
    }

    public void writeBytes(String relative, byte[] data) {
        Path path = resolve(relative);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(temporary, data);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("failed to write cache file {}: {}", path, e.toString());
        }
    }

    public void prune(String directory, String suffix, int keep) {
        Path folder = resolve(directory);
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> listed = Files.list(folder)) {
            List<Path> files = listed.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparingLong(DiskCache::modifiedAt).reversed())
                    .toList();
            for (int i = keep; i < files.size(); i++) Files.deleteIfExists(files.get(i));
        } catch (IOException e) {
            LOGGER.warn("failed to prune cache folder {}: {}", folder, e.toString());
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("cache path escapes the cache directory: " + relative);
        return path;
    }
}
