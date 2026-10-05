package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtworkColorsTest {

    private static BufferedImage image(int width, int height, Color fill) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, fill.getRGB());
        }
        return image;
    }

    @Test
    void picksTheMostCommonVividColorOverGreysAndBlacks() {
        BufferedImage image = image(100, 100, Color.BLACK);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 30; x++) image.setRGB(x, y, new Color(200, 30, 40).getRGB());
            for (int x = 30; x < 45; x++) image.setRGB(x, y, new Color(40, 60, 220).getRGB());
            for (int x = 45; x < 60; x++) image.setRGB(x, y, Color.GRAY.getRGB());
        }
        Color color = ArtworkColors.dominant(image);
        assertTrue(color.getRed() > color.getBlue() && color.getRed() > color.getGreen(), color.toString());
    }

    @Test
    void fallsBackToTheAverageWhenNothingIsVivid() {
        Color color = ArtworkColors.dominant(image(20, 20, new Color(120, 120, 120)));
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        assertTrue(hsb[2] >= 0.45f, color.toString());
    }

    @Test
    void displayableColorsAreNeverTooDarkToSeeOnTheSidebar() {
        Color color = ArtworkColors.displayable(new Color(30, 10, 10));
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        assertTrue(hsb[2] >= 0.45f, color.toString());
        assertEquals(MusicEmbeds.PRIMARY, ArtworkColors.of(null));
    }

    @Test
    void squareArtworkBecomesASixteenByNineBannerWithTheArtCentred() throws Exception {
        byte[] banner = ArtworkColors.composeBanner(image(100, 100, new Color(200, 30, 40)));
        BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(banner));
        assertEquals(ArtworkColors.BANNER_WIDTH, decoded.getWidth());
        assertEquals(ArtworkColors.BANNER_HEIGHT, decoded.getHeight());
        Color centre = new Color(decoded.getRGB(ArtworkColors.BANNER_WIDTH / 2, ArtworkColors.BANNER_HEIGHT / 2));
        Color edge = new Color(decoded.getRGB(5, ArtworkColors.BANNER_HEIGHT / 2));
        assertTrue(centre.getRed() > 150, centre.toString());
        assertTrue(edge.getRed() < centre.getRed(), edge + " vs " + centre);
    }

    private static void noise(BufferedImage image, int x0, int y0, int x1, int y1, long seed) {
        java.util.Random random = new java.util.Random(seed);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) image.setRGB(x, y, new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)).getRGB());
        }
    }

    @Test
    void flatBandsOnAnySideAreCroppedAway() {
        BufferedImage pillarboxed = image(960, 540, new Color(60, 20, 20));
        noise(pillarboxed, 210, 0, 750, 540, 7);
        BufferedImage cover = ArtworkColors.cropFlatBands(pillarboxed);
        assertEquals(540, cover.getWidth());
        assertEquals(540, cover.getHeight());

        BufferedImage letterboxed = image(1280, 720, Color.BLACK);
        noise(letterboxed, 0, 140, 1280, 580, 8);
        BufferedImage wide = ArtworkColors.cropFlatBands(letterboxed);
        assertEquals(1280, wide.getWidth());
        assertEquals(440, wide.getHeight());

        BufferedImage photo = image(960, 540, Color.BLACK);
        noise(photo, 0, 0, 960, 540, 9);
        assertTrue(ArtworkColors.cropFlatBands(photo) == photo);
    }

    @Test
    void croppedWideArtworkIsScaledToFillTheBanner() throws Exception {
        BufferedImage ultraWide = image(1200, 300, new Color(30, 160, 60));
        BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(ArtworkColors.coverBanner(ultraWide)));
        assertEquals(ArtworkColors.BANNER_WIDTH, decoded.getWidth());
        assertEquals(ArtworkColors.BANNER_HEIGHT, decoded.getHeight());
        Color corner = new Color(decoded.getRGB(2, 2));
        assertTrue(corner.getGreen() > 120, corner.toString());
    }

    @Test
    void missingMaxResThumbnailsFallBackToSmallerYoutubeSizes() {
        assertEquals(java.util.List.of(
                        "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
                        "https://i.ytimg.com/vi/abc/sddefault.jpg",
                        "https://i.ytimg.com/vi/abc/hqdefault.jpg"),
                ArtworkColors.candidates("https://i.ytimg.com/vi/abc/maxresdefault.jpg"));
        assertEquals(java.util.List.of("https://x/cover.png"), ArtworkColors.candidates("https://x/cover.png"));
        assertFalse(ArtworkColors.candidates("https://x/cover.png").isEmpty());
    }
}
