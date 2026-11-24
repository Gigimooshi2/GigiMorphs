package app.morphe.extension.reddit.patches;

import com.reddit.domain.model.PlaybackMp4Permutation;
import com.reddit.domain.model.PlaybackMp4s;
import com.reddit.domain.model.VideoUrls;

import java.util.List;

@SuppressWarnings("unused")
public final class GigiVideo {
    // The app supplies directly playable MP4s for some clips. Longer videos benefit from adaptive quality.
    public static String startupStream(VideoUrls urls) {
        if (urls == null || urls.getLocalUri() != null) return null;
        PlaybackMp4s mp4s = urls.getPlaybackMp4s();
        if (mp4s == null || mp4s.getDurationSeconds() <= 0 || mp4s.getDurationSeconds() > 90) return null;
        List<PlaybackMp4Permutation> choices = mp4s.getPermutations();
        if (choices == null) return null;

        int best = 0;
        String selected = null;
        for (PlaybackMp4Permutation choice : choices) {
            if (choice == null || !"h264".equalsIgnoreCase(choice.getVideoCodecRaw())) continue;
            int shortSide = Math.min(choice.getWidth(), choice.getHeight());
            if (shortSide <= best || shortSide > 720) continue;
            String url = choice.getUrl();
            if (url == null || !url.startsWith("https://")) continue;
            best = shortSide;
            selected = url;
        }
        return selected;
    }
}
