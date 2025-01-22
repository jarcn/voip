package com.kupu.sip.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import com.kupu.sip.modules.call.service.SipService;

import javax.annotation.PreDestroy;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SipConfiguration {

    private final SipService sipService;

    @PreDestroy
    public void onApplicationShutdown() {
        log.info("应用关闭,开始清理SIP资源...");
        try {
            // 获取所有SIP客户端并销毁
            sipService.destroyAllClients();
            log.info("SIP资源清理完成");
        } catch (Exception e) {
            log.error("SIP资源清理失败", e);
        }
    }
} 