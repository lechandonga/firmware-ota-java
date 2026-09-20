package com.github.lechandonga.firmwareotajava.config;

import com.github.lechandonga.firmwareotajava.model.Version;
import com.github.lechandonga.firmwareotajava.upgrade.BootRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 应用启动后首先执行“上电恢复”：
 * 安装出厂固件或依据持久状态确定本次落在哪个完整版本上。
 */
@Component
@Order(0)
public class BootRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootRecoveryRunner.class);

    private final BootRecovery bootRecovery;

    public BootRecoveryRunner(BootRecovery bootRecovery) {
        this.bootRecovery = bootRecovery;
    }

    @Override
    public void run(ApplicationArguments args) {
        Version running = bootRecovery.recover();
        log.info("设备启动完成，确定运行版本: {}", running);
    }
}
