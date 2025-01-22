package com.kupu.sip.modules.call.request.impl;

import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;

import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;

import lombok.extern.slf4j.Slf4j;


@Slf4j
public class AckRequestProcessor extends AbstractRequestProcessor {

    public AckRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
                               MessageFactory messageFactory, SipProvider sipProvider, SipFactory sipFactory) {
        super(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory);
    }


    @Override
    public void process(RequestEvent evt) throws Exception {
        Dialog dialog = evt.getDialog();
        String clientId = extractClientId(evt);
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.info("[{}] 处理ACK请求 callId: {}", clientId, callId);
            SipSession session = sessionManager.getSession(clientId, callId);
            if (session != null && session.getStatus() == SipSession.SessionStatus.CONNECTED) {
                // 启动媒体会话
                session.startMediaSession();
                log.info("[{}] 媒体会话已启动 callId: {}", clientId, callId);
            }
        } else {
            log.warn("[{}] 收到未知的ACK请求", clientId);
        }
    }
}