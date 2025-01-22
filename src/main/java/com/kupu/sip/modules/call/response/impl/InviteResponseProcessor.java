package com.kupu.sip.modules.call.response.impl;

import java.util.HashMap;
import java.util.Map;

import javax.sdp.Connection;
import javax.sdp.MediaDescription;
import javax.sdp.SdpFactory;
import javax.sdp.SessionDescription;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.kupu.sip.modules.call.response.AbstractResponseProcessor;
import com.kupu.sip.modules.session.SessionKeepAlive;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import com.kupu.sip.modules.media.RtpMediaManager;

import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InviteResponseProcessor extends AbstractResponseProcessor {
    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;
    private final SipProvider sipProvider;
    private final SessionKeepAlive keepAlive;

    public InviteResponseProcessor(SessionManager sessionManager, HeaderFactory headerFactory,
                                   AddressFactory addressFactory, SipProvider sipProvider, SipFactory sipFactory,
                                   MessageFactory messageFactory, SessionKeepAlive keepAlive, RtpMediaManager rtpMediaManager) {
        super(sessionManager, rtpMediaManager);
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
        this.sipProvider = sipProvider;
        this.keepAlive = keepAlive;
    }

    @Override
    public void process(ResponseEvent evt) throws Exception {
        Response response = evt.getResponse();
        String clientId = extractClientId(evt);
        Dialog dialog = evt.getDialog();
        int statusCode = response.getStatusCode();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.info("[{}] 处理INVITE响应: {} callId: {}", clientId, statusCode, callId);
            // 1. 处理临时响应
            if (statusCode < 200) {
                handleProvisionalResponse(evt, clientId, statusCode);
                return;
            }
            // 2. 处理最终响应
            if (statusCode < 300) {
                handleSuccessResponse(evt, clientId);
            } else if (statusCode < 400) {
                handleRedirectResponse(evt, clientId);
            } else {
                handleErrorResponse(evt, clientId);
            }
        }
    }

    @Override
    protected void updateStatus(SipSession session, int statusCode) {
        switch (statusCode) {
            case Response.TRYING:
                session.updateStatus(SipSession.SessionStatus.INVITING);
                break;
            case Response.RINGING:
            case Response.SESSION_PROGRESS:
                session.updateStatus(SipSession.SessionStatus.RINGING);
                break;
            case Response.OK:
                session.updateStatus(SipSession.SessionStatus.CONNECTED);
                break;
            default:
                if (statusCode >= 300) {
                    session.updateStatus(SipSession.SessionStatus.FAILED);
                }
        }
    }

    private void handleProvisionalResponse(ResponseEvent evt, String clientId, int statusCode) {
        Dialog dialog = evt.getDialog();
        String callId = dialog.getCallId().getCallId();
        switch (statusCode) {
            case Response.TRYING: // 100
                log.info("[{}] 收到100 Trying响应 callId: {}", clientId, callId);
                updateSessionStatus(clientId, evt);
                break;
            case Response.RINGING: // 180
                log.info("[{}] 收到180 Ringing响应 callId: {}", clientId, callId);
                updateSessionStatus(clientId, evt);
                startSessionRefresh(evt, dialog, clientId);// 发送UPDATE请求进行会话保活协商
                break;
            case Response.SESSION_PROGRESS: // 183
                log.info("[{}] 收到183 Session Progress响应 callId: {}", clientId, callId);
                updateSessionStatus(clientId, evt);
                break;
            default:
                log.info("[{}] 收到其他临时响应: {} callId: {}", clientId, statusCode, callId);
                break;
        }
    }

    private void handleSuccessResponse(ResponseEvent evt, String clientId) throws Exception {
        Response response = evt.getResponse();
        ContentTypeHeader contentTypeHeader = (ContentTypeHeader) response.getHeader(ContentTypeHeader.NAME);
        String type = contentTypeHeader.getContentType() + "/" + contentTypeHeader.getContentSubType();
        if (type.equals("application/sdp")) {
            sendAck(evt, clientId);
            handleSdpAnswer(evt);
        } else {
            sendAck(evt, clientId);
        }
    }

    private void startSessionRefresh(ResponseEvent evt, Dialog dialog, String clientId) {
        String callId = dialog.getCallId().getCallId();
        try {
            // 1. 创建UPDATE请求
            Request updateRequest = dialog.createRequest(Request.UPDATE);
            // 2. 添加Min-SE头部（最小会话过期时间）
            int minSE = 90; // 90秒
            Header minSEHeader = headerFactory.createHeader("Min-SE", String.valueOf(minSE));
            updateRequest.addHeader(minSEHeader);
            // 3. 添加Session-Expires头部
            int sessionExpires = 1800; // 30分钟
            Header sessionExpiresHeader = headerFactory.createHeader("Session-Expires",
                    sessionExpires + ";refresher=uac;min-se=" + minSE);
            updateRequest.addHeader(sessionExpiresHeader);
            // 4. 添加Supported头部
            Header supportedHeader = headerFactory.createHeader("Supported", "timer");
            updateRequest.addHeader(supportedHeader);
            // 5. 发送UPDATE请求
            ClientTransaction ct = sipProvider.getNewClientTransaction(updateRequest);
            dialog.sendRequest(ct);
            // 6. 更新会话状态
            SipSession session = sessionManager.getSession(clientId, callId);
            if (session != null) {
                session.setSessionExpires(sessionExpires);
                session.setRefresher("uac"); // 初始设置为UAC
            }
            log.info("[{}] 发起会话保活协商 callId: {}, expires: {}s", clientId, callId, sessionExpires);
        } catch (Exception e) {
            log.error("[{}] 发起会话保活协商失败 callId: {}", clientId, callId, e);
        }
    }

    private void handleRedirectResponse(ResponseEvent evt, String clientId) {
        Response response = evt.getResponse();
        Dialog dialog = evt.getDialog();
        String callId = dialog.getCallId().getCallId();
        // 处理重定向响应（3xx）
        ContactHeader contactHeader = (ContactHeader) response.getHeader(ContactHeader.NAME);
        if (contactHeader != null) {
            Address newAddress = contactHeader.getAddress();
            log.info("[{}] 呼叫重定向到新地址: {} callId: {}", clientId, newAddress, callId);
        }
        updateSessionStatus(clientId, evt);
    }

    private void handleErrorResponse(ResponseEvent evt, String clientId) {
        Response response = evt.getResponse();
        Dialog dialog = evt.getDialog();
        String callId = dialog.getCallId().getCallId();
        int statusCode = response.getStatusCode();
        log.error("[{}] INVITE失败: {} callId: {}", clientId, statusCode, callId);
        // 1. 停止会话保活
        keepAlive.stopKeepAlive(clientId, callId);
        // 2. 处理特定错误码
        switch (statusCode) {
            case Response.BUSY_HERE: // 486
            case Response.BUSY_EVERYWHERE: // 600
                log.info("[{}] 被叫忙 callId: {}", clientId, callId);
                break;
            case Response.REQUEST_TIMEOUT: // 408
                log.info("[{}] 请求超时 callId: {}", clientId, callId);
                break;
            case Response.TEMPORARILY_UNAVAILABLE: // 480
                log.info("[{}] 临时不可用 callId: {}", clientId, callId);
                break;
            case Response.REQUEST_TERMINATED: // 487
                log.info("[{}] 请求被终止 callId: {}", clientId, callId);
                break;
            default:
                log.info("[{}] 其他错误: {} callId: {}", clientId, statusCode, callId);
        }
        // 3. 更新并清理会话
        Map<String, SipSession> sessions = sessionManager.getClientSessions(clientId);
        if (sessions != null) {
            sessions.values().stream()
                    .filter(session -> callId.equals(session.getCallId()))
                    .findFirst()
                    .ifPresent(session -> {
                        session.updateStatus(SipSession.SessionStatus.FAILED);  // 更新会话状态
                        session.setDialog(null);
                        sessionManager.removeSession(clientId, session.getSessionId());// 移除会话
                    });
        }
    }

    private void handleSdpAnswer(ResponseEvent evt) {
        try {
            Response response = evt.getResponse();
            Dialog dialog = evt.getDialog();
            if (dialog == null) {
                log.error("Dialog is null in handleSdpAnswer");
                return;
            }
            String clientId = extractClientId(evt);
            String callId = dialog.getCallId().getCallId();
            byte[] rawContent = response.getRawContent();
            if (rawContent == null) {
                log.error("SDP content is null for callId: {}", callId);
                return;
            }
            String sdp = new String(rawContent);
            log.info("远端SDP协商信息: {}", sdp);
            SessionDescription sessionDescription = SdpFactory.getInstance().createSessionDescription(sdp);
            MediaDescription audioMedia = (MediaDescription) sessionDescription.getMediaDescriptions(false).get(0);
            // 获取远程地址：优先从媒体级别获取，如果没有则从会话级别获取
            String remoteAddress;
            Connection mediaConnection = audioMedia.getConnection();
            if (mediaConnection != null) {
                remoteAddress = mediaConnection.getAddress();
            } else {
                Connection sessionConnection = sessionDescription.getConnection();
                if (sessionConnection != null) {
                    remoteAddress = sessionConnection.getAddress();
                } else {
                    throw new Exception("No connection information found in SDP");
                }
            }
            int remotePort = audioMedia.getMedia().getMediaPort();
            log.info("远端sdp协商地址信息 remoteAddress: {}, remotePort: {}", remoteAddress, remotePort);
            Map<String, SipSession> sessions = sessionManager.getClientSessions(clientId);
            if (sessions == null) {
                log.error("No sessions found for clientId: {}", clientId);
                return;
            }
            SipSession session = sessions.values().stream()
                    .filter(s -> callId.equals(s.getCallId()))
                    .findFirst()
                    .orElseThrow(() -> new Exception("Session not found for callId: " + callId));
            // 初始化RTP会话
            String localAddress = session.getFromDomain();
            int sdpPort = session.getSdpPort();
            // startRTPSvr(remoteAddress, remotePort, callId, localAddress); //
            // http通知rtp服务启动
            initRTP(localAddress, sdpPort, remoteAddress, remotePort, clientId, callId);
        } catch (Exception e) {
            log.error("处理SDP应答失败", e);
        }
    }

    private void sendAck(ResponseEvent responseEvent, String clientId) throws Exception {
        Response response = responseEvent.getResponse();
        Dialog dialog = responseEvent.getDialog();
        if (dialog == null) {
            log.error("[{}] Dialog is null. Cannot send ACK", clientId);
            throw new SipException("Dialog is null");
        }
        String callId = dialog.getCallId().getCallId();
        SipSession session = sessionManager.getClientSessions(clientId)
                .values().stream().filter(s -> callId.equals(s.getCallId())).findFirst()
                .orElseThrow(() -> new SipException("Session not found for callId: " + callId));
        try {
            Request ackRequest = dialog.createAck(((CSeqHeader) response.getHeader(CSeqHeader.NAME)).getSeqNumber());
            ackRequest.removeHeader(ViaHeader.NAME);// 更新Via头
            String branchId = UUID.randomUUID().toString().substring(0, 8);
            int localPort = sipProvider.getListeningPoint("udp").getPort(); // 获取本地端口
            ViaHeader viaHeader = headerFactory.createViaHeader(session.getFromDomain(), localPort, "udp", branchId);
            ackRequest.addHeader(viaHeader);
            ackRequest.removeHeader(ContactHeader.NAME); // 更新Contact头
            SipURI contactURI = addressFactory.createSipURI(session.getFromUser(),
                    session.getFromDomain() + ":" + localPort);
            Address contactAddress = addressFactory.createAddress(contactURI);
            ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
            ackRequest.addHeader(contactHeader);
            dialog.sendAck(ackRequest); // 发送ACK
            log.info("[{}] 发送ACK请求: {} callId: {}", clientId, ackRequest, callId);
        } catch (Exception e) {
            log.error("[{}] 发送ACK失败 callId: {}", clientId, callId, e);
            session.updateStatus(SipSession.SessionStatus.FAILED);
            throw e;
        }
    }

    private void startRTPSvr(String remoteIp, int remotePort, String callId, String wsUrl) {
        Map<String, Object> para = new HashMap<>();
        para.put("call_id", callId);
        para.put("ip", remoteIp);
        para.put("port", remotePort);
        para.put("robot_ws_url", "");
        String jsonStr = JSONUtil.toJsonStr(para);
        log.info("通知rtp服务启动请求参数:{}", jsonStr);
        String result = HttpUtil.post("http://192.168.0.4:20009/start_call", jsonStr);
        log.info("通知rtp服务启动响应参数:{}", result);
    }

    private void initRTP(String localAddress, int sdpPort, String remoteAddress, int remotePort, String clientId,
                         String callId) {
        rtpMediaManager.initializeRtpSession(localAddress, sdpPort, remoteAddress, remotePort);
        rtpMediaManager.startMediaSession();
        log.info("[{}] 媒体会话已建立 本地: {}:{}, 远程: {}:{}, callId: {}", clientId, localAddress, sdpPort, remoteAddress,
                remotePort, callId);
        rtpMediaManager.playAudioFile("/data/test/tt.wav"); // 播放欢迎语
    }
}
