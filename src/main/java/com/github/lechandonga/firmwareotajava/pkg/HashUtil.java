package com.github.lechandonga.firmwareotajava.pkg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 计算与十六进制转换工具。 */
public final class HashUtil {

    private HashUtil() {
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", e);
        }
    }

    public static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
