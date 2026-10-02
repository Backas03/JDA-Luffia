package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SongInfoClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode results(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void prefersTheEarliestExactTitleOverLaterVariants() throws Exception {
        JsonNode results = results("""
                [{"trackName": "Dynamite (Live)", "artistName": "BTS", "collectionName": "Live Album", "releaseDate": "2025-07-18T07:00:00Z", "primaryGenreName": "K-Pop"},
                 {"trackName": "Dynamite", "artistName": "BTS", "collectionName": "BE", "releaseDate": "2020-11-20T08:00:00Z", "primaryGenreName": "K-Pop"},
                 {"trackName": "Dynamite", "artistName": "BTS", "collectionName": "Dynamite - Single", "releaseDate": "2020-08-21T07:00:00Z", "primaryGenreName": "Pop"}]""");
        SongInfoClient.Catalog catalog = SongInfoClient.pickCatalog(results, "Dynamite", "BTS");
        assertNotNull(catalog);
        assertEquals("2020-08-21", catalog.released());
        assertEquals("Dynamite - Single", catalog.album());
    }

    @Test
    void fallsBackToAVariantWhenNoExactTitleExists() throws Exception {
        JsonNode results = results("""
                [{"trackName": "Dynamite (Live)", "artistName": "BTS", "collectionName": "Live Album", "releaseDate": "2025-07-18T07:00:00Z", "primaryGenreName": "K-Pop"}]""");
        SongInfoClient.Catalog catalog = SongInfoClient.pickCatalog(results, "Dynamite", "BTS");
        assertNotNull(catalog);
        assertEquals("Dynamite (Live)", catalog.track());
    }

    @Test
    void ignoresSameTitleByAnotherArtist() throws Exception {
        JsonNode results = results("""
                [{"trackName": "Dynamite", "artistName": "Taio Cruz", "collectionName": "Rokstarr", "releaseDate": "2010-05-30T07:00:00Z", "primaryGenreName": "Pop"}]""");
        assertNull(SongInfoClient.pickCatalog(results, "Dynamite", "BTS"));
        assertNull(SongInfoClient.pickCatalog(results, "", "BTS"));
    }

    @Test
    void matchesTitlesAcrossWidthAndPunctuation() throws Exception {
        JsonNode results = results("""
                [{"trackName": "テレパシ", "artistName": "DECO*27", "collectionName": "テレパシ - Single", "releaseDate": "2025-02-22T12:00:00Z", "primaryGenreName": "J-Pop"}]""");
        SongInfoClient.Catalog catalog = SongInfoClient.pickCatalog(results, "ﾃﾚﾊﾟｼ", "deco 27");
        assertNotNull(catalog);
        assertEquals("2025-02-22", catalog.released());
    }

    @Test
    void keepsOnlyPagesAboutTheSongOrArtist() {
        SongInfoClient.WikiPage artist = new SongInfoClient.WikiPage("ja", 1, "DECO*27", "日本の音楽プロデューサー。");
        SongInfoClient.WikiPage song = new SongInfoClient.WikiPage("en", 2, "Dynamite (BTS song)", "A song by BTS released in 2020.");
        SongInfoClient.WikiPage otherSong = new SongInfoClient.WikiPage("en", 3, "Dynamite (Taio Cruz song)", "A song by Taio Cruz.");
        SongInfoClient.WikiPage otherWork = new SongInfoClient.WikiPage("ja", 4, "エゴママ/恋距離遠愛", "DECO*27 のシングル。");
        SongInfoClient.WikiPage chart = new SongInfoClient.WikiPage("en", 5, "List of number ones of 2020", "Dynamite by BTS topped the chart.");
        assertTrue(SongInfoClient.isRelevant(artist, "チェリーポップ", "DECO*27"));
        assertTrue(SongInfoClient.isRelevant(song, "Dynamite", "BTS"));
        assertFalse(SongInfoClient.isRelevant(otherSong, "Dynamite", "BTS"));
        assertFalse(SongInfoClient.isRelevant(otherWork, "チェリーポップ", "DECO*27"));
        assertTrue(SongInfoClient.isRelevant(chart, "Dynamite", "BTS"));
    }

    @Test
    void picksTheSongPageAsTheMainReference() {
        SongInfoClient.WikiPage artist = new SongInfoClient.WikiPage("en", 1, "BTS", "A South Korean boy band.");
        SongInfoClient.WikiPage song = new SongInfoClient.WikiPage("en", 2, "Dynamite (BTS song)", "A song by BTS.");
        assertSame(song, SongInfoClient.mainPage(List.of(artist, song), "Dynamite", "BTS"));
        assertSame(artist, SongInfoClient.mainPage(List.of(artist), "Dynamite", "BTS"));
        assertNull(SongInfoClient.mainPage(List.of(), "Dynamite", "BTS"));
    }

    @Test
    void choosesLookupRegionsFromTheScript() {
        assertEquals(List.of("ja", "ko"), SongInfoClient.wikiLanguages("kana+latin"));
        assertEquals(List.of("ko", "en"), SongInfoClient.wikiLanguages("hangul"));
        assertEquals(List.of("en", "ko"), SongInfoClient.wikiLanguages("latin"));
        assertEquals("jp", SongInfoClient.catalogCountries("kana").get(0));
        assertEquals("kr", SongInfoClient.catalogCountries("hangul+latin").get(0));
    }
}
