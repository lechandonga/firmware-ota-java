package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 升级包解析/校验失败，携带可区分的拒绝原因。
 */
public class PackageException extends RuntimeException {

    private final RejectReason reason;

    public PackageException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PackageException(RejectReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
