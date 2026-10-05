package kr.kro.backas;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildInfo {

    public static final String NAME = "Luffia";
    public static final String VERSION = NAME + "/" + readVersion();

    private BuildInfo() {
    }

    private static String readVersion() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version", "").trim();
                if (!version.isEmpty() && !version.startsWith("${")) return version;
            }
        } catch (IOException ignored) {
        }
        return "dev";
    }
}
