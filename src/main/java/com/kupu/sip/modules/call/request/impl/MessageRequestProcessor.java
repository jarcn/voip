package com.kupu.sip.modules.call.request.impl;

import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import lombok.extern.slf4j.Slf4j;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

@Slf4j
public class MessageRequestProcessor extends AbstractRequestProcessor {

    public MessageRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
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

        log.info("[{}] 处理MESSAGE请求", clientId);

        // 处理消息内容
        processMessageContent(request);

        // 发送200 OK响应
        Response response = messageFactory.createResponse(Response.OK, request);
        serverTransaction.sendResponse(response);
        log.info("[{}] 发送200 OK响应", clientId);
    }

    private void processMessageContent(Request request) {
        // 处理MESSAGE请求中的具体内容
        // 例如文本消息、即时消息等
    }
}