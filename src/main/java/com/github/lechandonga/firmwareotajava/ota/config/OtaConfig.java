package com.github.lechandonga.firmwareotajava.ota.config;

import com.github.lechandonga.firmwareotajava.ota.boot.SelfTestRunner;
import com.github.lechandonga.firmwareotajava.ota.fault.FaultInjection;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageReader;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageValidator;
import com.github.lechandonga.firmwareotajava.ota.slots.SlotManager;
import com.github.lechandonga.firmwareotajava.ota.staging.StagingService;
import com.github.lechandonga.firmwareotajava.ota.storage.DeviceStorage;
import com.github.lechandonga.firmwareotajava.ota.storage.HistoryStore;
import com.github.lechandonga.firmwareotajava.ota.storage.JsonFileStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OTA 组件装配。
 */
@Configuration
@EnableConfigurationProperties(OtaProperties.class)
public class OtaConfig {

    @Bean
    public JsonFileStore jsonFileStore() {
        return new JsonFileStore();
    }

    @Bean
    public DeviceStorage deviceStorage(OtaProperties props) {
        return new DeviceStorage(props.getStorageRoot());
    }

    @Bean
    public HistoryStore historyStore(DeviceStorage storage, JsonFileStore json) {
        return new HistoryStore(storage, json);
    }

    @Bean
    public FirmwarePackageReader firmwarePackageReader(JsonFileStore json) {
        return new FirmwarePackageReader(json);
    }

    @Bean
    public FirmwarePackageValidator firmwarePackageValidator(OtaProperties props) {
        return new FirmwarePackageValidator(props);
    }

    @Bean
    public StagingService stagingService(DeviceStorage storage, JsonFileStore json, FaultInjection faults) {
        return new StagingService(storage, json, faults);
    }

    @Bean
    public SlotManager slotManager(DeviceStorage storage, JsonFileStore json, FaultInjection faults) {
        return new SlotManager(storage, json, faults);
    }

    @Bean
    public SelfTestRunner selfTestRunner(FaultInjection faults) {
        return new SelfTestRunner(faults);
    }
}
