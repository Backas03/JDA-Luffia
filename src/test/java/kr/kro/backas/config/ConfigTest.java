package kr.kro.backas.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void reset() {
        Config.reset();
    }

    @Test
    void defaultsLoadWithKebabCaseKeysAndEmptySecrets() {
        LuffiaConfig config = Config.load(null);
        assertEquals("", config.discord().token());
        assertEquals(10, config.musicPlayer().defaultVolume());
        assertEquals(1200, config.lyrics().display().minEditIntervalMs());
        assertEquals(450, config.lyrics().lead().renderMarginMs());
        assertEquals("KR", config.sources().spotify().country());
        assertTrue(config.llm().endpoints().isEmpty());
        assertEquals("", config.llm().endpointSpec());
        assertEquals(List.of("discord.token"), Config.missingRequired(config));
    }

    @Test
    void externalFileIsDeepMergedOverDefaults() throws Exception {
        Path external = tempDir.resolve("config.yaml");
        Files.writeString(external, String.join("\n",
                "discord:",
                "  token: abc",
                "  guilds:",
                "    main: 42",
                "music-player:",
                "  default-volume: 25",
                "llm:",
                "  endpoints:",
                "    - url: http://gpu:11434/",
                "      label: GPU",
                "      model: gemma",
                "      slots: 4",
                "  fallback:",
                "    url: http://127.0.0.1:8765",
                "    label: CPU",
                "whisper:",
                "  endpoints:",
                "    - url: http://gpu:8000",
                "      label: W",
                ""));
        LuffiaConfig config = Config.load(external);
        assertEquals("abc", config.discord().token());
        assertEquals(42, config.discord().guilds().main());
        assertEquals(0, config.discord().guilds().dev());
        assertEquals(25, config.musicPlayer().defaultVolume());
        assertEquals(0.30, config.musicPlayer().karaoke().echoSeconds(), 1e-9);
        assertEquals("http://gpu:11434/|GPU|gemma|4,http://127.0.0.1:8765|CPU||fallback", config.llm().endpointSpec());
        assertEquals("http://gpu:8000|W", config.whisper().endpointSpec());
        assertTrue(Config.missingRequired(config).isEmpty());
        assertTrue(config.isServiceGuild(999));
    }

    @Test
    void environmentVariablesOverrideNestedKeysAndLists() {
        ObjectNode tree = Config.defaults();
        Config.applyEnvironment(tree, Map.of(
                "LUFFIA_DISCORD_DEV_TOKEN", "dev-secret",
                "LUFFIA_DISCORD_MUSIC_BOT_TOKENS", "one, two",
                "LUFFIA_BOT_DEV", "true",
                "LUFFIA_LYRICS_LEAD_RENDER_MARGIN_MS", "600",
                "LUFFIA_UNKNOWN_KEY", "x"));
        LuffiaConfig config = Config.convert(tree);
        assertEquals("dev-secret", config.discord().devToken());
        assertEquals(List.of("one", "two"), config.discord().musicBotTokens());
        assertTrue(config.bot().dev());
        assertEquals(600, config.lyrics().lead().renderMarginMs());
        assertEquals(List.of("discord.dev-token").get(0), "discord.dev-token");
        assertFalse(Config.missingRequired(config).contains("discord.dev-token"));
    }

    @Test
    void writesTheDefaultFileOnlyWhenMissing() throws Exception {
        Path external = tempDir.resolve("fresh.yaml");
        assertTrue(Config.writeDefaultsIfMissing(external));
        assertTrue(Files.size(external) > 100);
        assertFalse(Config.writeDefaultsIfMissing(external));
    }
}
