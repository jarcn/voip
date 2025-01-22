package com.kupu.sip.modules.call.request;

import com.kupu.sip.modules.call.request.impl.*;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionKeepAlive;
import com.kupu.sip.modules.session.SessionManager;
import lombok.extern.slf4j.Slf4j;

import javax.sip.RequestEvent;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class RequestProcessorProxy implements ISipRequestProcessor {

    private final Map<String, ISipRequestProcessor> processors;
    private final SessionManager sessionManager;
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;
    private final MessageFactory messageFactory;
    private final SipProvider sipProvider;
    private final SipFactory sipFactory;
    private final RtpMediaManager rtpMediaManager;
    private final SessionKeepAlive sessionKeepAlive;

    public RequestProcessorProxy(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory
            , SipFactory sipFactory, MessageFactory messageFactory, SipProvider sipProvider, SessionKeepAlive sessionKeepAlive, RtpMediaManager rtpMediaManager) {
        this.sessionManager = sessionManager;
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
        this.messageFactory = messageFactory;
        this.sipProvider = sipProvider;
        this.sipFactory = sipFactory;
        this.processors = new HashMap<>();
        this.sessionKeepAlive = sessionKeepAlive;
        this.rtpMediaManager = rtpMediaManager;
        initProcessors();
    }

    private void initProcessors() {
        processors.put(Request.INVITE, new InviteRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
        processors.put(Request.BYE, new ByeRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory,sessionKeepAlive, rtpMediaManager));
        processors.put(Request.CANCEL, new CancelRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
        processors.put(Request.ACK, new AckRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
        processors.put(Request.INFO, new InfoRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
        processors.put(Request.MESSAGE, new MessageRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
        processors.put(Request.REGISTER, new RegisterRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
        processors.put(Request.SUBSCRIBE, new SubscribeRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
    }

    @Override
    public void process(RequestEvent evt) throws Exception {
        Request request = evt.getRequest();
        String method = request.getMethod();
        String clientId = extractClientId(evt);
        log.info("[{}] 收到{}请求", clientId, method);
        ISipRequestProcessor processor = processors.getOrDefault(method, new OtherRequestProcessor(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory));
        try {
            processor.process(evt);
        } catch (Exception e) {
            log.error("[{}] 处理{}请求失败", clientId, method, e);
            sendErrorResponse(evt, e);
        }
    }

    private void sendErrorResponse(RequestEvent evt, Exception e) {
        try {
            Response response = messageFactory.createResponse(Response.SERVER_INTERNAL_ERROR, evt.getRequest());
            evt.getServerTransaction().sendResponse(response);
        } catch (Exception ex) {
            log.error("发送错误响应失败", ex);
        }
    }

    private String extractClientId(RequestEvent evt) {
        Request request = evt.getRequest();
        return request.getHeader("From").toString();
    }
}