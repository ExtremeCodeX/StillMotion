package dev.stillmotion.wallpaper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class OrbitaArchiveTest {
    private static final String ROW = "orbita_sample\tAmostra\tSample\tsingle\t22dd88\t103242\n";

    public static void main(String[] args) throws IOException {
        Path work = Files.createTempDirectory("stillmotion-orbita-tests");
        try {
            reject(work, "traversal", ROW, "../orbita_sample-wake.webp");
            reject(work, "missing", ROW, null);
            reject(work, "duplicate", ROW + ROW, null);
            reject(work, "bad-color", ROW.replace("22dd88", "invalid"), null);
            if (args.length != 0) {
                try (InputStream source = Files.newInputStream(Paths.get(args[0]));
                        ZipInputStream zip = new ZipInputStream(source)) {
                    Path stage = Files.createDirectory(work.resolve("valid"));
                    Map<String, OrbitaArchive.Spec> themes = OrbitaArchive.unpack(zip, zip.getNextEntry(), stage.toFile());
                    if (themes.size() != 6 || !themes.get("orbita_menta").radial
                            || themes.get("orbita_violeta").rim != 0x6c41ff) {
                        throw new AssertionError("Bundled package catalog");
                    }
                }
            }
            System.out.println("Órbita: catálogo, limites e entradas inválidas verificados");
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static void reject(Path work, String caseName, String rows, String file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(OrbitaArchive.INFO));
            zip.write(("stillmotion-orbita-v1\n" + rows).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (file != null) {
                zip.putNextEntry(new ZipEntry(file));
                zip.write(1);
                zip.closeEntry();
            }
        }
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            OrbitaArchive.unpack(zip, zip.getNextEntry(), Files.createDirectory(work.resolve(caseName)).toFile());
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Accepted invalid Orbita pack: " + caseName);
    }
}
