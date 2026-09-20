package com.github.lechandonga.firmwareotajava.ota.boot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时执行一次启动决策（自检/回滚/正常启动）。
 */
@Component
public class OtaStartupRunner implements ApplicationRunner {

    private final BootCoordinator bootCoordinator;

    public OtaStartupRunner(BootCoordinator bootCoordinator) {
        this.bootCoordinator = bootCoordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootCoordinator.onStartup();
    }
}
