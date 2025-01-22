package com.kupu.sip.modules.call.response.impl;

import com.kupu.sip.modules.call.response.AbstractResponseProcessor;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.message.Response;

@Slf4j
@Component
public class InfoResponseProcessor extends AbstractResponseProcessor {
    
    public InfoResponseProcessor(SessionManager sessionManager, RtpMediaManager rtpMediaManager) {
        super(sessionManager, rtpMediaManager);
    }
    
    @Override
    public void process(ResponseEvent evt) throws Exception {
        Response response = evt.getResponse();
        String clientId = extractClientId(evt);
        Dialog dialog = evt.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.info("[{}] 处理INFO响应: {} callId: {}", clientId, response.getStatusCode(), callId);
        }
    }
    
    @Override
    protected void updateStatus(SipSession session, int statusCode) {
        // INFO不影响会话状态
    }
}
