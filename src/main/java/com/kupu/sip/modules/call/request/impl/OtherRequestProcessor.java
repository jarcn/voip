package com.kupu.sip.modules.call.request.impl;

import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import lombok.extern.slf4j.Slf4j;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.CSeqHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

@Slf4j
public class OtherRequestProcessor extends AbstractRequestProcessor {

    public OtherRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
                                 MessageFactory messageFactory, SipProvider sipProvider, SipFactory sipFactory) {
        super(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory);
    }

    @Override
    public void process(RequestEvent evt) throws Exception {
        Request request = evt.getRequest();
        ServerTransaction serverTransaction = evt.getServerTransaction();
        String clientId = extractClientId(evt);
        // 获取请求方法
        String method = request.getMethod();
        // 获取CSeq
        CSeqHeader cseqHeader = (CSeqHeader) request.getHeader(CSeqHeader.NAME);
        long cseq = cseqHeader != null ? cseqHeader.getSeqNumber() : -1;
        log.info("[{}] 收到未知请求: {} CSeq: {}", clientId, method, cseq);
        // 如果没有服务器事务，创建一个
        if (serverTransaction == null) {
            try {
                serverTransaction = sipProvider.getNewServerTransaction(request);
            } catch (TransactionAlreadyExistsException e) {
                log.warn("[{}] 事务已存在: {} CSeq: {}", clientId, method, cseq);
                return;
            } catch (TransactionUnavailableException e) {
                log.error("[{}] 无法创建事务: {} CSeq: {}", clientId, method, cseq, e);
                return;
            }
        }

        Dialog dialog = serverTransaction.getDialog();
        String callId = dialog != null ? dialog.getCallId().getCallId() : "unknown";
        try {
            // 检查请求是否在已建立的会话中
            if (dialog != null && sessionManager.getSession(clientId, callId) != null) {
                // 对于未知请求，但在已有会话中的情况，返回200 OK
                log.info("[{}] 在已有会话中处理未知请求: {} callId: {}", clientId, method, callId);
                Response response = messageFactory.createResponse(Response.OK, request);
                serverTransaction.sendResponse(response);
            } else {
                // 对于未知请求且没有会话的情况，返回501 Not Implemented
                log.warn("[{}] 收到不支持的请求方法: {} callId: {}", clientId, method, callId);
                Response response = messageFactory.createResponse(Response.NOT_IMPLEMENTED, request);
                serverTransaction.sendResponse(response);
            }
        } catch (Exception e) {
            log.error("[{}] 处理未知请求失败: {} callId: {}", clientId, method, callId, e);
            // 发送500 Server Internal Error响应
            try {
                Response response = messageFactory.createResponse(Response.SERVER_INTERNAL_ERROR, request);
                serverTransaction.sendResponse(response);
            } catch (Exception ex) {
                log.error("[{}] 发送错误响应失败: {} callId: {}", clientId, method, callId, ex);
            }
        }
    }
}