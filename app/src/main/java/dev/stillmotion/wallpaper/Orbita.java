package dev.stillmotion.wallpaper;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class Orbita {
    static final String GAS_EFFECT_KEY = "orbita_gas_effect";
    static final String AFTER_KEY = "orbita_after_unlock";
    static final String LIBRARY_KEY = "orbita_library";

    private Orbita() {}

    static boolean isOrbita(String id) {
        return id.startsWith("orbita_");
    }

    static Map<String, OrbitaArchive.Spec> catalog(Context context) {
        String id = Theme.preferences(context).getString(LIBRARY_KEY, "");
        if (!id.matches("orbita-[a-zA-Z0-9]{1,40}")) return Collections.emptyMap();
        try {
            return OrbitaArchive.read(new File(context.getFilesDir(), id));
        } catch (IOException invalid) {
            return Collections.emptyMap();
        }
    }

    static OrbitaArchive.Spec find(Context context, String id) {
        return isOrbita(id) ? catalog(context).get(id) : null;
    }

    static void addEntries(Context context, Map<String, OrbitaArchive.Spec> catalog, List<FlymePack.Entry> entries) {
        boolean english = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("en");
        for (OrbitaArchive.Spec theme : catalog.values()) {
            entries.add(new FlymePack.Entry(theme.id, english ? theme.english : theme.portuguese));
        }
    }

    static void install(Context context, ZipInputStream zip, ZipEntry first) throws IOException {
        File staging = Files.createTempDirectory(context.getFilesDir().toPath(), "orbita-").toFile();
        boolean published = false;
        try {
            Map<String, OrbitaArchive.Spec> themes = OrbitaArchive.unpack(zip, first, staging);
            for (OrbitaArchive.Spec theme : themes.values()) {
                for (String stage : new String[]{"wake", "lock", "home"}) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(theme.poster(stage).getPath(), options);
                    if (!"image/webp".equals(options.outMimeType) || options.outWidth < 1
                            || options.outHeight < 1 || options.outWidth > 1080 || options.outHeight > 2340) {
                        throw new IOException("Invalid Orbita poster");
                    }
                }
            }
            android.content.SharedPreferences prefs = Theme.preferences(context);
            String old = prefs.getString(LIBRARY_KEY, "");
            if (!prefs.edit().putString(LIBRARY_KEY, staging.getName()).commit()) {
                // commit() updates the process view even on write failure.
                prefs.edit().putString(LIBRARY_KEY, old).apply();
                throw new IOException("Could not save catalog");
            }
            published = true;
            if (old.matches("orbita-[a-zA-Z0-9]{1,40}")) {
                clear(new File(context.getFilesDir(), old));
            }
        } finally {
            if (!published) clear(staging);
        }
    }

    private static void clear(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) file.delete();
        directory.delete();
    }
}
