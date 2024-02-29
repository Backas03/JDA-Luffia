package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;

public class AudioForwarder implements AudioSendHandler {
    private final MusicPlayerClient client;
    private AudioFrame lastFrame;

    public AudioForwarder(MusicPlayerClient client) {
        this.client = client;
    }

    @Override
    public boolean canProvide() {
        lastFrame = client.getAudioPlayer().provide();
        if (lastFrame != null) { // can provide
            client.updatePosition();
            return true;
        }
        return false;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}
