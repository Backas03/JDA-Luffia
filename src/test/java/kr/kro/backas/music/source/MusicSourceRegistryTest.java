package kr.kro.backas.music.source;

import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.skeleton.Client;
import kr.kro.backas.config.LuffiaConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicSourceRegistryTest {

    private static final LuffiaConfig.Oauth NO_OAUTH = new LuffiaConfig.Oauth(false, "");
    private static final LuffiaConfig.PoToken NO_PO_TOKEN = new LuffiaConfig.PoToken("");

    @Test
    void usesTheRemoteCipherServerOnlyWhenOneIsConfigured() {
        YoutubeSourceOptions local = MusicSourceRegistry.youtubeOptions(
                new LuffiaConfig.Youtube(10, new LuffiaConfig.RemoteCipher("  ", ""), NO_OAUTH, NO_PO_TOKEN));
        assertNull(local.getRemoteCipherUrl());
        assertTrue(local.isAllowSearch());

        YoutubeSourceOptions remote = MusicSourceRegistry.youtubeOptions(
                new LuffiaConfig.Youtube(10, new LuffiaConfig.RemoteCipher(" http://localhost:8001 ", ""), NO_OAUTH, NO_PO_TOKEN));
        assertEquals("http://localhost:8001", remote.getRemoteCipherUrl());
        assertNull(remote.getRemoteCipherPassword());

        assertNull(MusicSourceRegistry.youtubeOptions(new LuffiaConfig.Youtube(10, null, null, null)).getRemoteCipherUrl());
    }

    @Test
    void addsTheTvClientOnlyWhenOauthIsOn() {
        assertFalse(MusicSourceRegistry.oauthEnabled(new LuffiaConfig.Youtube(10, null, null, null)));
        assertFalse(MusicSourceRegistry.oauthEnabled(new LuffiaConfig.Youtube(10, null, NO_OAUTH, null)));
        assertTrue(MusicSourceRegistry.oauthEnabled(new LuffiaConfig.Youtube(10, null, new LuffiaConfig.Oauth(true, ""), null)));

        Client[] plain = QualityRecordingClients.create(new SourceQualities(), false);
        assertTrue(Arrays.stream(plain).noneMatch(Tv.class::isInstance));

        Client[] withTv = QualityRecordingClients.create(new SourceQualities(), true);
        assertEquals(plain.length + 1, withTv.length);
        assertInstanceOf(Tv.class, withTv[1]);
        assertTrue(withTv[1].supportsOAuth());
    }

    @Test
    void pointsThePoTokenServiceAtBgutil() {
        assertFalse(MusicSourceRegistry.poTokenEnabled(new LuffiaConfig.Youtube(10, null, null, null)));
        assertFalse(MusicSourceRegistry.poTokenEnabled(new LuffiaConfig.Youtube(10, null, null, NO_PO_TOKEN)));
        assertTrue(MusicSourceRegistry.poTokenEnabled(new LuffiaConfig.Youtube(10, null, null, new LuffiaConfig.PoToken("http://127.0.0.1:4416"))));
        assertEquals("http://127.0.0.1:4416/get_pot?path=", MusicSourceRegistry.bgutilEndpoint(" http://127.0.0.1:4416/ "));
    }

    @Test
    void playsThroughWebFirstOnlyWithAPoToken() {
        Client[] withToken = QualityRecordingClients.create(new SourceQualities(), false, true);
        assertInstanceOf(Music.class, withToken[0]);
        assertFalse(withToken[0].supportsFormatLoading());
        assertEquals("WEB", withToken[1].getIdentifier());
        assertTrue(withToken[1].supportsFormatLoading());
        assertTrue(Arrays.stream(withToken).filter(MWeb.class::isInstance).noneMatch(Client::supportsFormatLoading));
        assertEquals("IOS", withToken[withToken.length - 1].getIdentifier());

        Client[] withoutToken = QualityRecordingClients.create(new SourceQualities(), false, false);
        assertTrue(Arrays.stream(withoutToken).filter(client -> client.getIdentifier().equals("WEB")).noneMatch(Client::supportsFormatLoading));
        assertTrue(Arrays.stream(withoutToken).filter(Music.class::isInstance).noneMatch(Client::supportsFormatLoading));
    }
}
