package kr.kro.backas.music.source;

import dev.lavalink.youtube.YoutubeSourceOptions;
import kr.kro.backas.config.LuffiaConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicSourceRegistryTest {

    @Test
    void usesTheRemoteCipherServerOnlyWhenOneIsConfigured() {
        YoutubeSourceOptions local = MusicSourceRegistry.youtubeOptions(
                new LuffiaConfig.Youtube(10, new LuffiaConfig.RemoteCipher("  ", "")));
        assertNull(local.getRemoteCipherUrl());
        assertTrue(local.isAllowSearch());

        YoutubeSourceOptions remote = MusicSourceRegistry.youtubeOptions(
                new LuffiaConfig.Youtube(10, new LuffiaConfig.RemoteCipher(" http://localhost:8001 ", "")));
        assertEquals("http://localhost:8001", remote.getRemoteCipherUrl());
        assertNull(remote.getRemoteCipherPassword());

        assertNull(MusicSourceRegistry.youtubeOptions(new LuffiaConfig.Youtube(10, null)).getRemoteCipherUrl());
    }
}
