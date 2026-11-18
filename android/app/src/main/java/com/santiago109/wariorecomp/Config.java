package com.santiago109.wariorecomp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The engine's Config.toml, edited the way the PC launcher edits it: line by line, so only the keys
 * actually changed are rewritten and everything else survives — comments, the header the engine
 * writes itself, and the keys the game saves on its own.
 *
 * <p>A port of launcher-avalonia/Core/Toml.cs. The engine reads the same file with the same keys on
 * Android as on PC, which is what makes the settings here mean anything.
 */
final class Config {

    private final File file;
    private List<String> lines = new ArrayList<>();

    Config(File file) {
        this.file = file;
        reload();
    }

    File path() {
        return file;
    }

    void reload() {
        lines = new ArrayList<>();
        if (!file.isFile()) {
            return;
        }
        String text;
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            RuntimeFiles.copy(input, bytes);
            text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        // A byte-order mark would otherwise become part of the first key's name.
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            lines.add(line);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── Lines ───────────────────────────────────────────────────────────────────────────────────

    private static String sectionOf(String line) {
        String t = line.trim();
        if (t.startsWith("[") && !t.startsWith("[[") && t.contains("]")) {
            return t.substring(1, t.indexOf(']')).trim();
        }
        return null;
    }

    /** The key and its raw literal on this line, or null when the line holds neither. */
    private static String[] keyOf(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.charAt(0) == '#' || t.charAt(0) == '[') {
            return null;
        }
        int equals = t.indexOf('=');
        if (equals <= 0) {
            return null;
        }
        String key = t.substring(0, equals).trim();
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        return new String[]{key, stripComment(t.substring(equals + 1).trim())};
    }

    /** Drops a trailing comment, leaving a hash that is inside a string alone. */
    private static String stripComment(String value) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '#') {
                return value.substring(0, i).trim();
            }
        }
        return value.trim();
    }

    /** Where a key lives, and where its section begins and ends: {index, start, end}. */
    private int[] find(String section, String key) {
        String current = "";
        int sectionStart = -1;
        int sectionEnd = -1;
        int found = -1;
        for (int i = 0; i < lines.size(); i++) {
            String s = sectionOf(lines.get(i));
            if (s != null) {
                if (current.equals(section) && sectionStart >= 0 && sectionEnd < 0) {
                    sectionEnd = i;
                }
                current = s;
                if (s.equals(section) && sectionStart < 0) {
                    sectionStart = i;
                }
                continue;
            }
            if (current.equals(section)) {
                String[] entry = keyOf(lines.get(i));
                if (entry != null && entry[0].equals(key)) {
                    found = i;
                }
            }
        }
        if (sectionStart >= 0 && sectionEnd < 0) {
            sectionEnd = lines.size();
        }
        return new int[]{found, sectionStart, sectionEnd};
    }

    private String getRaw(String section, String key) {
        int index = find(section, key)[0];
        if (index < 0) {
            return null;
        }
        String[] entry = keyOf(lines.get(index));
        return entry == null ? null : entry[1];
    }

    private void setRaw(String section, String key, String literal) {
        int[] where = find(section, key);
        int index = where[0];
        int start = where[1];
        int end = where[2];
        String line = key + " = " + literal;
        if (index >= 0) {
            lines.set(index, line);
            return;
        }
        if (start < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.add("");
            }
            lines.add("[" + section + "]");
            lines.add(line);
            return;
        }
        // Into the section, above the blank lines that separate it from the next one.
        int insert = end;
        while (insert - 1 > start && lines.get(insert - 1).trim().isEmpty()) {
            insert--;
        }
        lines.add(insert, line);
    }

    // ── Values ──────────────────────────────────────────────────────────────────────────────────

    String getString(String section, String key, String fallback) {
        String v = getRaw(section, key);
        if (v == null) {
            return fallback;
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < v.length() - 1; i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length() - 1) {
                    char next = v.charAt(++i);
                    out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    void setString(String section, String key, String value) {
        String escaped = (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"");
        setRaw(section, key, "\"" + escaped + "\"");
    }

    boolean getBool(String section, String key, boolean fallback) {
        String v = getRaw(section, key);
        if ("true".equals(v)) {
            return true;
        }
        if ("false".equals(v)) {
            return false;
        }
        return fallback;
    }

    void setBool(String section, String key, boolean value) {
        setRaw(section, key, value ? "true" : "false");
    }

    double getDouble(String section, String key, double fallback) {
        String v = getRaw(section, key);
        if (v == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    void setDouble(String section, String key, double value) {
        setRaw(section, key, trimZeros(String.format(Locale.ROOT, "%.4f", value)));
    }

    /** 2.2500 is written as 2.25 and 1.0000 as 1.0, matching what the PC launcher writes. */
    private static String trimZeros(String value) {
        String out = value;
        while (out.contains(".") && out.endsWith("0") && !out.endsWith(".0")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    int getInt(String section, String key, int fallback) {
        String v = getRaw(section, key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    void setInt(String section, String key, int value) {
        setRaw(section, key, Integer.toString(value));
    }
}
