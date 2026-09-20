package com.github.lechandonga.firmwareotajava.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.pkg.PackageParser;
import com.github.lechandonga.firmwareotajava.storage.ChunkStagingStore;
import com.github.lechandonga.firmwareotajava.storage.FactoryInstaller;
import com.github.lechandonga.firmwareotajava.storage.SlotInstaller;
import com.github.lechandonga.firmwareotajava.storage.SlotLayout;
import com.github.lechandonga.firmwareotajava.storage.SlotSwitcher;
import com.github.lechandonga.firmwareotajava.storage.StateStore;
import com.github.lechandonga.firmwareotajava.storage.UpgradeHistory;
import com.github.lechandonga.firmwareotajava.upgrade.BootRecovery;
import com.github.lechandonga.firmwareotajava.upgrade.SelftestExecutor;
import com.github.lechandonga.firmwareotajava.upgrade.UpgradeService;
import com.github.lechandonga.firmwareotajava.upgrade.VersionPolicy;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 设备测试夹具：在临时目录上手工装配全部 OTA 组件（等价于 Spring 单例），
 * {@link #reboot()} 通过丢弃对象、用相同存储目录重新装配来模拟掉电/强杀后重启，
 * 从而验证状态、槽位指针与暂存位图在新进程视角下是否可确定恢复。
 */
public class DeviceHarness {

    public final Path storage;
    public final Path packages;
    public final OtaProperties properties;

    private ObjectMapper mapper;
    private SlotLayout layout;
    private StateStore stateStore;
    public ChunkStagingStore staging;
    public SlotInstaller installer;
    public SlotSwitcher switcher;
    private FactoryInstaller factory;
    private UpgradeHistory history;
    private PackageParser parser;
    private VersionPolicy policy;
    private SelftestExecutor selftest;
    public BootRecovery recovery;
    public UpgradeService service;

    private DeviceHarness(Path storage, Path packages, OtaProperties properties) {
        this.storage = storage;
        this.packages = packages;
        this.properties = properties;
        wire();
    }

    /** 创建一台全新设备并执行首次上电（安装出厂固件 1.0.0）。 */
    public static DeviceHarness boot(Path storage, Path packages) {
        return boot(storage, packages, defaultProps(storage, packages));
    }

    public static DeviceHarness boot(Path storage, Path packages, OtaProperties props) {
        DeviceHarness h = new DeviceHarness(storage, packages, props);
        h.recovery.recover();
        return h;
    }

    /** 模拟掉电后重新上电：重新加载持久状态并执行恢复，返回新的夹具实例。 */
    public DeviceHarness reboot() {
        DeviceHarness h = new DeviceHarness(storage, packages, properties);
        h.recovery.recover();
        return h;
    }

    private void wire() {
        mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        layout = new SlotLayout(properties);
        try {
            layout.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        stateStore = new StateStore(layout, mapper);
        staging = new ChunkStagingStore(layout, mapper);
        installer = new SlotInstaller(layout, mapper);
        switcher = new SlotSwitcher(layout, installer);
        factory = new FactoryInstaller(properties, layout, installer, switcher);
        history = new UpgradeHistory(layout);
        parser = new PackageParser(properties, mapper);
        policy = new VersionPolicy(properties);
        selftest = new SelftestExecutor();
        recovery = new BootRecovery(properties, stateStore, installer, staging,
                history, factory, switcher, layout);
        service = new UpgradeService(properties, parser, stateStore, staging,
                installer, switcher, policy, selftest, history, recovery, layout);
    }

    public static OtaProperties defaultProps(Path storage, Path packages) {
        OtaProperties p = new OtaProperties();
        p.setStorageDir(storage.toString());
        p.setPackageSourceDir(packages.toString());
        p.setFactoryVersion("1.0.0");
        p.setChunkSize(4096);
        p.setSelftestTimeoutMillis(300);
        p.setMaxPackageBytes(1024L * 1024);       // 1 MiB（测试用小上限）
        p.setMaxStorageBytes(8L * 1024 * 1024);   // 8 MiB
        return p;
    }
}
