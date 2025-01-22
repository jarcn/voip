package com.kupu.sip.modules.call.response.impl;

import javax.sip.ResponseEvent;
import javax.sip.message.Response;
import javax.sip.Dialog;

import org.springframework.stereotype.Component;

import com.kupu.sip.modules.call.response.AbstractResponseProcessor;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ByeResponseProcessor extends AbstractResponseProcessor {

    public ByeResponseProcessor(SessionManager sessionManager, RtpMediaManager rtpMediaManager) {
        super(sessionManager, rtpMediaManager);
    }

    @Override
    public void process(ResponseEvent evt) throws Exception {
        Response response = evt.getResponse();
        String clientId = extractClientId(evt);
        Dialog dialog = evt.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.info("[{}] 处理BYE响应: {} callId: {}", clientId, response.getStatusCode(), callId);
            updateSessionStatus(clientId, evt);
        }
    }

    @Override
    protected void updateStatus(SipSession session, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            session.updateStatus(SipSession.SessionStatus.DISCONNECTED);
        } else if (statusCode >= 300) {
            session.updateStatus(SipSession.SessionStatus.FAILED);
        }
    }
}
