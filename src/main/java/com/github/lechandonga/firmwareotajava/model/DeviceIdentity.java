package com.github.lechandonga.firmwareotajava.model;

/** 设备身份：型号与硬件版本，用于兼容性匹配。 */
public record DeviceIdentity(String model, String hardwareVersion) {
}
