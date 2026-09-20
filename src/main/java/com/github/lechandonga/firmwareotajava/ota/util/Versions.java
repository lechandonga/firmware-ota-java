package com.github.lechandonga.firmwareotajava.ota.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义化版本（major.minor.patch，可带后缀）比较与合法性检查。
 * 数字部分按数值比较，后缀（如 -rc1）存在时视为低于同号正式版，
 * 两个都带后缀时按后缀字符串字典序比较。
 */
public final class Versions {

    private static final Pattern PATTERN =
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

    private Versions() {
    }

    /** 是否为受支持的 x.y.z 形式。 */
    public static boolean isValid(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        return PATTERN.matcher(version.trim()).matches();
    }

    /**
     * 比较两个版本。
     *
     * @return 负数/0/正数；非法版本抛 IllegalArgumentException
     */
    public static int compare(String a, String b) {
        int[] va = parseCore(a);
        int[] vb = parseCore(b);
        for (int i = 0; i < 3; i++) {
            if (va[i] != vb[i]) {
                return Integer.compare(va[i], vb[i]);
            }
        }
        String suffixA = parseSuffix(a);
        String suffixB = parseSuffix(b);
        if (suffixA == null && suffixB == null) {
            return 0;
        }
        // 正式版（无后缀）高于预发布版
        if (suffixA == null) {
            return 1;
        }
        if (suffixB == null) {
            return -1;
        }
        return suffixA.compareTo(suffixB);
    }

    private static int[] parseCore(String version) {
        Matcher m = matcher(version);
        return new int[] {
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
        };
    }

    private static String parseSuffix(String version) {
        return matcher(version).group(4);
    }

    private static Matcher matcher(String version) {
        if (version == null) {
            throw new IllegalArgumentException("version is null");
        }
        Matcher m = PATTERN.matcher(version.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid semantic version: " + version);
        }
        return m;
    }
}
