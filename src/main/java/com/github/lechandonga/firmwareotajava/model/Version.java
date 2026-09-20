package com.github.lechandonga.firmwareotajava.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * 语义化版本号 major.minor.patch，支持解析与大小比较。
 * 比较结果用于区分升级与降级，以及最低兼容版本判断。
 */
public record Version(int major, int minor, int patch)
        implements Comparable<Version> {

    @JsonCreator
    public static Version parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("版本号为空");
        }
        String[] parts = text.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("非法版本号（应为 major.minor.patch）: " + text);
        }
        try {
            return new Version(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法版本号: " + text, e);
        }
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, o.minor);
        if (c != 0) {
            return c;
        }
        return Integer.compare(patch, o.patch);
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @com.fasterxml.jackson.annotation.JsonValue
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
