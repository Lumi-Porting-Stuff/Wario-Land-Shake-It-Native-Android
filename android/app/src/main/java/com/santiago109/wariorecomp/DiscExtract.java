package com.santiago109.wariorecomp;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Turns a disc image the user picked into the files/ and sys/ folders the engine reads.
 *
 * <p>This is the same job the PC launcher gives nodtool, done by the library nodtool is built on,
 * linked into the app as libdiscextract.so. A phone has no desktop executable to shell out to.
 *
 * <p>The image is handed over as a file descriptor rather than a path, which is what makes this
 * worth having: the document picker returns a {@code content://} URI that no path-based reader can
 * open, and the extractor reads the image by absolute offset, so nothing has to be copied first.
 * A 4.7 GB disc is read where it lies - on an SD card, in Downloads, wherever the user keeps it.
 *
 * @see DiscImport for the other route, which copies a disc somebody else already extracted.
 */
final class DiscExtract {

    static {
        System.loadLibrary("discextract");
    }

    /** Progress for the UI, called from the extraction thread. */
    interface Listener {
        /**
         * @param done  bytes written so far
         * @param total bytes the disc's file table says there are
         * @param file  the file being written, as a path inside the disc
         */
        void onProgress(long done, long total, String file);

        /** Checked between files; true stops the extraction and leaves nothing behind. */
        boolean isCancelled();
    }

    /**
     * @param fd       the image, already detached from its ParcelFileDescriptor; closed by this
     * @param destDir  the folder to write files/ and sys/ into
     * @param listener progress and cancellation
     * @return null when the disc is out, an empty string when the listener cancelled, or what to
     *         tell the user
     */
    private static native String nativeExtract(int fd, String destDir, Listener listener);

    private final Context context;
    private volatile boolean cancelled;

    DiscExtract(Context context) {
        this.context = context;
    }

    void cancel() {
        cancelled = true;
    }

    /** True when {@link #cancel} stopped it, so its message is not a failure to report. */
    boolean wasCancelled() {
        return cancelled;
    }

    /**
     * Extracts {@code image} into the game folder and records it in Config.toml.
     *
     * @return null on success, or what to tell the user.
     */
    String run(Uri image, Listener listener) {
        File target = GameData.importTarget(context);
        // A previous half-finished extraction would otherwise be mistaken for a complete disc.
        RuntimeFiles.deleteRecursively(target);
        if (!target.mkdirs()) {
            return "Cannot create " + target;
        }

        String problem;
        try (ParcelFileDescriptor descriptor =
                     context.getContentResolver().openFileDescriptor(image, "r")) {
            if (descriptor == null) {
                RuntimeFiles.deleteRecursively(target);
                return "That file could not be opened.";
            }
            // Detached, because the native side closes it: leaving it attached would close it
            // twice, and on Android that can take an unrelated file down with it.
            problem = nativeExtract(descriptor.detachFd(), target.getAbsolutePath(),
                    new Listener() {
                        @Override
                        public void onProgress(long done, long total, String file) {
                            listener.onProgress(done, total, file);
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled || listener.isCancelled();
                        }
                    });
        } catch (FileNotFoundException e) {
            RuntimeFiles.deleteRecursively(target);
            return "That file could not be opened.";
        } catch (IOException e) {
            RuntimeFiles.deleteRecursively(target);
            return "The extraction failed: " + e.getMessage();
        }

        if (problem != null) {
            RuntimeFiles.deleteRecursively(target);
            // Empty means the user cancelled, which needs no message.
            return problem.isEmpty() ? "The extraction was stopped." : problem;
        }

        // The same check the folder import makes: the right game, at the right revision.
        String wrongDisc = GameData.problem(target);
        if (wrongDisc != null) {
            RuntimeFiles.deleteRecursively(target);
            return wrongDisc;
        }
        // Not a failure of the extraction: the disc is in place by now, and the launcher records the
        // folder again every time PLAY is pressed (LauncherActivity.play), where a Config.toml
        // that really cannot be written is reported for what it is.
        try {
            GameData.writeDvdRoot(context, target);
        } catch (IOException e) {
            android.util.Log.w("WarioRecomp", "could not record the game folder yet", e);
        }
        return null;
    }
}
