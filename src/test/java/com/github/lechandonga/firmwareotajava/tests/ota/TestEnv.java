package com.github.lechandonga.firmwareotajava.ota.tests.ota;

import com.github.lechandonga.firmwareotajava.ota.boot.BootCoordinator;
import com.github.lechandonga.firmwareotajava.ota.boot.SelfTestRunner;
import com.github.lechandonga.firmwareotajava.ota.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.ota.fault.FaultInjection;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageBuilder;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageReader;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageValidator;
import com.github.lechandonga.firmwareotajava.ota.slots.SlotManager;
import com.github.lechandonga.firmwareotajava.ota.staging.StagingService;
import com.github.lechandonga.firmwareotajava.ota.storage.DeviceStorage;
import com.github.lechandonga.firmwareotajava.ota.storage.HistoryStore;
import com.github.lechandonga.firmwareotajava.ota.storage.JsonFileStore;
import com.github.lechandonga.firmwareotajava.ota.upgrade.UpgradeService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试用装配：全部组件以本地临时目录构造，不启动 Spring 容器。
 */
public class TestEnv {

    public final Path root;
    public final Path packageRoot;
    public final Path deviceRoot;

    public final OtaProperties props;
    public final FaultInjection faults;
    public final JsonFileStore json;
    public final DeviceStorage storage;
    public final HistoryStore history;
    public final FirmwarePackageReader reader;
    public final FirmwarePackageValidator validator;
    public final StagingService staging;
    public final SlotManager slots;
    public final SelfTestRunner selfTest;
    public final BootCoordinator boot;
    public final UpgradeService service;
    public final FirmwarePackageBuilder builder;

    private TestEnv(Path root, OtaProperties props) {
        this.root = root;
        this.packageRoot = root.resolve("packages");
        this.deviceRoot = root.resolve("device");
        this.props = props;
        try {
            Files.createDirectories(packageRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.faults = new FaultInjection();
        this.json = new JsonFileStore();
        this.storage = new DeviceStorage(deviceRoot.toString());
        this.history = new HistoryStore(storage, json);
        this.reader = new FirmwarePackageReader(json);
        this.validator = new FirmwarePackageValidator(props);
        this.staging = new StagingService(storage, json, faults);
        this.slots = new SlotManager(storage, json, faults);
        this.selfTest = new SelfTestRunner(faults);
        this.boot = new BootCoordinator(props, storage, slots, history, selfTest);
        this.service = new UpgradeService(props, storage, reader, validator, staging,
                slots, history, boot, faults, json);
        this.builder = new FirmwarePackageBuilder(json);
    }

    public static TestEnv create(Path tmp, int chunkSize, long maxPackageBytes,
                                 long maxStorageBytes, boolean allowDowngrade,
                                 String minCompatible) {
        OtaProperties props = new OtaProperties();
        props.setStorageRoot(tmp.resolve("device").toString());
        props.setDeviceModel("DEMO-DEVICE");
        props.setHardwareVersion("1.0");
        props.setFactoryVersion("1.0.0");
        props.setFactoryPayload("factory firmware 1.0.0\n");
        props.setChunkSizeBytes(chunkSize);
        props.setMaxPackageBytes(maxPackageBytes);
        props.setMaxStorageBytes(maxStorageBytes);
        props.setAllowDowngrade(allowDowngrade);
        props.setMinCompatibleVersion(minCompatible == null ? "" : minCompatible);
        return new TestEnv(tmp, props);
    }

    public static TestEnv createDefault(Path tmp) {
        return create(tmp, 32, 64L * 1024 * 1024, 256L * 1024 * 1024, false, "");
    }

    public Path buildPackage(String name, String version, String payloadText) {
        return buildPackage(name, version, payloadText.getBytes(StandardCharsets.UTF_8));
    }

    public Path buildPackage(String name, String version, byte[] payload) {
        Path dir = packageRoot.resolve(name);
        builder.build(dir, "DEMO-DEVICE", "1.0", version, payload,
                props.getChunkSizeBytes(), null,
                com.github.lechandonga.firmwareotajava.ota.model.SelfTestMode.ALWAYS_OK,
                5_000L, null);
        return dir;
    }

    public String activeVersion() {
        boot.getClass();
        return slots.activeImage() == null ? null : slots.activeImage().getVersion();
    }
}
