package dev.stillmotion.wallpaper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class CommunityArchive {
    static final String INFO = "theme.txt";
    static final String CLIP = "animation.mp4";
    static final String AFTER = "after.mp4";
    static final int VIDEO_LIMIT = 32 * 1024 * 1024;
    private static final int INFO_LIMIT = 512;

    static final class Spec {
        final String title;
        final boolean wake;
        final boolean after;

        Spec(String title, boolean wake) throws IOException {
            this(title, wake, false);
        }

        Spec(String title, boolean wake, boolean after) throws IOException {
            this.title = title.trim();
            this.wake = wake;
            this.after = after;
            if (wake && after) throw new IOException("Post-unlock needs an unlock clip");
            if (this.title.isEmpty() || this.title.length() > 60
                    || this.title.codePoints().anyMatch(c -> Character.isISOControl(c)
                            || Character.getType(c) == Character.FORMAT)) {
                throw new IOException("Invalid title");
            }
        }

        static Spec read(File file) throws IOException {
            if (file.length() > INFO_LIMIT) throw new IOException("Invalid manifest");
            String[] lines = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").split("\n", -1);
            if (lines.length == 4 && lines[3].isEmpty()) {
                lines = java.util.Arrays.copyOf(lines, 3);
            }
            if (lines.length != 3 || !(lines[0].equals("stillmotion-v1") || lines[0].equals("stillmotion-v2"))
                    || !(lines[1].equals("wake") || lines[1].equals("unlock"))) {
                throw new IOException("Invalid manifest");
            }
            return new Spec(lines[2], lines[1].equals("wake"), lines[0].equals("stillmotion-v2"));
        }


    }

    private CommunityArchive() {}

    static void unpack(InputStream source, File staging) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(source)) {
            unpack(zip, zip.getNextEntry(), staging);
        }
    }

    static void unpack(ZipInputStream zip, ZipEntry first, File staging) throws IOException {
        Set<String> seen = new HashSet<>();
        for (ZipEntry item = first; item != null; item = zip.getNextEntry()) {
            String name = item.getName();
            if (item.isDirectory() || !(INFO.equals(name) || CLIP.equals(name) || AFTER.equals(name))
                    || !seen.add(name)) {
                throw new IOException("Unexpected pack entry");
            }
            copy(zip, new File(staging, name), INFO.equals(name) ? INFO_LIMIT : VIDEO_LIMIT);
        }
        if (!seen.contains(INFO) || !seen.contains(CLIP)) throw new IOException("Incomplete pack");
        Spec spec = Spec.read(new File(staging, INFO));
        if (spec.after != seen.contains(AFTER)) throw new IOException("Invalid post-unlock clip");
    }

    static void copy(InputStream source, File destination, int limit) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        try (OutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("Pack entry too large");
                output.write(buffer, 0, count);
            }
        }
        if (total == 0) throw new IOException("Empty pack entry");
    }

}
