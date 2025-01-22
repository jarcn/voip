package com.kupu.sip.modules.call.controller;

import com.kupu.sip.modules.call.client.JainSipClient;
import com.kupu.sip.modules.call.service.SipService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/sip")
@RequiredArgsConstructor
public class SipCallController {

    private final SipService sipService;

    @Data
    public static class CallRequest {
        private String clientId; // SIP客户端ID
        private String fromUser; // 主叫用户
        private String fromDomain; // 主叫域
        private String toUser; // 被叫用户
        private String toDomain; // 被叫域
        private String ip; // 本地IP
        private Integer sipPort; // 本地端口
        private Integer sdpPort; // 本地端口
        private String uasHost; // SIP服务器地址
    }

    @PostMapping("/call")
    public String makeCall(@RequestBody CallRequest request) {
        try {
            // 1. 创建或获取SIP客户端
            JainSipClient sipClient = sipService.createSipClient(
                    request.getClientId(),
                    request.getIp(),
                    request.getSipPort(),
                    request.getUasHost());

            // 2. 发起呼叫（同步方式）
            CompletableFuture<String> inviteAsync = sipClient.inviteAsync(
                    request.getSdpPort(),
                    request.getFromUser(),
                    request.getFromDomain(),
                    request.getToUser(),
                    request.getToDomain());
            String sessionId = inviteAsync.get();
            return String.format("呼叫已发起，会话ID: %s", sessionId);
        } catch (Exception e) {
            log.error("发起呼叫失败", e);
            throw new RuntimeException("发起呼叫失败: " + e.getMessage());
        }
    }

    /**
     * 销毁指定的SIP客户端
     */
    @DeleteMapping("/client/{clientId}")
    public String destroyClient(@PathVariable String clientId) {
        try {
            sipService.destroyClient(clientId);
            return String.format("SIP客户端 [%s] 已销毁", clientId);
        } catch (Exception e) {
            log.error("销毁SIP客户端失败: {}", clientId, e);
            throw new RuntimeException("销毁SIP客户端失败: " + e.getMessage());
        }
    }

    /**
     * 销毁所有SIP客户端
     */
    @DeleteMapping("/clients")
    public String destroyAllClients() {
        try {
            sipService.destroyAllClients();
            return "所有SIP客户端已销毁";
        } catch (Exception e) {
            log.error("销毁所有SIP客户端失败", e);
            throw new RuntimeException("销毁所有SIP客户端失败: " + e.getMessage());
        }
    }
}