package com.kupu.sip.modules.call.request;

import javax.sip.RequestEvent;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import com.kupu.sip.modules.session.SessionManager;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractRequestProcessor implements ISipRequestProcessor {

    protected final SessionManager sessionManager;
    protected final HeaderFactory headerFactory;
    protected final AddressFactory addressFactory;
    protected final MessageFactory messageFactory;
    protected final SipProvider sipProvider;
    protected final SipFactory sipFactory;

    protected AbstractRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory, MessageFactory messageFactory, SipProvider sipProvider, SipFactory sipFactory) {
        this.sessionManager = sessionManager;
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
        this.messageFactory = messageFactory;
        this.sipProvider = sipProvider;
        this.sipFactory = sipFactory;
    }

    @Override
    public abstract void process(RequestEvent evt) throws Exception;

    protected void sendResponse(RequestEvent evt, int statusCode) {
        try {
            Response response = messageFactory.createResponse(statusCode, evt.getRequest());
            evt.getServerTransaction().sendResponse(response);
            log.info("发送响应: {}", statusCode);
        } catch (Exception e) {
            log.error("发送响应失败", e);
        }
    }

    protected String extractClientId(RequestEvent evt) {
        Request request = evt.getRequest();
        FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
        if (fromHeader != null) {
            return fromHeader.getAddress().getURI().toString();
        }
        return "unknown-client";
    }

    protected void updateSessionStatus(String clientId, RequestEvent evt) {
        // 由子类实现具体的会话状态更新逻辑
    }
} 