package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
