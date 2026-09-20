package com.github.lechandonga.firmwareotajava.ota.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 工具。
 */
public final class Hashes {

    private static final int BUFFER_SIZE = 64 * 1024;

    private Hashes() {
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    public static String sha256Hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256Hex(in);
        }
    }

    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
