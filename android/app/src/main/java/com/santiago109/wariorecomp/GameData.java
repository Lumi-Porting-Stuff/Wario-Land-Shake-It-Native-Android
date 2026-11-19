package com.santiago109.wariorecomp;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The extracted disc the engine reads, and the one setting the engine needs to find it.
 *
 * <p>The engine opens the disc with ordinary file calls, so it cannot read a folder the user picked
 * through the document picker (those are content:// URIs, not paths). The folder therefore lives in
 * the app's own external storage, which is both a real path for the engine and reachable over USB
 * for the user: {@code Android/data/com.santiago109.wariorecomp/files/game}. A user who copies it
 * there themselves skips the import entirely.
 *
 * <p>The checks mirror runtime/src/game_folder.cpp so the app rejects the wrong disc with the same
 * explanations the PC build gives, instead of letting the engine fail later.
 */
final class GameData {

    /** The game this port runs: Wario Land: Shake It!, USA, revision 0. */
    private static final String GAME_ID = "RWLE01";
    private static final int REVISION = 0;

    /** The folder on shared storage the game is meant to live in: /sdcard/WarioRecomp/game. */
    private static final String SHARED_FOLDER = "WarioRecomp/game";

    private GameData() {
    }

    /**
     * Where the game is kept: {@code /sdcard/WarioRecomp/game}.
     *
     * <p>Plainly visible in any file manager and reachable over USB, so the disc can be put there
     * once and left alone. Reading it needs all-files access ({@link #hasSharedAccess}), because
     * the engine opens the disc as a path rather than through the document picker.
     */
    static File sharedRoot() {
        return new File(android.os.Environment.getExternalStorageDirectory(), SHARED_FOLDER);
    }

    /**
     * The fallback inside the app's own folder, which needs no permission at all:
     * {@code Android/data/com.santiago109.wariorecomp/files/game}.
     */
    static File privateRoot(Context context) {
        return new File(dataDirectory(context), "game");
    }

    /**
     * Whether the app may read files outside its own folder, which is what
     * {@link #sharedRoot} requires.
     *
     * <p>From Android 11 that is the all-files access the user grants in Settings; before it, the
     * old read permission does the same job.
     */
    static boolean hasSharedAccess(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The game folder the engine should read, or null when there is none yet.
     *
     * <p>The shared folder wins, since that is the one the user put the disc in on purpose; the
     * app's private folder is still accepted so an import, or a copy made before the permission was
     * granted, keeps working.
     */
    static File resolve(Context context) {
        if (hasSharedAccess(context)) {
            File shared = sharedRoot();
            if (problem(shared) == null) {
                return shared;
            }
        }
        File priv = privateRoot(context);
        return problem(priv) == null ? priv : null;
    }

    /**
     * The engine's own state: Config.toml, saves, caches and logs (WIICOMPILED_DATA_DIR).
     *
     * <p>Always the app's external files directory, whatever the disc's folder is: writing there
     * needs no permission and it survives as the app's own. It is null while storage is unavailable
     * — an ejected or still-mounting SD card, say — and internal storage stands in rather than
     * crashing; the game then works, but the folder cannot be reached over USB.
     */
    static File dataDirectory(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    /** True when a folder the engine will accept is in place. */
    static boolean isReady(Context context) {
        return resolve(context) != null;
    }

    /**
     * Where an import should put the disc: the shared folder when the app may read it, and its own
     * folder otherwise. Either way the engine can open it afterwards.
     */
    static File importTarget(Context context) {
        // Being allowed to read shared storage is not being able to write it: Android 10 grants
        // the read permission alone, and some ROMs report all-files access while still refusing
        // writes. So the shared folder is tried rather than assumed, and the app's own folder,
        // which needs no permission, takes over when it cannot be written.
        if (hasSharedAccess(context) && isWritable(sharedRoot())) {
            return sharedRoot();
        }
        return privateRoot(context);
    }

    /** True when a file can actually be created in {@code directory}, creating it if need be. */
    private static boolean isWritable(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return false;
        }
        File probe = new File(directory, ".write-test");
        try (OutputStream output = new java.io.FileOutputStream(probe)) {
            output.write(0);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            probe.delete();
        }
    }

    /**
     * Replaces {@code file} with {@code bytes} through a temporary file and a rename.
     *
     * <p>Opening the file itself for writing fails with "Permission denied" when it belongs to
     * somebody else - a Config.toml pushed with adb, or left by an earlier install - even though
     * the folder is this app's. Renaming over it only needs the folder, and it also means a write
     * that is interrupted cannot leave half a file behind.
     */
    static void replaceFile(File file, byte[] bytes) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (OutputStream output = new java.io.FileOutputStream(temp)) {
                output.write(bytes);
            }
            if (temp.renameTo(file)) {
                return;
            }
        } catch (IOException e) {
            // The folder refused a new file; the file itself may still accept a write.
        }
        temp.delete();
        try (OutputStream output = new java.io.FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    /**
     * The extracted disc at or just below {@code path}, or null. Dolphin writes either the disc
     * itself or a DATA folder holding it, and both are accepted (as in GameFolder::Find).
     */
    static File find(File path) {
        if (path == null) {
            return null;
        }
        if (isExtractedDisc(path)) {
            return path;
        }
        File data = new File(path, "DATA");
        return isExtractedDisc(data) ? data : null;
    }

    private static boolean isExtractedDisc(File path) {
        return new File(path, "files").isDirectory() && new File(path, "sys/fst.bin").isFile();
    }

    /**
     * What is wrong with {@code root} as a game folder, in words meant for the user, or null when
     * it is the right disc.
     */
    static String problem(File root) {
        if (root == null || !isExtractedDisc(root)) {
            return "That folder isn't an extracted game: it needs the files and sys folders"
                    + " Dolphin's Extract Entire Disc makes.";
        }
        byte[] header = new byte[8];
        try (InputStream input = new FileInputStream(new File(root, "sys/boot.bin"))) {
            int read = 0;
            while (read < header.length) {
                int step = input.read(header, read, header.length - read);
                if (step < 0) {
                    break;
                }
                read += step;
            }
            if (read < header.length) {
                return "That folder has no sys/boot.bin, so the game can't be identified.";
            }
        } catch (IOException e) {
            return "That folder has no sys/boot.bin, so the game can't be identified.";
        }
        String id = new String(header, 0, 6, StandardCharsets.US_ASCII);
        int revision = header[7] & 0xFF;
        if (!GAME_ID.equals(id)) {
            return "That is " + id + ", not Wario Land: Shake It! " + GAME_ID
                    + ". This engine runs only that disc.";
        }
        if (revision != REVISION) {
            return "That is Wario Land: Shake It! revision " + revision
                    + ". This port runs only revision 0 (Dolphin shows it under Properties > Info).";
        }
        return null;
    }

    /**
     * Records the game folder in the engine's Config.toml, leaving every other setting alone. This
     * is the Android counterpart of GameFolder::EnsureConfigured, which on this platform trusts the
     * app to have chosen and checked the folder.
     */
    static void writeDvdRoot(Context context, File dvdRoot) throws IOException {
        File directory = dataDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory);
        }
        File config = new File(directory, "Config.toml");
        String entry = "dvd_root = \"" + escape(dvdRoot.getAbsolutePath()) + "\"";

        List<String> lines = config.isFile() ? readLines(config) : new ArrayList<>();
        boolean inPaths = false;
        boolean replaced = false;
        for (int i = 0; i < lines.size() && !replaced; i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[")) {
                // Leaving [paths] without having found the key: put it at the end of that section.
                if (inPaths) {
                    lines.add(i, entry);
                    replaced = true;
                    break;
                }
                inPaths = trimmed.toLowerCase(Locale.ROOT).startsWith("[paths]");
            } else if (inPaths && trimmed.replace(" ", "").startsWith("dvd_root=")) {
                lines.set(i, entry);
                replaced = true;
            }
        }
        if (!replaced) {
            if (inPaths) {
                lines.add(entry);
            } else {
                if (!lines.isEmpty()) {
                    lines.add("");
                }
                lines.add("[paths]");
                lines.add(entry);
            }
        }

        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        replaceFile(config, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** TOML basic strings take backslash escapes, so a path's own backslashes must be doubled. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
