package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 一份已写入槽位的固件镜像元数据。
 */
public class ImageInfo {

    private String version;
    private String payloadSha256;
    private long payloadSize;
    private String hardwareModel;
    private String hardwareVersion;
    private String minCompatibleVersion;
    private SelfTestSpec selfTest;

    public ImageInfo() {
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public void setPayloadSha256(String payloadSha256) {
        this.payloadSha256 = payloadSha256;
    }

    public long getPayloadSize() {
        return payloadSize;
    }

    public void setPayloadSize(long payloadSize) {
        this.payloadSize = payloadSize;
    }

    public String getHardwareModel() {
        return hardwareModel;
    }

    public void setHardwareModel(String hardwareModel) {
        this.hardwareModel = hardwareModel;
    }

    public String getHardwareVersion() {
        return hardwareVersion;
    }

    public void setHardwareVersion(String hardwareVersion) {
        this.hardwareVersion = hardwareVersion;
    }

    public String getMinCompatibleVersion() {
        return minCompatibleVersion;
    }

    public void setMinCompatibleVersion(String minCompatibleVersion) {
        this.minCompatibleVersion = minCompatibleVersion;
    }

    public SelfTestSpec getSelfTest() {
        return selfTest;
    }

    public void setSelfTest(SelfTestSpec selfTest) {
        this.selfTest = selfTest;
    }
}
