package com.kupu.sip.modules.call.client;

import com.kupu.sip.modules.call.request.RequestProcessorProxy;
import com.kupu.sip.modules.call.response.ResponseProcessorProxy;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionKeepAlive;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ToHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import java.util.Map;

@Slf4j
public class SipListenerImpl implements SipListener {

    private final String clientId;
    private final SessionManager sessionManager;
    private final RtpMediaManager rtpMediaManager;
    private final RequestProcessorProxy requestProcessorProxy;
    private final ResponseProcessorProxy responseProcessorProxy;

    public SipListenerImpl(String clientId, SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory, SipFactory sipFactory, MessageFactory messageFactory, SipProvider sipProvider, SessionKeepAlive sessionKeepAlive, RtpMediaManager rtpMediaManager) {
        this.clientId = clientId;
        this.sessionManager = sessionManager;
        this.rtpMediaManager = rtpMediaManager;
        this.requestProcessorProxy = new RequestProcessorProxy(sessionManager, headerFactory, addressFactory, sipFactory, messageFactory, sipProvider, sessionKeepAlive, rtpMediaManager);
        this.responseProcessorProxy = new ResponseProcessorProxy(sessionManager, headerFactory, addressFactory, sipFactory, messageFactory, sipProvider, sessionKeepAlive, rtpMediaManager);
    }

    /**
     * SIP服务端接收消息的方法 Content 里面是GBK编码 This method is called by the SIP stack when a
     * new request arrives.
     */
    @Override
    public void processRequest(RequestEvent requestEvent) {
        try {
            log.info("[{}] Processing request", clientId);
            requestProcessorProxy.process(requestEvent);
        } catch (Exception e) {
            log.error("[{}] processRequest error", clientId, e);
        }
    }

    @Override
    public void processResponse(ResponseEvent responseEvent) {
        try {
            Dialog dialog = responseEvent.getDialog();
            if (dialog != null) {
                String callId = dialog.getCallId().getCallId();
                log.info("[{}] Processing response for call: {}", clientId, callId);
                updateSessionStatus(responseEvent);
            }
            responseProcessorProxy.process(responseEvent);
        } catch (Exception e) {
            log.error("[{}] processResponse error", clientId, e);
        }
    }

    private void updateSessionStatus(ResponseEvent responseEvent) {
        Response response = responseEvent.getResponse();
        Dialog dialog = responseEvent.getDialog();
        if (dialog != null && sessionManager != null) {
            String callId = dialog.getCallId().getCallId();
            int status = response.getStatusCode();
            // 遍历查找对应的session
            sessionManager.getClientSessions(clientId).values().stream()
                    .filter(session -> callId.equals(session.getCallId()))
                    .findFirst()
                    .ifPresent(session -> {
                        if (status == Response.TRYING) {
                            session.updateStatus(SipSession.SessionStatus.INVITING);
                        } else if (status == Response.RINGING) {
                            session.updateStatus(SipSession.SessionStatus.RINGING);
                        } else if (status == Response.OK) {
                            session.updateStatus(SipSession.SessionStatus.CONNECTED);
                        }
                        session.setDialog(dialog);
                    });
        }
    }

    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
        String clientId = extractClientId(timeoutEvent);
        Transaction transaction = timeoutEvent.getClientTransaction();
        if (transaction != null) {
            Dialog dialog = transaction.getDialog();
            if (dialog != null) {
                String callId = dialog.getCallId().getCallId();
                log.warn("[{}] 事务超时 callId: {}", clientId, callId);
                // 处理超时
                handleTransactionTimeout(clientId, callId, transaction);
            }
        }
    }

    @Override
    public void processIOException(IOExceptionEvent exceptionEvent) {
        log.error("[{}] I/O Exception: {}", clientId, exceptionEvent.getHost());
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
        log.info("[{}] Transaction terminated", clientId);
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {
        Dialog dialog = dialogTerminatedEvent.getDialog();
        String callId = dialog.getCallId().getCallId();
        log.info("[{}] Dialog terminated: {}", clientId, callId);
        // 查找并清理相关会话
        Map<String, SipSession> sessions = sessionManager.getClientSessions(clientId);
        if (sessions != null) {
            sessions.values().stream()
                .filter(session -> callId.equals(session.getCallId()))
                .findFirst()
                .ifPresent(session -> {
                    // 更新会话状态
                    session.updateStatus(SipSession.SessionStatus.DISCONNECTED);
                    session.setDialog(null);
                    // 移除会话
                    sessionManager.removeSession(clientId, session.getSessionId());
                });
        }
    }

    private void handleTransactionTimeout(String clientId, String callId, Transaction transaction) {
        try {
            // 获取会话
            SipSession session = sessionManager.getClientSessions(clientId).values().stream().filter(s -> callId.equals(s.getCallId())).findFirst().orElse(null);
            if (session != null) {
                // 如果是INVITE事务超时，更新会话状态
                if (transaction instanceof ClientTransaction && transaction.getRequest().getMethod().equals(Request.INVITE)) {
                    session.updateStatus(SipSession.SessionStatus.FAILED);
                    sessionManager.removeSession(clientId, session.getSessionId());
                }
            }
        } catch (Exception e) {
            log.error("[{}] 处理事务超时异常 callId: {}", clientId, callId, e);
        }
    }

    private String extractClientId(TimeoutEvent evt) {
        Transaction transaction = evt.isServerTransaction() ? evt.getServerTransaction() : evt.getClientTransaction();
        if (transaction != null && transaction.getDialog() != null) {
            ToHeader toHeader = (ToHeader) transaction.getRequest().getHeader(ToHeader.NAME);
            if (toHeader != null) {
                return toHeader.getAddress().getURI().toString();
            }
        }
        return "unknown-client";
    }
}