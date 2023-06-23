package kr.kro.backas.util;

import java.io.File;
import java.io.IOException;

public class FileUtil {

    public static File checkAndCreateFile(File file) throws IOException {
        checkAndCreateFolder(file.getParentFile());
        if (!file.exists()) file.createNewFile();
        return file;
    }

    public static void checkAndCreateFolder(File folder) {
        if (!folder.exists()) folder.mkdirs();
    }
}
