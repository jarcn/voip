package com.kupu.sip.modules.call.request.impl;


import com.kupu.sip.modules.call.request.AbstractRequestProcessor;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sdp.MediaDescription;
import javax.sdp.SdpFactory;
import javax.sdp.SessionDescription;
import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

@Slf4j
public class InviteRequestProcessor extends AbstractRequestProcessor {

    public InviteRequestProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory,
                                  MessageFactory messageFactory, SipProvider sipProvider, SipFactory sipFactory) {
        super(sessionManager, headerFactory, addressFactory, messageFactory, sipProvider, sipFactory);
    }

    @Override
    public void process(RequestEvent evt) throws Exception {
        Request request = evt.getRequest();
        ServerTransaction serverTransaction = evt.getServerTransaction();
        String clientId = extractClientId(evt);
        // 如果没有服务器事务，创建一个
        if (serverTransaction == null) {
            serverTransaction = sipProvider.getNewServerTransaction(request);
        }
        // 发送100 Trying
        Response tryingResponse = messageFactory.createResponse(Response.TRYING, request);
        serverTransaction.sendResponse(tryingResponse);
        log.info("[{}] 发送100 Trying响应", clientId);
        // 解析收到的SDP
        String sdpContent = new String(request.getRawContent());
        SessionDescription remoteSdp = SdpFactory.getInstance().createSessionDescription(sdpContent);
        // 获取远端媒体信息
        MediaDescription audioMedia = (MediaDescription) remoteSdp.getMediaDescriptions(false).get(0);
        int remoteRtpPort = audioMedia.getMedia().getMediaPort();
        String remoteAddress = remoteSdp.getConnection().getAddress();
        log.info("[{}] 远端媒体信息: {}", clientId, remoteAddress);     
        // 检查会话状态
        Dialog dialog = serverTransaction.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            SipSession session = sessionManager.getSession(clientId, callId);
            if (session != null) {
                session.setRemoteAddress(remoteAddress);
                session.setRemotePort(remoteRtpPort);
                session.setLocalAddress(sipProvider.getListeningPoint("udp").getIPAddress());
                session.setLocalPort(49170); // 使用配置的端口
                // 初始化媒体会话
                session.initializeMediaSession();
                // 发送180 Ringing
                Response ringingResponse = messageFactory.createResponse(Response.RINGING, request);
                serverTransaction.sendResponse(ringingResponse);
                log.info("[{}] 发送180 Ringing响应 callId: {}", clientId, callId);
                // 发送200 OK with SDP
                Response okResponse = messageFactory.createResponse(Response.OK, request);
                // 添加本地SDP
                String localSdp = createLocalSdp(session);
                okResponse.setContent(localSdp.getBytes(),headerFactory.createContentTypeHeader("application", "sdp"));
                serverTransaction.sendResponse(okResponse);
                log.info("[{}] 发送200 OK响应 callId: {}", clientId, callId);
                session.updateStatus(SipSession.SessionStatus.CONNECTED);
                log.info("[{}] 会话状态更新为CONNECTED callId: {}", clientId, callId);
            } else {
                // 如果会话不存在，发送404 Not Found
                sendResponse(evt, Response.NOT_FOUND);
                log.warn("[{}] 会话不存在 callId: {}", clientId, callId);
            }
        }
    }

    private String createLocalSdp(SipSession session) {
        return "v=0\r\n" +
                "o=- " + System.currentTimeMillis() + " " + System.currentTimeMillis() +
                " IN IP4 " + session.getLocalAddress() + "\r\n" +
                "s=Voice Call\r\n" +
                "c=IN IP4 " + session.getLocalAddress() + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + session.getLocalPort() + " RTP/AVP 0 8 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                "a=fmtp:101 0-15\r\n" +
                "a=ptime:20\r\n" +
                "a=sendrecv\r\n";
    }
}
