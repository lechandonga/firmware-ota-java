package com.github.lechandonga.firmwareotajava.upgrade;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.model.RejectReason;
import com.github.lechandonga.firmwareotajava.model.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 版本策略（可配置）：
 * <ul>
 *   <li>{@code firmware.ota.allow-downgrade=false}（默认）时拒绝降级；</li>
 *   <li>{@code firmware.ota.minimum-compatible-version} 设定最低兼容版本，
 *       目标版本低于它一律拒绝（即使显式允许降级）。</li>
 * </ul>
 * 策略拒绝不改变在位版本，也不写入升级历史。
 */
@Component
public class VersionPolicy {

    private static final Logger log = LoggerFactory.getLogger(VersionPolicy.class);

    private final OtaProperties properties;

    public VersionPolicy(OtaProperties properties) {
        this.properties = properties;
    }

    public RejectReason evaluate(Version current, Version target) {
        Version min = properties.minimumCompatible();
        if (min != null && target.compareTo(min) < 0) {
            log.warn("策略拒绝：目标版本 {} 低于最低兼容版本 {}", target, min);
            return RejectReason.VERSION_BELOW_MIN_COMPATIBLE;
        }
        if (!properties.isAllowDowngrade() && current != null
                && target.compareTo(current) < 0) {
            log.warn("策略拒绝：降级被禁止 current={} target={}", current, target);
            return RejectReason.DOWNGRADE_FORBIDDEN;
        }
        return null;
    }
}
