package com.kupu.sip.common.configs;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * UAS配置
 */


@Data
@Configuration
@ConfigurationProperties(prefix = "inner.server")
public class UASConfig {

    @Value("${inner.server.uas.ip:}")    
    private String uasIp;

    @Value("${inner.server.uas.port:}")
    private Integer uasPort;

}
