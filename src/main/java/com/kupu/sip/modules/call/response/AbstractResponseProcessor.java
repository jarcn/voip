package com.kupu.sip.modules.call.response;

import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.header.ToHeader;
import javax.sip.message.Response;

@Slf4j
public abstract class AbstractResponseProcessor implements ISipResponseProcessor {

    protected final SessionManager sessionManager;
    protected final RtpMediaManager rtpMediaManager;    

    protected AbstractResponseProcessor(SessionManager sessionManager, RtpMediaManager rtpMediaManager) {
        this.sessionManager = sessionManager;
        this.rtpMediaManager = rtpMediaManager;
    }

    protected void updateSessionStatus(String clientId, ResponseEvent responseEvent) {
        Response response = responseEvent.getResponse();
        Dialog dialog = responseEvent.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            int status = response.getStatusCode();
            sessionManager.getClientSessions(clientId).values()
                    .stream().filter(
                            session -> callId.equals(session.getCallId())).findFirst().ifPresent(session -> {
                                updateStatus(session, status);
                                session.setDialog(dialog);
                            }
                    );
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

    protected abstract void updateStatus(SipSession session, int statusCode);
} 