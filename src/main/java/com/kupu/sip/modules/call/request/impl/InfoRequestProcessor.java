package com.kupu.sip.modules.call.request.impl;

import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

@Slf4j
public class InfoRequestProcessor extends AbstractRequestProcessor {

    public InfoRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
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

        Dialog dialog = serverTransaction.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.info("[{}] 处理INFO请求 callId: {}", clientId, callId);

            SipSession session = sessionManager.getSession(clientId, callId);
            if (session != null) {
                // 处理INFO请求内容
                processInfoContent(request);
                // 发送200 OK响应
                Response response = messageFactory.createResponse(Response.OK, request);
                serverTransaction.sendResponse(response);
                log.info("[{}] 发送200 OK响应 callId: {}", clientId, callId);
            } else {
                log.warn("[{}] 会话不存在 callId: {}", clientId, callId);
                sendResponse(evt, Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST);
            }
        }
    }

    private void processInfoContent(Request request) {
        // 处理INFO请求中的具体内容
        // 例如DTMF信息等
    }
}