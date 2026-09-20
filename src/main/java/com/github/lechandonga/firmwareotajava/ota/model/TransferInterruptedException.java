package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 模拟传输/写入中断。已处理字节数仅用于日志与测试断言，
 * 已写入但未通过分块校验的数据不会被当作有效数据。
 */
public class TransferInterruptedException extends RuntimeException {

    private final long bytesProcessed;

    public TransferInterruptedException(long bytesProcessed, String message) {
        super(message);
        this.bytesProcessed = bytesProcessed;
    }

    public long getBytesProcessed() {
        return bytesProcessed;
    }
}
