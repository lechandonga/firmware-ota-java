package com.github.lechandonga.firmwareotajava.pkg;

import com.github.lechandonga.firmwareotajava.model.RejectReason;

/** 升级包解析/校验失败，携带可区分的拒绝原因。 */
public class PackageRejectedException extends Exception {
    private final RejectReason reason;

    public PackageRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() { return reason; }
}
