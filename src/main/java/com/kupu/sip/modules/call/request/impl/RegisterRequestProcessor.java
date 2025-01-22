package com.kupu.sip.modules.call.request.impl;

import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

@Slf4j
public class RegisterRequestProcessor extends AbstractRequestProcessor {

    public RegisterRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
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

        log.info("[{}] 处理REGISTER请求", clientId);

        // 检查认证信息
        if (!checkAuthentication(request)) {
            sendAuthenticationChallenge(evt);
            return;
        }

        // 处理注册信息
        ExpiresHeader expiresHeader = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        int expires = expiresHeader != null ? expiresHeader.getExpires() : 3600;

        if (expires == 0) {
            // 处理注销请求
            handleDeregistration(evt, clientId);
        } else {
            // 处理注册请求
            handleRegistration(evt, clientId, expires);
        }
    }

    private boolean checkAuthentication(Request request) {
        // 检查认证信息
        // 根据实际需求实现认证逻辑
        return true;
    }

    private void sendAuthenticationChallenge(RequestEvent evt) throws Exception {
        Response response = messageFactory.createResponse(Response.UNAUTHORIZED, evt.getRequest());
        // 添加认证头部
        // 根据实际需求设置认证参数
        evt.getServerTransaction().sendResponse(response);
    }

    private void handleRegistration(RequestEvent evt, String clientId, int expires) throws Exception {
        // 创建或更新注册会话
        CallIdHeader callIdHeader = (CallIdHeader) evt.getRequest().getHeader(CallIdHeader.NAME);
        SipSession session = sessionManager.createSession(clientId, callIdHeader.getCallId());
        session.updateStatus(SipSession.SessionStatus.REGISTERED);
        // 发送200 OK响应
        Response response = messageFactory.createResponse(Response.OK, evt.getRequest());
        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(expires);
        response.addHeader(expiresHeader);
        evt.getServerTransaction().sendResponse(response);
        log.info("[{}] 注册成功，有效期：{} 秒", clientId, expires);
    }

    private void handleDeregistration(RequestEvent evt, String clientId) throws Exception {
        // 移除注册会话
        sessionManager.removeSession(clientId, null);

        // 发送200 OK响应
        Response response = messageFactory.createResponse(Response.OK, evt.getRequest());
        evt.getServerTransaction().sendResponse(response);
        log.info("[{}] 注销成功", clientId);
    }
}