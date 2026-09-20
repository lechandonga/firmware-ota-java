package com.github.lechandonga.firmwareotajava.model;

/** 升级包被拒绝的可区分原因。 */
public enum RejectReason {
    MANIFEST_MISSING,
    MANIFEST_MALFORMED,
    PAYLOAD_MISSING,
    PAYLOAD_TAMPERED,
    MODEL_MISMATCH,
    HARDWARE_MISMATCH,
    PACKAGE_TOO_LARGE,
    STORAGE_LIMIT_EXCEEDED,
    DOWNGRADE_FORBIDDEN,
    VERSION_BELOW_MIN_COMPATIBLE,
    SOURCE_NOT_FOUND
}
