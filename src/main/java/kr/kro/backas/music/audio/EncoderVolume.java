package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer;
import org.jetbrains.annotations.Nullable;

public final class EncoderVolume {
    private final AudioConfiguration configuration;
    private final Runnable onUnity;
    private volatile int volume;
    private volatile Carried carried;

    private record Carried(AudioFrameBuffer buffer, int volume) {
    }

    public EncoderVolume(AudioConfiguration configuration, int volume, Runnable onUnity) {
        this.configuration = configuration;
        this.volume = volume;
        this.onUnity = onUnity;
    }

    public static boolean supported(AudioConfiguration configuration) {
        return MusicOpusFormat.appliesGain(configuration.getOutputFormat());
    }

    public int get() {
        return volume;
    }

    public void set(int volume) {
        this.volume = volume;
    }

    public boolean neededFor(@Nullable AudioFrameBuffer playing) {
        if (volume != 100) return true;
        Carried known = carried;
        return known != null && playing != null && known.buffer() == playing && known.volume() != 100;
    }

    public double gainFor(@Nullable AudioFrameBuffer buffer) {
        int wanted = volume;
        float gain = EncoderGain.forVolume(wanted);
        if (buffer == null) return gain;
        Carried known = carried;
        boolean tracked = known != null && known.buffer() == buffer;
        int before = tracked ? known.volume() : 100;
        if (tracked && before == wanted) return gain;
        if (before != wanted) rescale(buffer, EncoderGain.forVolume(before), gain);
        carried = new Carried(buffer, wanted);
        if (wanted == 100) onUnity.run();
        return gain;
    }

    private void rescale(AudioFrameBuffer buffer, float before, float after) {
        if (configuration.getOutputFormat() instanceof MusicOpusFormat format) {
            format.rescaleBuffered(buffer, configuration, before, after);
        }
    }
}
