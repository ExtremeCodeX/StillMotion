package dev.stillmotion.wallpaper;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FlymePack {
    private static final String[] VIDEO_LABELS = {"Oriental White", "Sunset Danxia", "Bloom and Fade", "Rebirth Vitality"};
    private static final String VIDEOS = "flyme-videos-v1";
    private static final String SCENES = "flyme-scenes-v1";

    static final class Entry {
        final String id;
        final String label;

        Entry(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private FlymePack() {}

    static File file(Context context, String id, String extension) {
        if (id == null || !id.matches("[a-z0-9]+_[a-z0-9]{1,12}")) id = id(0);
        if (id.startsWith("flyme_")) return new File(new File(context.getFilesDir(), VIDEOS), id + extension);
        if (id.startsWith("bubble_")) {
            File corrected = new File(context.getFilesDir(), "flyme-bubble-v2");
            if (corrected.isDirectory()) return new File(corrected, id + extension);
        }
        File combined = new File(new File(context.getFilesDir(), SCENES), id + extension);
        if (combined.isFile()) return combined;
        File family = new File(context.getFilesDir(), familyDirectory(id.substring(0, id.indexOf('_'))));
        return new File(family, id + extension);
    }

    static boolean contains(Context context, String id) {
        return file(context, id, ".mp4").isFile() && file(context, id, ".webp").isFile();
    }

    static String id(int index) {
        return "flyme_" + (index + 1);
    }

    static List<Entry> installed(Context context, Map<String, OrbitaArchive.Spec> orbitaThemes) {
        List<Entry> installed = new ArrayList<>();
        Orbita.addEntries(context, orbitaThemes, installed);
        CommunityPack.addEntries(context, installed);
        videoLabels().forEach((id, name) -> {
            if (contains(context, id)) installed.add(new Entry(id, name));
        });
        sceneLabels(context).forEach((id, name) -> {
            if (contains(context, id)) installed.add(new Entry(id, name));
        });
        return installed;
    }

    private static Map<String, String> videoLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (int index = 0; index < VIDEO_LABELS.length; index++) {
            labels.put(id(index), VIDEO_LABELS[index]);
        }
        return labels;
    }

    private static Map<String, String> sceneLabels(Context context) {
        Map<String, String> labels = new LinkedHashMap<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(R.raw.flyme_scenes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] columns = line.split("\\t", 4);
                labels.put(columns[0], columns[1] + " · " + columns[2]);
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not read Flyme catalog", impossible);
        }
        return labels;
    }

    private static String familyDirectory(String family) {
        return "flyme-" + family + "-v1";
    }
}
