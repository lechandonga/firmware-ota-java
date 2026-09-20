package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 设备槽位布局（device/slots/slot-layout.json），决定启动哪一份镜像。
 * 通过临时文件 + Files.move ATOMIC_MOVE 整体替换，保证原子切换。
 */
public class SlotLayout {

    /** A 或 B，当前活动槽位。 */
    private String activeSlot;
    /** 已确认可正常启动的槽位（回滚目标）。 */
    private String committedSlot;
    /** 待自检的新槽位；为空表示没有待确认升级。 */
    private String pendingSlot;
    /** 启动次数，每次启动加一。 */
    private int bootCount;
    /** 待确认槽位已进行的自检尝试次数。 */
    private int selfTestAttempts;
    /** 待确认升级的 upgradeId（关联升级历史，自检/回滚时更新该记录）。 */
    private String pendingUpgradeId;
    /** 上一个已确认版本所在槽位（转正后仍保留，供手动回滚到最近好版本）。 */
    private String previousCommittedSlot;

    public SlotLayout() {
    }

    public String getActiveSlot() {
        return activeSlot;
    }

    public void setActiveSlot(String activeSlot) {
        this.activeSlot = activeSlot;
    }

    public String getCommittedSlot() {
        return committedSlot;
    }

    public void setCommittedSlot(String committedSlot) {
        this.committedSlot = committedSlot;
    }

    public String getPendingSlot() {
        return pendingSlot;
    }

    public void setPendingSlot(String pendingSlot) {
        this.pendingSlot = pendingSlot;
    }

    public int getBootCount() {
        return bootCount;
    }

    public void setBootCount(int bootCount) {
        this.bootCount = bootCount;
    }

    public int getSelfTestAttempts() {
        return selfTestAttempts;
    }

    public void setSelfTestAttempts(int selfTestAttempts) {
        this.selfTestAttempts = selfTestAttempts;
    }

    public String getPendingUpgradeId() {
        return pendingUpgradeId;
    }

    public void setPendingUpgradeId(String pendingUpgradeId) {
        this.pendingUpgradeId = pendingUpgradeId;
    }

    public String getPreviousCommittedSlot() {
        return previousCommittedSlot;
    }

    public void setPreviousCommittedSlot(String previousCommittedSlot) {
        this.previousCommittedSlot = previousCommittedSlot;
    }
}
