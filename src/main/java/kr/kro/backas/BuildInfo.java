package kr.kro.backas;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildInfo {

    public static final String NAME = "Luffia";
    public static final String VERSION = NAME + "/" + readVersion();
    public static final String AUTHOR = "Luffia by Backas03";
    public static final String SOURCE_URL = "https://github.com/Backas03/JDA-Luffia";
    public static final String LICENSE = "Apache-2.0 + 추가 조건";

    public static String credits() {
        return AUTHOR + "\n" + SOURCE_URL + "\n" + LICENSE;
    }

    public static String versionLink() {
        return "[" + VERSION + "](" + SOURCE_URL + ")";
    }

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
