package com.kupu.sip.modules.call.response.impl;

import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.header.Header;
import javax.sip.message.Response;

import com.kupu.sip.modules.call.response.AbstractResponseProcessor;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import com.kupu.sip.modules.session.SessionKeepAlive;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpdateResponseProcessor extends AbstractResponseProcessor {

    private static final String SESSION_EXPIRES_HEADER = "Session-Expires";
    private final SessionKeepAlive keepAlive;

    public UpdateResponseProcessor(SessionManager sessionManager, SessionKeepAlive keepAlive, RtpMediaManager rtpMediaManager) {
        super(sessionManager, rtpMediaManager);
        this.keepAlive = keepAlive;
    }

    @Override
    public void process(ResponseEvent evt) throws Exception {
        Response response = evt.getResponse();
        Dialog dialog = evt.getDialog();
        String clientId = extractClientId(evt);
        if (dialog == null) {
            log.warn("[{}] UPDATE响应中的Dialog为空", clientId);
            return;
        }
        String callId = dialog.getCallId().getCallId();
        int statusCode = response.getStatusCode();
        log.info("[{}] 处理UPDATE响应: {} callId: {}", clientId, statusCode, callId);
        // 获取会话
        SipSession session = sessionManager.getClientSessions(clientId).values().stream().filter(s -> callId.equals(s.getCallId())).findFirst().orElse(null);
        if (session == null) {
            log.warn("[{}] 未找到会话信息 callId: {}", clientId, callId);
            return;
        }
        // 处理200 OK响应
        if (statusCode == Response.OK) {
            // 检查Session-Expires头部
            Header header = response.getHeader(SESSION_EXPIRES_HEADER);
            if (header != null) {
                try {
                    // 解析Session-Expires值，格式如: "1800;refresher=uac"
                    String headerValue = header.toString().substring(SESSION_EXPIRES_HEADER.length() + 2);
                    String[] parts = headerValue.split(";");
                    int expires = Integer.parseInt(parts[0].trim());
                    String refresher = parts.length > 1 ? parts[1].split("=")[1].trim() : "uac";
                    log.info("[{}] UPDATE会话刷新协商成功 callId: {}, expires: {}, refresher: {}", clientId, callId, expires, refresher);
                    // 1. 更新会话的刷新参数
                    session.setSessionExpires(expires);
                    session.setRefresher(refresher);
                    // 2. 如果我们是刷新方(uac),则启动会话保活定时器
                    if ("uac".equals(refresher)) {
                        // 提前半分钟发送刷新请求
                        int refreshInterval = (expires - 30) * 1000;
                        keepAlive.scheduleKeepAlive(clientId, dialog, refreshInterval);
                        log.info("[{}] UAC作为刷新方,设置会话保活定时器 callId: {}, 间隔: {}秒", clientId, callId, expires);
                    } else {
                        log.info("[{}] UAS作为刷新方,等待对方发送刷新请求 callId: {}, 间隔: {}秒", clientId, callId, expires);
                    }
                    // 3. 更新会话状态
                    session.updateStatus(SipSession.SessionStatus.CONNECTED);
                    log.info("[{}] UPDATE会话刷新协商成功 callId: {}, expires: {}, refresher: {}", clientId, callId, expires, refresher);
                } catch (Exception e) {
                    log.error("[{}] 解析Session-Expires头部失败 callId: {}", clientId, callId, e);
                }
            } else {
                log.warn("[{}] UPDATE响应中未包含Session-Expires头部 callId: {}", clientId, callId);
            }
        } else if (statusCode >= 300) {
            // 处理错误响应
            log.warn("[{}] UPDATE请求失败 callId: {}, statusCode: {}", clientId, callId, statusCode);
            // 如果是致命错误，则更新会话状态
            if (statusCode == Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST ||
                    statusCode == Response.REQUEST_TIMEOUT || statusCode >= 500) {
                session.updateStatus(SipSession.SessionStatus.FAILED);
                log.error("[{}] UPDATE请求收到严重错误响应，会话可能已断开 callId: {}, statusCode: {}", clientId, callId, statusCode);
            }
        }
    }

    @Override
    protected void updateStatus(SipSession session, int statusCode) {
        // UPDATE响应不需要特殊的状态更新逻辑，主要在process方法中处理
    }
} 