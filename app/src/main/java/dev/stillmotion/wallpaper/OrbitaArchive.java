package dev.stillmotion.wallpaper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OrbitaArchive {
    static final String INFO = "orbita.tsv";
    static final int MAX_THEMES = 24;
    private static final int MAX_INFO = 8192;
    private static final int MAX_POSTER = 4 * 1024 * 1024;
    private static final long MAX_POSTERS_TOTAL = 24L * 1024 * 1024;

    static final class Spec {
        final String id, portuguese, english;
        final int rim, depth;
        final boolean radial;
        private final File directory;

        Spec(File directory, String[] fields) throws IOException {
            this.directory = directory;
            if (fields.length != 6 || !fields[0].matches("orbita_[a-z][a-z0-9_]{0,23}")
                    || !(fields[3].equals("single") || fields[3].equals("double"))) {
                throw new IOException("Invalid Orbita theme");
            }
            id = fields[0];
            portuguese = label(fields[1]);
            english = label(fields[2]);
            radial = fields[3].equals("single");
            rim = color(fields[4]);
            depth = color(fields[5]);
        }

        File poster(String stage) {
            return new File(directory, id + "-" + stage + ".webp");
        }

        private static int color(String value) throws IOException {
            if (!value.matches("[0-9a-fA-F]{6}")) throw new IOException("Invalid Orbita color");
            return Integer.parseInt(value, 16);
        }

        private static String label(String value) throws IOException {
            if (value.isEmpty() || value.length() > 60 || value.codePoints().anyMatch(
                    c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
                throw new IOException("Invalid Orbita label");
            }
            return value;
        }
    }

    private OrbitaArchive() {}

    static Map<String, Spec> read(File directory) throws IOException {
        File file = new File(directory, INFO);
        if (file.length() > MAX_INFO) throw new IOException("Orbita catalog is too large");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file.toPath())))
                .toString();
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 2 || lines.length > MAX_THEMES + 1 || !lines[0].equals("stillmotion-orbita-v1")) {
            throw new IOException("Invalid Orbita catalog");
        }
        Map<String, Spec> themes = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            Spec spec = new Spec(directory, lines[i].split("\\t", -1));
            if (themes.put(spec.id, spec) != null) throw new IOException("Duplicate Orbita theme");
        }
        return themes;
    }

    static Map<String, Spec> unpack(ZipInputStream zip, ZipEntry first, File staging) throws IOException {
        if (first == null || !INFO.equals(first.getName()) || first.isDirectory()) {
            throw new IOException("Orbita catalog must be first");
        }
        CommunityArchive.copy(zip, new File(staging, INFO), MAX_INFO);
        Map<String, Spec> themes = read(staging);
        Map<String, File> posters = new LinkedHashMap<>();
        for (Spec spec : themes.values()) {
            for (String stage : new String[]{"wake", "lock", "home"}) {
                File poster = spec.poster(stage);
                posters.put(poster.getName(), poster);
            }
        }
        long total = 0;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            File poster = posters.remove(entry.getName());
            if (entry.isDirectory() || poster == null) throw new IOException("Unexpected Orbita entry");
            int limit = (int) Math.min(MAX_POSTER, MAX_POSTERS_TOTAL - total);
            CommunityArchive.copy(zip, poster, limit);
            total += poster.length();
        }
        if (!posters.isEmpty()) throw new IOException("Missing Orbita posters");
        return themes;
    }
}
