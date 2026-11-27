package dev.stillmotion.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class CommunityPack {
    private static final int LIMIT = 32;
    private static final String PREFIX = "custom_";

    private CommunityPack() {}

    static boolean isCustom(String id) {
        return id.startsWith(PREFIX);
    }

    private static File root(Context context) {
        return new File(context.getFilesDir(), "community");
    }

    private static File directory(Context context, String id) {
        if (!id.matches("custom_[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid pack ID");
        return new File(root(context), id);
    }

    static void addEntries(Context context, List<FlymePack.Entry> entries) {
        String[] ids = root(context).list((directory, name) -> name.matches("custom_[0-9a-f]{32}"));
        if (ids == null) return;
        Arrays.sort(ids);
        for (String id : ids) {
            CommunityArchive.Spec spec = spec(context, id);
            if (spec != null) entries.add(new FlymePack.Entry(id, spec.title));
        }
    }

    static CommunityArchive.Spec spec(Context context, String id) {
        if (!isCustom(id)) return null;
        try {
            return CommunityArchive.Spec.read(new File(directory(context, id), CommunityArchive.INFO));
        } catch (IOException | IllegalArgumentException unavailable) {
            return null;
        }
    }

    static File clip(Context context, String id) {
        return new File(directory(context, id), CommunityArchive.CLIP);
    }

    static File afterClip(Context context, String id) {
        return new File(directory(context, id), CommunityArchive.AFTER);
    }

    static File poster(Context context, String id, boolean first) {
        return new File(directory(context, id), first ? "first.webp" : "last.webp");
    }

    static synchronized void install(Context context, Uri input) throws IOException {
        try (InputStream source = context.getContentResolver().openInputStream(input)) {
            if (source == null) throw new IOException("File unavailable");
            try (ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry first = zip.getNextEntry();
                if (first != null && OrbitaArchive.INFO.equals(first.getName())) Orbita.install(context, zip, first);
                else installCommunity(context, zip, first);
            }
        }
    }

    private static void installCommunity(Context context, ZipInputStream zip, ZipEntry first) throws IOException {
        File root = root(context);
        Files.createDirectories(root.toPath());
        String[] installed = root.list((parent, name) -> name.startsWith(PREFIX));
        if (installed == null || installed.length >= LIMIT) throw new IOException("Community library is full");
        File staging = Files.createTempDirectory(root.toPath(), ".import-").toFile();
        try {
            File clip = new File(staging, CommunityArchive.CLIP);
            CommunityArchive.unpack(zip, first, staging);
            CommunityArchive.Spec spec = CommunityArchive.Spec.read(new File(staging, CommunityArchive.INFO));
            long duration = validate(clip);
            File end = spec.after ? new File(staging, CommunityArchive.AFTER) : clip;
            long endDuration = spec.after ? validate(end) : duration;
            if (spec.after && (duration + endDuration > 14_100_000
                    || clip.length() + end.length() > CommunityArchive.VIDEO_LIMIT)) {
                throw new IOException("Combined clips exceed limits");
            }
            createPosters(clip, end, staging, endDuration);
            String id = PREFIX + UUID.randomUUID().toString().replace("-", "");
            if (!staging.renameTo(directory(context, id))) throw new IOException("Could not publish pack");
        } finally {
            File[] files = staging.listFiles();
            if (files != null) {
                for (File file : files) Files.deleteIfExists(file.toPath());
                Files.deleteIfExists(staging.toPath());
            }
        }
    }

    private static long validate(File clip) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(clip.getPath());
            if (extractor.getTrackCount() != 1) throw new IOException("Expected one silent video track");
            MediaFormat format = extractor.getTrackFormat(0);
            long duration = VideoClip.durationIfSupported(format);
            if (duration < 250_000 || !format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                throw new IOException("Expected an AVC clip with 0.25 to 14 seconds");
            }
            int fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
            if (fps < 1 || fps > 30) throw new IOException("Expected up to 30 frames per second");
            if (format.containsKey(MediaFormat.KEY_ROTATION)
                    && format.getInteger(MediaFormat.KEY_ROTATION) != 0) {
                throw new IOException("Apply rotation before import");
            }
            extractor.selectTrack(0);
            int samples = 0;
            while (extractor.getSampleTime() >= 0) {
                if (++samples > Math.ceil(duration * 30 / 1_000_000.0) + 1) {
                    throw new IOException("Clip contains too many frames");
                }
                if (!extractor.advance()) break;
            }
            if (samples < 2) throw new IOException("Expected an animated clip");
            return duration;
        } finally {
            extractor.release();
        }
    }

    private static void createPosters(File clip, File last, File staging, long duration) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(clip.getPath());
            savePoster(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST),
                    new File(staging, "first.webp"));
            if (!clip.equals(last)) retriever.setDataSource(last.getPath());
            String frameCount = Build.VERSION.SDK_INT >= 28
                    ? retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT) : null;
            Bitmap end = null;
            if (Build.VERSION.SDK_INT >= 28 && frameCount != null) {
                int count = Integer.parseInt(frameCount);
                if (count >= 2 && count <= 424) {
                    try {
                        end = retriever.getFrameAtIndex(count - 1);
                    } catch (IllegalArgumentException unavailable) {
                        // Some extractors expose the frame count without index seeking.
                    }
                }
            }
            if (end == null) {
                end = retriever.getFrameAtTime(duration - 1, MediaMetadataRetriever.OPTION_CLOSEST);
            }
            savePoster(end, new File(staging, "last.webp"));
        } finally {
            retriever.release();
        }
    }

    @SuppressWarnings("deprecation")
    private static void savePoster(Bitmap bitmap, File output) throws IOException {
        if (bitmap == null) throw new IOException("Could not decode a poster");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP;
            if (!bitmap.compress(format, 100, stream)) throw new IOException("Could not save poster");
        } finally {
            bitmap.recycle();
        }
    }
}
