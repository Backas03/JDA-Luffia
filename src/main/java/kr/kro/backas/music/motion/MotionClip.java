package kr.kro.backas.music.motion;

import net.dv8tion.jda.api.utils.FileUpload;

public record MotionClip(byte[] data, String extension) {

    public String fileName() {
        return "motion." + extension;
    }

    public FileUpload upload() {
        return FileUpload.fromData(data, fileName());
    }
}
