package com.kupu.sip.modules.call.request.impl;

import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.Transaction;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;

import lombok.extern.slf4j.Slf4j;


@Slf4j
public class CancelRequestProcessor extends AbstractRequestProcessor {

    public CancelRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
                                  MessageFactory messageFactory, SipProvider sipProvider, SipFactory sipFactory) {
        super(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory);
    }

    @Override
    public void process(RequestEvent evt) throws Exception {
        Request request = evt.getRequest();
        ServerTransaction serverTransaction = evt.getServerTransaction();
        String clientId = extractClientId(evt);

        if (serverTransaction == null) {
            log.warn("[{}] 没有找到对应的ServerTransaction", clientId);
            return;
        }

        Dialog dialog = serverTransaction.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.info("[{}] 处理CANCEL请求 callId: {}", clientId, callId);

            // 更新会话状态
            SipSession session = sessionManager.getSession(clientId, callId);
            if (session != null) {
                session.updateStatus(SipSession.SessionStatus.CANCELLED);
                // 发送200 OK响应CANCEL
                Response okResponse = messageFactory.createResponse(Response.OK, request);
                serverTransaction.sendResponse(okResponse);
                log.info("[{}] 发送200 OK响应CANCEL callId: {}", clientId, callId);
                // 发送487 Request Terminated响应原始INVITE
                @SuppressWarnings("deprecation")
                Transaction inviteTransaction = serverTransaction.getDialog().getFirstTransaction();
                if (inviteTransaction instanceof ServerTransaction) {
                    Response terminatedResponse = messageFactory.createResponse(Response.REQUEST_TERMINATED, inviteTransaction.getRequest());
                    ((ServerTransaction) inviteTransaction).sendResponse(terminatedResponse);
                    log.info("[{}] 发送487 Request Terminated响应INVITE callId: {}", clientId, callId);
                }

                // 清理会话资源
                sessionManager.removeSession(clientId, callId);
            }
        }
    }
}