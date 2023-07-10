package kr.kro.backas.civilwar.lol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import kr.kro.backas.util.FileUtil;

import java.io.File;
import java.io.IOException;

public class LOLUserDataManager {

    private LOLUserData userData;

    public LOLUserDataManager() throws IOException {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            this.userData = mapper.readValue(
                    FileUtil.checkAndCreateFile(getFile()),
                    LOLUserData.class);
        } catch (MismatchedInputException ignore) {
            this.userData = new LOLUserData();
        }
    }

    public File getFile() {
        return new File("data/lol.yaml");
    }
}
