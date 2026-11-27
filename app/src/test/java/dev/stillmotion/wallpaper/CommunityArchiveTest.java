package dev.stillmotion.wallpaper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CommunityArchiveTest {
    public static void main(String[] arguments) throws IOException {
        Path work = Files.createTempDirectory("community-archive-test");
        try {
            ByteArrayOutputStream data = sample("stillmotion-v1\nwake\nÓrbita de teste\n", false);
            Path destination = Files.createDirectory(work.resolve("valid"));
            CommunityArchive.unpack(new ByteArrayInputStream(data.toByteArray()), destination.toFile());
            CommunityArchive.Spec spec = CommunityArchive.Spec.read(destination.resolve(CommunityArchive.INFO).toFile());
            require(spec.wake && spec.title.equals("Órbita de teste"), "round-trip of wake theme");
            reject(work, "traversal", "stillmotion-v1\nunlock\nTeste\n", "../animation.mp4");
            reject(work, "unknown-format", "stillmotion-v3\nwake\nTeste\n", "animation.mp4");
            reject(work, "unknown-trigger", "stillmotion-v1\nloop\nTeste\n", "animation.mp4");
            reject(work, "oversized", "stillmotion-v1\nunlock\n" + new String(new char[520]).replace('\0', 'x'),
                    "animation.mp4");
            reject(work, "incomplete", "stillmotion-v1\nunlock\nTeste\n", null);
            reject(work, "missing-after", "stillmotion-v2\nunlock\nTeste\n", "animation.mp4");
            data = sample("stillmotion-v2\nunlock\nCom final\n", true);
            Path two = Files.createDirectory(work.resolve("with-after"));
            CommunityArchive.unpack(new ByteArrayInputStream(data.toByteArray()), two.toFile());
            require(CommunityArchive.Spec.read(two.resolve(CommunityArchive.INFO).toFile()).after
                    && Files.isRegularFile(two.resolve(CommunityArchive.AFTER)), "round-trip of post-unlock theme");
            if (arguments.length != 0) {
                Path external = Files.createDirectory(work.resolve("external"));
                try (java.io.InputStream input = Files.newInputStream(java.nio.file.Paths.get(arguments[0]))) {
                    CommunityArchive.unpack(input, external.toFile());
                }
                require(!CommunityArchive.Spec.read(external.resolve(CommunityArchive.INFO).toFile()).wake,
                        "offline authoring output");
            }
            System.out.println("Community archives: round trip, limits and hostile entries verified");
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static ByteArrayOutputStream sample(String manifest, boolean after) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(CommunityArchive.INFO));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String clip : after ? new String[]{CommunityArchive.CLIP, CommunityArchive.AFTER}
                    : new String[]{CommunityArchive.CLIP}) {
                zip.putNextEntry(new ZipEntry(clip));
                zip.write(1);
                zip.closeEntry();
            }
        }
        return bytes;
    }

    private static void reject(Path work, String reason, String manifest, String clip) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("theme.txt"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (clip != null) {
                zip.putNextEntry(new ZipEntry(clip));
                zip.write(1);
                zip.closeEntry();
            }
        }
        try {
            CommunityArchive.unpack(new ByteArrayInputStream(bytes.toByteArray()),
                    Files.createDirectory(work.resolve(reason)).toFile());
        } catch (IOException rejected) {
            return;
        }
        throw new AssertionError("Archive accepted: " + reason);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
