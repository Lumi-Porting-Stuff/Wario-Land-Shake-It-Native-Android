package com.santiago109.wariorecomp;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies an extracted disc from a folder the user picked into the app's own storage.
 *
 * <p>The copy is not a convenience: the engine reads the disc with ordinary file calls, and a
 * folder chosen through the document picker is a content:// URI that no such call can open. Users
 * who would rather not spend the disk space can put the folder in
 * {@code Android/data/com.santiago109.wariorecomp/files/game} over USB instead, and the app will
 * use it as it is.
 */
final class DiscImport {

    /** Progress for the UI, called from the import thread. */
    interface Listener {
        void onProgress(long copiedBytes, long totalBytes, String currentFile);
    }

    private final Context context;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    DiscImport(Context context) {
        this.context = context;
    }

    void cancel() {
        cancelled.set(true);
    }

    /** True when {@link #cancel} stopped the import, so its message is not a failure to report. */
    boolean wasCancelled() {
        return cancelled.get();
    }

    /**
     * Copies the disc under {@code treeUri} into the game folder and records it in Config.toml.
     *
     * @return null on success, or what to tell the user.
     */
    String run(Uri treeUri, Listener listener) {
        DocumentFile picked = DocumentFile.fromTreeUri(context, treeUri);
        if (picked == null) {
            return "That folder could not be opened.";
        }
        DocumentFile source = findDisc(picked);
        if (source == null) {
            return "That folder isn't an extracted game: it needs the files and sys folders"
                    + " Dolphin's Extract Entire Disc makes.";
        }

        File target = GameData.importTarget(context);
        // A previous half-finished import would otherwise be mistaken for a complete disc.
        RuntimeFiles.deleteRecursively(target);
        if (!target.mkdirs()) {
            return "Cannot create " + target;
        }

        try {
            long total = totalBytes(source);
            copyDirectory(source, target, new long[]{0}, total, listener);
        } catch (Cancelled e) {
            RuntimeFiles.deleteRecursively(target);
            return "The import was stopped.";
        } catch (IOException e) {
            RuntimeFiles.deleteRecursively(target);
            return "The copy failed: " + e.getMessage();
        }

        String problem = GameData.problem(target);
        if (problem != null) {
            return problem;
        }
        // Not a failure of the import: the disc is in place by now, and the launcher records the
        // folder again every time PLAY is pressed (LauncherActivity.play), where a Config.toml
        // that really cannot be written is reported for what it is.
        try {
            GameData.writeDvdRoot(context, target);
        } catch (IOException e) {
            android.util.Log.w("WarioRecomp", "could not record the game folder yet", e);
        }
        return null;
    }

    /** The disc itself, or the DATA folder holding it, as GameFolder::Find accepts both. */
    private DocumentFile findDisc(DocumentFile path) {
        if (isExtractedDisc(path)) {
            return path;
        }
        DocumentFile data = path.findFile("DATA");
        return data != null && isExtractedDisc(data) ? data : null;
    }

    private boolean isExtractedDisc(DocumentFile path) {
        DocumentFile files = path.findFile("files");
        DocumentFile sys = path.findFile("sys");
        if (files == null || !files.isDirectory() || sys == null || !sys.isDirectory()) {
            return false;
        }
        DocumentFile fst = sys.findFile("fst.bin");
        return fst != null && fst.isFile();
    }

    private long totalBytes(DocumentFile directory) throws IOException {
        long total = 0;
        List<DocumentFile> pending = new ArrayList<>();
        pending.add(directory);
        while (!pending.isEmpty()) {
            throwIfCancelled();
            DocumentFile current = pending.remove(pending.size() - 1);
            for (DocumentFile child : current.listFiles()) {
                if (child.isDirectory()) {
                    pending.add(child);
                } else {
                    total += child.length();
                }
            }
        }
        return total;
    }

    private void copyDirectory(DocumentFile source, File target, long[] copied, long total,
                               Listener listener) throws IOException {
        for (DocumentFile child : source.listFiles()) {
            throwIfCancelled();
            String name = child.getName();
            if (name == null || name.equals(".") || name.equals("..") || name.contains("/")) {
                continue;
            }
            File destination = new File(target, name);
            if (child.isDirectory()) {
                if (!destination.isDirectory() && !destination.mkdirs()) {
                    throw new IOException("Cannot create " + destination);
                }
                copyDirectory(child, destination, copied, total, listener);
            } else {
                copyFile(child, destination, copied, total, listener, name);
            }
        }
    }

    private void copyFile(DocumentFile source, File target, long[] copied, long total,
                          Listener listener, String name) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        byte[] buffer = new byte[512 * 1024];
        try (InputStream input = resolver.openInputStream(source.getUri());
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IOException("Cannot read " + name);
            }
            long sinceReport = 0;
            for (int read = input.read(buffer); read > 0; read = input.read(buffer)) {
                throwIfCancelled();
                output.write(buffer, 0, read);
                copied[0] += read;
                sinceReport += read;
                // Reporting every buffer would spend more time on the UI thread than on the copy.
                if (sinceReport >= 8L * 1024 * 1024) {
                    sinceReport = 0;
                    listener.onProgress(copied[0], total, name);
                }
            }
        }
        listener.onProgress(copied[0], total, name);
    }

    private void throwIfCancelled() throws Cancelled {
        if (cancelled.get()) {
            throw new Cancelled();
        }
    }

    /** Distinguishes "the user stopped it" from a real failure. */
    private static final class Cancelled extends IOException {
    }
}
