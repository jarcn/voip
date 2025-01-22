package com.kupu.sip.modules.call.response;

import com.kupu.sip.modules.call.response.impl.*;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionKeepAlive;
import com.kupu.sip.modules.session.SessionManager;
import lombok.extern.slf4j.Slf4j;

import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.CSeqHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ToHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ResponseProcessorProxy implements ISipResponseProcessor {

    private final Map<String, ISipResponseProcessor> processors;
    private final SessionManager sessionManager;
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;
    private final SipFactory sipFactory;
    private final MessageFactory messageFactory;
    private final SipProvider sipProvider;
    private final SessionKeepAlive sessionKeepAlive;
    private final RtpMediaManager rtpMediaManager;

    public ResponseProcessorProxy(SessionManager sessionManager, HeaderFactory headerFactory,
            AddressFactory addressFactory, SipFactory sipFactory, MessageFactory messageFactory,
            SipProvider sipProvider, SessionKeepAlive sessionKeepAlive, RtpMediaManager rtpMediaManager) {
        this.sessionManager = sessionManager;
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
        this.sipFactory = sipFactory;
        this.messageFactory = messageFactory;
        this.sipProvider = sipProvider;
        this.sessionKeepAlive = sessionKeepAlive;
        this.rtpMediaManager = rtpMediaManager;
        this.processors = new HashMap<>();
        initProcessors();
    }

    private void initProcessors() {
        processors.put(Request.BYE, new ByeResponseProcessor(sessionManager, rtpMediaManager));
        processors.put(Request.CANCEL, new CancelResponseProcessor(sessionManager, rtpMediaManager));
        processors.put(Request.INFO, new InfoResponseProcessor(sessionManager, rtpMediaManager));
        processors.put(Request.UPDATE, new UpdateResponseProcessor(sessionManager, sessionKeepAlive, rtpMediaManager));
        processors.put(Request.INVITE, new InviteResponseProcessor(sessionManager, headerFactory, addressFactory, sipProvider,
                        sipFactory, messageFactory, sessionKeepAlive, rtpMediaManager));
        processors.put(Request.REGISTER, new RegisterResponseProcessor(sessionManager, headerFactory, addressFactory,
                sipProvider, sipFactory, messageFactory, rtpMediaManager));
    }

    @Override
    public void process(ResponseEvent evt) throws Exception {
        Response response = evt.getResponse();
        Dialog dialog = evt.getDialog();
        log.info("dialog: {}", dialog);
        String clientId = extractClientId(evt);
        if (response == null) {
            log.warn("[{}] 收到空响应", clientId);
            return;
        }
        CSeqHeader cseqHeader = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        if (cseqHeader == null) {
            log.warn("[{}] 响应中缺少CSeq头", clientId);
            return;
        }
        String method = cseqHeader.getMethod();
        int statusCode = response.getStatusCode();
        // 处理临时响应
        if (statusCode < 200) {
            handleProvisionalResponse(evt, clientId, method, statusCode);
            return;
        }
        // 处理最终响应
        handleFinalResponse(evt, clientId, method, statusCode);
    }

    private void handleProvisionalResponse(ResponseEvent evt, String clientId, String method, int statusCode) {
        log.info("[{}] 收到临时响应: {} {}", clientId, statusCode, method);
        try {
            if (method.equals(Request.INVITE)) {
                // 对INVITE请求的临时响应特殊处理
                processors.get(Request.INVITE).process(evt);
            }
        } catch (Exception e) {
            log.error("[{}] 处理临时响应异常: {} {}", clientId, statusCode, method, e);
        }
    }

    private void handleFinalResponse(ResponseEvent evt, String clientId, String method, int statusCode) {
        log.info("[{}] 收到最终响应: {} {}", clientId, statusCode, method);
        try {
            ISipResponseProcessor processor = processors.getOrDefault(method,
                    new OtherResponseProcessor(sessionManager, rtpMediaManager));
            processor.process(evt);
        } catch (Exception e) {
            log.error("[{}] 处理最终响应异常: {} {}", clientId, statusCode, method, e);
        }
    }

    protected String extractClientId(ResponseEvent evt) {
        Response response = evt.getResponse();
        ToHeader toHeader = (ToHeader) response.getHeader(ToHeader.NAME);
        if (toHeader != null) {
            return toHeader.getAddress().getURI().toString();
        }
        return "unknown-client";
    }
}
