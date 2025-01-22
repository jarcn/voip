package com.kupu.sip.modules.call.request.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionKeepAlive;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.CallIdHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ByeRequestProcessor extends AbstractRequestProcessor {
    private final SessionKeepAlive keepAlive;
    private final RtpMediaManager rtpMediaManager;
    public ByeRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
                               MessageFactory messageFactory, SipProvider sipProvider, SipFactory sipFactory, SessionKeepAlive keepAlive, RtpMediaManager rtpMediaManager) {
        super(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory);
        this.keepAlive = keepAlive;
        this.rtpMediaManager = rtpMediaManager;
    }

    @Override
    public void process(RequestEvent evt) throws Exception {
        Request request = evt.getRequest();
        String callId = ((CallIdHeader) request.getHeader(CallIdHeader.NAME)).getCallId();
        String clientId = extractClientId(evt);
        log.info("[{}] 处理BYE请求 callId: {}", clientId, callId);
        // 先停止会话保活
        keepAlive.stopKeepAlive(clientId, callId);
        // 查找会话
        SipSession session = sessionManager.getClientSessions(clientId).values()
            .stream().filter(s -> callId.equals(s.getCallId())).findFirst()
            .orElseThrow(() -> new SipException("Session not found for callId: " + callId));
        if (session != null) {
             // 发送200 OK响应
            sendResponse(evt, Response.OK);
            // 停止媒体会话
            session.stopMediaSession();
            // 移除会话
            sessionManager.removeSession(clientId, session.getSessionId());
            // 更新会话状态
            session.updateStatus(SipSession.SessionStatus.DISCONNECTED);
            // 停止 rtp 服务
            rtpMediaManager.stopMediaSession();
            // 停止 rtp 服务
            stopRTPSvr(callId);
            log.info("[{}] 会话已终止 callId: {}", clientId, callId);
        } else {
            log.warn("[{}] 会话不存在 callId: {}", clientId, callId);
        }
    }

    private void stopRTPSvr(String callId) {
        Map<String, Object> para = new HashMap<>();
        para.put("call_id", callId);
        String jsonStr = JSONUtil.toJsonStr(para);
        log.info("通知rtp服务停止请求参数:{}", jsonStr);
        String result = HttpUtil.post("http://192.168.0.4:20009/end_call", jsonStr);
        log.info("通知rtp服务停止响应参数:{}", result);
    }
}