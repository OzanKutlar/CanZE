package lu.fisch.canze.media;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lu.fisch.canze.activities.MainActivity;

/**
 * Resolves album art for a media session. Embedded bitmaps are used directly; URIs are
 * decoded on one background thread and downsampled to the display size. Only the newest
 * requests are queued, so skipping quickly through tracks never piles up work.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class AlbumArtLoader {

    interface Callback {
        void onArtLoaded(String key, Bitmap art);
    }

    private static final String[] BITMAP_KEYS = {
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
    };
    private static final String[] URI_KEYS = {
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
    };
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final int MAX_READS = 65536;
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_SAMPLE_SIZE = 64;
    private static final int MIN_TARGET_PX = 64;
    private static final int MAX_QUEUED = 2;

    private final ContentResolver resolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor executor;
    private final int targetPx;

    AlbumArtLoader(Context context, int targetPx) {
        this.resolver = context.getContentResolver();
        this.targetPx = Math.max(MIN_TARGET_PX, targetPx);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(MAX_QUEUED), new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "CanZE-AlbumArt");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    }
                }, new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /** The first bitmap embedded in the metadata, or null. */
    static Bitmap embedded(MediaMetadata metadata) {
        if (metadata == null) return null;
        for (String key : BITMAP_KEYS) {
            Bitmap bitmap = metadata.getBitmap(key);
            if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        }
        return null;
    }

    /** The first art URI in the metadata, or null. */
    static String uriOf(MediaMetadata metadata) {
        if (metadata == null) return null;
        for (String key : URI_KEYS) {
            String uri = metadata.getString(key);
            if (uri != null && uri.length() > 0) return uri;
        }
        return null;
    }

    /** Decodes the URI off the main thread and reports back on the main thread. */
    void load(final String key, final String uri, final Callback callback) {
        if (key == null || uri == null || callback == null || executor.isShutdown()) return;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                deliver(key, decode(uri), callback);
            }
        });
    }

    void shutdown() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void deliver(final String key, final Bitmap art, final Callback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onArtLoaded(key, art);
            }
        });
    }

    private Bitmap decode(String uri) {
        byte[] data = fetch(uri);
        if (data == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            debug("art is not a decodable image: " + uri);
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (OutOfMemoryError e) {
            debug("out of memory decoding art: " + uri);
            return null;
        }
    }

    private int sampleSize(int width, int height) {
        int shortest = Math.min(width, height);
        int sample = 1;
        while (sample < MAX_SAMPLE_SIZE && shortest / (sample * 2) >= targetPx) sample *= 2;
        return sample;
    }

    private byte[] fetch(String uri) {
        InputStream in = null;
        HttpURLConnection http = null;
        try {
            Uri parsed = Uri.parse(uri);
            String scheme = parsed.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                http = (HttpURLConnection) new URL(uri).openConnection();
                http.setConnectTimeout(TIMEOUT_MS);
                http.setReadTimeout(TIMEOUT_MS);
                in = http.getInputStream();
            } else {
                in = resolver.openInputStream(parsed);
            }
            return in == null ? null : readBounded(in);
        } catch (IOException e) {
            debug("cannot read art " + uri + ": " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // SecurityException / IllegalArgumentException from foreign content providers.
            debug("art not accessible " + uri + ": " + e.getMessage());
            return null;
        } finally {
            closeQuietly(in);
            if (http != null) http.disconnect();
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_BYTES);
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        for (int reads = 0; reads < MAX_READS; reads++) {
            int count = in.read(buffer);
            if (count < 0) return out.toByteArray();
            total += count;
            if (total > MAX_BYTES) {
                debug("art larger than " + MAX_BYTES + " bytes, skipped");
                return null;
            }
            out.write(buffer, 0, count);
        }
        debug("art stream did not end, skipped");
        return null;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException e) {
            debug("cannot close art stream: " + e.getMessage());
        }
    }

    private static void debug(String message) {
        MainActivity.debug("Media: " + message);
    }
}
