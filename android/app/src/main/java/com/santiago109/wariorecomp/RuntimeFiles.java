package com.santiago109.wariorecomp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The files a desktop build keeps beside its executable. There is no executable directory inside an
 * APK, so they ship as assets and are unpacked once into the app's own storage; the engine reads
 * them from there through WIICOMPILED_RUNTIME_DIR (runtime/src/platform/host_platform.cpp).
 *
 * <p>The layout under {@code assets/runtime/} is the one the engine looks for beside an executable:
 * {@code dsp_coef.bin}, {@code cacert.pem} and {@code initial_pipeline_cache.db} loose, and the Wii
 * bootstrap NAND under {@code wii_bootstrap/shared2/wc24/}. tools/build-wario-android.ps1 assembles
 * it from runtime/assets.
 */
final class RuntimeFiles {

    private static final String TAG = "WarioRecomp";
    private static final String ASSET_ROOT = "runtime";
    private static final String STAMP_NAME = ".unpacked";

    private RuntimeFiles() {
    }

    /** The unpacked runtime directory, creating it on the first start after an install or update. */
    static File prepare(Context context) throws IOException {
        File target = new File(context.getFilesDir(), "runtime");
        String stamp = stampFor(context);
        File stampFile = new File(target, STAMP_NAME);

        if (target.isDirectory() && stamp.equals(readText(stampFile))) {
            return target;
        }

        // An interrupted unpack (or an update) must not leave a half-written mixture behind.
        deleteRecursively(target);
        if (!target.mkdirs()) {
            throw new IOException("Cannot create " + target);
        }
        copyAssetDirectory(context.getAssets(), ASSET_ROOT, target);
        writeText(stampFile, stamp);
        Log.i(TAG, "unpacked the runtime files into " + target);
        return target;
    }

    /** Changes whenever the APK does, so an update replaces the files it shipped. */
    private static String stampFor(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + "/" + info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static void copyAssetDirectory(AssetManager assets, String assetPath, File target)
            throws IOException {
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            throw new IOException("The APK has no " + assetPath + " assets");
        }
        for (String entry : entries) {
            String childAsset = assetPath + "/" + entry;
            File child = new File(target, entry);
            // AssetManager has no isDirectory(); a directory is what lists children.
            String[] grandchildren = assets.list(childAsset);
            if (grandchildren != null && grandchildren.length > 0) {
                if (!child.isDirectory() && !child.mkdirs()) {
                    throw new IOException("Cannot create " + child);
                }
                copyAssetDirectory(assets, childAsset, child);
            } else {
                copyAsset(assets, childAsset, child);
            }
        }
    }

    private static void copyAsset(AssetManager assets, String assetPath, File target)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (InputStream input = assets.open(assetPath);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        for (int read = input.read(buffer); read > 0; read = input.read(buffer)) {
            output.write(buffer, 0, read);
        }
    }

    static void deleteRecursively(File path) {
        File[] children = path.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // Not an error: the caller only needs the path gone, and mkdirs() reports what it cannot do.
        if (!path.delete() && path.exists()) {
            Log.w(TAG, "could not delete " + path);
        }
    }

    private static String readText(File file) {
        try (InputStream input = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            copy(input, bytes);
            return bytes.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
