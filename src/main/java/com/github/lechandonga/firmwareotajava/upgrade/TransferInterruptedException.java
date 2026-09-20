package com.github.lechandonga.firmwareotajava.upgrade;

/**
 * 模拟传输/写入中断（断电、拔线、进程被强杀）。
 * 抛出时已完成校验的块保留且有效，正在写的块不进入校验位图，
 * 下次升级同一包时从断点继续。
 */
public class TransferInterruptedException extends RuntimeException {

    private final int completedChunks;

    public TransferInterruptedException(int completedChunks, String message) {
        super(message);
        this.completedChunks = completedChunks;
    }

    public int getCompletedChunks() {
        return completedChunks;
    }
}
