package com.kupu.sip.modules.session;

import lombok.extern.slf4j.Slf4j;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.SipProvider;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.message.Request;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class SessionKeepAlive {
    private final SipProvider sipProvider;
    private final HeaderFactory headerFactory;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> keepAliveTasks;

    public SessionKeepAlive(SipProvider sipProvider, HeaderFactory headerFactory) {
        this.sipProvider = sipProvider;
        this.headerFactory = headerFactory;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.keepAliveTasks = new ConcurrentHashMap<>();
    }


    public void stopKeepAlive(String clientId, String callId) {
        ScheduledFuture<?> task = keepAliveTasks.remove(callId);
        if (task != null) {
            task.cancel(true);
            log.info("[{}] 停止会话保活 callId: {}", clientId, callId);
        }
    }

    public void shutdown() {
        // 停止所有保活任务
        keepAliveTasks.forEach((callId, task) -> task.cancel(true));
        keepAliveTasks.clear();
        // 关闭调度器
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("会话保活服务已关闭");
    }

    /**
     * 调度会话保活定时任务
     *
     * @param clientId 客户端ID
     * @param dialog   SIP对话
     * @param interval 保活间隔(毫秒)
     */
    public void scheduleKeepAlive(String clientId, Dialog dialog, int interval) {
        String callId = dialog.getCallId().getCallId();
        log.info("[{}] 调度会话保活定时任务 callId: {}, 间隔: {}ms", clientId, callId, interval);
        // 创建定时任务
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                // 创建UPDATE请求
                Request updateRequest = dialog.createRequest(Request.UPDATE);
                // 添加Session-Expires头部
                Header sessionExpiresHeader = headerFactory.createHeader("Session-Expires", interval / 1000 + ";refresher=uac");
                updateRequest.addHeader(sessionExpiresHeader);
                // 添加Supported头部
                Header supportedHeader = headerFactory.createHeader("Supported", "timer");
                updateRequest.addHeader(supportedHeader);
                // 发送请求
                ClientTransaction ct = sipProvider.getNewClientTransaction(updateRequest);
                dialog.sendRequest(ct);
                log.debug("[{}] 发送会话保活UPDATE请求 callId: {}", clientId, callId);
            } catch (Exception e) {
                log.error("[{}] 发送会话保活UPDATE请求失败 callId: {}", clientId, callId, e);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        // 保存定时任务
        keepAliveTasks.put(clientId + "_" + callId, future);
    }
}