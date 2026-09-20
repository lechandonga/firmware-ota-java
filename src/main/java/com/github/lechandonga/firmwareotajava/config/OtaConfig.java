package com.github.lechandonga.firmwareotajava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 OTA 配置属性。 */
@Configuration
@EnableConfigurationProperties(OtaProperties.class)
public class OtaConfig {
}
