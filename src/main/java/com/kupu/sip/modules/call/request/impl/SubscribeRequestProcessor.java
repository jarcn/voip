package com.kupu.sip.modules.call.request.impl;

import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.EventHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

@Slf4j
public class SubscribeRequestProcessor extends AbstractRequestProcessor {

    public SubscribeRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
                                     MessageFactory messageFactory, SipProvider sipProvider, SipFactory sipFactory) {
        super(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory);
    }

    @Override
    public void process(RequestEvent evt) throws Exception {
        Request request = evt.getRequest();
        ServerTransaction serverTransaction = evt.getServerTransaction();
        String clientId = extractClientId(evt);

        if (serverTransaction == null) {
            serverTransaction = sipProvider.getNewServerTransaction(request);
        }

        Dialog dialog = serverTransaction.getDialog();
        if (dialog == null) {
            dialog = sipProvider.getNewDialog(serverTransaction);
        }

        String callId = dialog.getCallId().getCallId();
        log.info("[{}] 处理SUBSCRIBE请求 callId: {}", clientId, callId);

        // 检查Event头部
        EventHeader eventHeader = (EventHeader) request.getHeader(EventHeader.NAME);
        if (eventHeader == null) {
            sendResponse(evt, Response.BAD_EVENT);
            return;
        }

        // 处理订阅请求
        ExpiresHeader expiresHeader = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        int expires = expiresHeader != null ? expiresHeader.getExpires() : 3600;

        if (expires == 0) {
            // 处理取消订阅
            handleUnsubscribe(evt, clientId, callId);
        } else {
            // 处理订阅
            handleSubscribe(evt, clientId, callId, expires, eventHeader.getEventType());
        }
    }

    private void handleSubscribe(RequestEvent evt, String clientId, String callId, int expires, String eventType) throws Exception {
        // 创建或更新订阅会话
        SipSession session = sessionManager.getSession(clientId, callId);
        if (session == null) {
            session = sessionManager.createSession(clientId, callId);
            session.setCallId(callId);
        }

        // 发送200 OK响应
        Response response = messageFactory.createResponse(Response.OK, evt.getRequest());
        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(expires);
        response.addHeader(expiresHeader);
        evt.getServerTransaction().sendResponse(response);

        log.info("[{}] 订阅成功 eventType: {} expires: {} callId: {}", clientId, eventType, expires, callId);

        // 发送初始NOTIFY
        sendInitialNotify(evt, session, eventType);
    }

    private void handleUnsubscribe(RequestEvent evt, String clientId, String callId) throws Exception {
        // 移除订阅会话
        sessionManager.removeSession(clientId, callId);

        // 发送200 OK响应
        Response response = messageFactory.createResponse(Response.OK, evt.getRequest());
        evt.getServerTransaction().sendResponse(response);
        log.info("[{}] 取消订阅成功 callId: {}", clientId, callId);
    }

    private void sendInitialNotify(RequestEvent evt, SipSession session, String eventType) {
        // 实现发送初始NOTIFY的逻辑
        // 根据不同的事件类型发送相应的通知内容
    }
}