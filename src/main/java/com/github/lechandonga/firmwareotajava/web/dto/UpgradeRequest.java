package com.github.lechandonga.firmwareotajava.web.dto;

/** 升级请求：升级包在来源目录下的目录名。 */
public class UpgradeRequest {
    private String packageName;

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
}
