package com.kupu.sip.modules.call.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.ListeningPoint;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.AcceptHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentLengthHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.SupportedHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.UserAgentHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;

import org.slf4j.MDC;

import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionKeepAlive;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.thread.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JainSipClient {

    private final String ip;
    private final int port;
    private final String clientId;
    private final String uasHost;
    private final ExecutorService executorService;
    private final SessionManager sessionManager;
    private final RtpMediaManager rtpMediaManager;
    private SipFactory sipFactory;
    private AddressFactory addressFactory;
    private HeaderFactory headerFactory;
    private MessageFactory messageFactory;
    private SipProvider sipProvider;
    private SessionKeepAlive sessionKeepAlive;
    private SipListener sipListener; // 存储 SipListener 引用

    public JainSipClient(String clientId, String ip, int port, String uasHost, SessionManager sessionManager,
            RtpMediaManager rtpMediaManager) {
        this.clientId = clientId; // 当前外呼客户端ID
        this.ip = ip; // 客户端IP
        this.port = port; // 客户端端口
        this.uasHost = uasHost;
        this.sessionManager = sessionManager; // 会话管理器
        this.rtpMediaManager = rtpMediaManager;
        this.executorService = new ThreadPoolExecutor(5, 20, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new ThreadFactoryBuilder().setNamePrefix("sip-client-" + clientId + "-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void init(String clientId) throws Exception {
        MDC.put("clientId", clientId);
        Properties prop = new Properties();
        prop.setProperty("javax.sip.STACK_NAME", "KupuSIP");
        prop.setProperty("javax.sip.IP_ADDRESS", ip);
        prop.setProperty("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY",
                "gov.nist.javax.sip.stack.NioMessageProcessorFactory"); // UDP消息处理相关配置
        prop.setProperty("gov.nist.javax.sip.REENTRANT_LISTENER", "true");
        prop.setProperty("gov.nist.javax.sip.THREAD_POOL_SIZE", "16");
        prop.setProperty("gov.nist.javax.sip.MAX_MESSAGE_SIZE", "1048576");
        prop.setProperty("gov.nist.javax.sip.AGGRESSIVE_CLEANUP", "true");
        prop.setProperty("gov.nist.javax.sip.TCP_POST_PARSING_THREAD_POOL_SIZE", "30");
        prop.setProperty("gov.nist.javax.sip.STUN_SERVER", "211.144.80.66:3478"); // todo NAT 穿透问题
        prop.setProperty("gov.nist.javax.sip.NAT_TRAVERSAL", "true");
        sipFactory = SipFactory.getInstance();
        sipFactory.setPathName("gov.nist");
        SipStack sipStack = sipFactory.createSipStack(prop);
        headerFactory = sipFactory.createHeaderFactory();
        addressFactory = sipFactory.createAddressFactory();
        messageFactory = sipFactory.createMessageFactory();
        ListeningPoint udpListeningPoint = sipStack.createListeningPoint(ip, port, "udp"); // UDP监听点
        sipProvider = sipStack.createSipProvider(udpListeningPoint);
        sessionKeepAlive = new SessionKeepAlive(sipProvider, headerFactory);
        sipListener = new SipListenerImpl(clientId, sessionManager, headerFactory, addressFactory, sipFactory,
                messageFactory, sipProvider, sessionKeepAlive, rtpMediaManager);
        sipProvider.addSipListener(sipListener);
        log.info("[{}] SIP客户端初始化完成，监听地址: {}:{}", clientId, ip, port);
    }

    public CompletableFuture<String> inviteAsync(Integer sdpPort, String fromUser, String fromDomain, String toUser, String toDomain) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sessionId = String.valueOf(System.currentTimeMillis());
                SipSession session = sessionManager.createSession(clientId, sessionId);
                session.setSdpPort(sdpPort);
                session.setFromUser(fromUser);
                session.setFromDomain(fromDomain);
                session.setToUser(toUser);
                session.setToDomain(toDomain);
                session.updateStatus(SipSession.SessionStatus.INVITING);
                this.invite(session);
                return sessionId;
            } catch (Exception e) {
                log.error("Async invite failed", e);
                throw new CompletionException(e);
            }
        }, executorService);
    }

    public String inviteSync(Integer sdpPort, String fromUser, String fromDomain, String toUser, String toDomain) {
        try {
            String sessionId = String.valueOf(System.currentTimeMillis());
            SipSession session = sessionManager.createSession(clientId, sessionId);
            session.setSdpPort(sdpPort);
            session.setFromUser(fromUser);
            session.setFromDomain(fromDomain);
            session.setToUser(toUser);
            session.setToDomain(toDomain);
            session.updateStatus(SipSession.SessionStatus.INVITING);
            this.invite(session);
            return sessionId;
        } catch (Exception e) {
            log.error("sync invite failed", e);
            throw new CompletionException(e);
        }
    }

    public void register(String fromUser, String fromDomain, Integer fromPort) throws Exception {
        String branchId = UUID.randomUUID().toString().substring(0, 8);
        SipURI requestUri = addressFactory.createSipURI(null, uasHost);
        ViaHeader viaHeader = headerFactory.createViaHeader(fromDomain, fromPort, "udp", branchId);
        viaHeader.setRPort();
        List<ViaHeader> viaHeaders = new ArrayList<>();
        viaHeaders.add(viaHeader);
        SipURI fromUri = addressFactory.createSipURI(fromUser, uasHost);
        fromUri.setPort(fromPort);
        Address fromAddress = addressFactory.createAddress(fromUri);
        fromAddress.setDisplayName(fromUser);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, branchId);
        SipURI toUri = addressFactory.createSipURI(fromUser, uasHost);
        toUri.setPort(fromPort);
        Address toAddress = addressFactory.createAddress(toUri);
        toAddress.setDisplayName(fromUser);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.REGISTER);
        CallIdHeader callIdHeader = headerFactory.createCallIdHeader(branchId);
        MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
        SupportedHeader supportedHeader = headerFactory.createSupportedHeader("replaces,outbound,gruu,path,record-aware");
        AcceptHeader acceptHeader = headerFactory.createAcceptHeader("application", "sdp");
        SipURI contactUri = addressFactory.createSipURI(fromUser, fromDomain + ":" + fromPort);
        contactUri.setTransportParam("udp");
        Address contacAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contacAddress);
        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(600);
        Request request = messageFactory.createRequest(requestUri, Request.REGISTER, callIdHeader, cSeqHeader,
                fromHeader, toHeader, viaHeaders, maxForwardsHeader);
        request.addHeader(expiresHeader);
        request.addHeader(supportedHeader);
        request.addHeader(acceptHeader);
        request.addHeader(contactHeader);
        sipProvider.sendRequest(request);
    }

    private void invite(SipSession session) {
        try {
            String fromUser = session.getFromUser();
            String fromDomain = session.getFromDomain();
            String toUser = session.getToUser();
            String toDomain = session.getToDomain();
            String branchId = UUID.randomUUID().toString().substring(0, 8);
            // 创建请求URI
            SipURI requestURI = addressFactory.createSipURI(toUser, toDomain);
            // 创建From头部
            SipURI fromURI = addressFactory.createSipURI(fromUser, fromDomain);
            Address fromAddress = addressFactory.createAddress(fromURI);
            FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, branchId);
            // 创建To头部
            SipURI toURI = addressFactory.createSipURI(toUser, toDomain);
            Address toAddress = addressFactory.createAddress(toURI);
            ToHeader toHeader = headerFactory.createToHeader(toAddress, null);
            // 创建Via头部
            ViaHeader viaHeader = headerFactory.createViaHeader(fromDomain, port, "udp", branchId);
            viaHeader.setRPort(); // 添加rport参数
            viaHeader.setReceived(fromDomain);
            List<ViaHeader> viaHeaders = new ArrayList<>();
            viaHeaders.add(viaHeader);
            // 创建其他必要头部
            CallIdHeader callIdHeader = sipProvider.getNewCallId();
            session.setCallId(callIdHeader.getCallId());
            CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.INVITE);
            MaxForwardsHeader maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
            // 创建INVITE请求
            Request request = messageFactory.createRequest(requestURI, Request.INVITE, callIdHeader, cSeqHeader,
                    fromHeader, toHeader, viaHeaders, maxForwardsHeader);
            // 添加Contact头部
            SipURI contactURI = addressFactory.createSipURI(fromUser, fromDomain);
            contactURI.setPort(port);
            contactURI.setTransportParam("udp");
            Address contactAddress = addressFactory.createAddress(contactURI);
            ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
            request.addHeader(contactHeader);
            // Session-Expires头部：设置会话超时时间为1800秒(30分钟)，并指定UAC作为刷新方
            javax.sip.header.Header sessionExpiresHeader = headerFactory.createHeader("Session-Expires","1800;refresher=uac");
            request.addHeader(sessionExpiresHeader);
            // Supported头部：指示支持timer特性
            javax.sip.header.Header supportedHeader = headerFactory.createHeader("Supported", "timer");
            request.addHeader(supportedHeader);
            // 添加SDP消息体
            String sdpDescribe = this.sdpDescribe(session.getSdpPort(), fromDomain, session.getSessionId());
            byte[] contents = sdpDescribe.getBytes();
            ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp");
            request.setContent(contents, contentTypeHeader);
            ContentLengthHeader contentLengthHeader = headerFactory.createContentLengthHeader(contents.length);
            request.setContentLength(contentLengthHeader);
            // 发送请求
            ClientTransaction transaction = sipProvider.getNewClientTransaction(request);
            transaction.sendRequest();
            log.info("[{}] 发送INVITE请求: {}", clientId, request);
        } catch (Exception e) {
            log.error("[{}] 发送INVITE请求失败", clientId, e);
            throw new RuntimeException(e);
        }
    }

    private String sdpDescribe(int sdpPort, String fromDomain, String sessionId) {
        return "v=0\r\n" +
                "o=- " + sessionId + " " + sessionId + " IN IP4 " + fromDomain + "\r\n" +
                "s=" + "VOS3000" + "\r\n" +
                "c=IN IP4 " + fromDomain + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + sdpPort + " RTP/AVP 8 0 101\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                "a=fmtp:101 0-15\r\n" +
                "a=ptime:20\r\n" +
                "a=sendrecv\r\n";
    }

    public void destroy() {
        try {
            // 1. 关闭会话保活服务
            if (sessionKeepAlive != null) {
                sessionKeepAlive.shutdown();
            }

            // 2. 关闭所有活跃会话
            Map<String, SipSession> sessions = sessionManager.getClientSessions(clientId);
            if (sessions != null && !sessions.isEmpty()) {
                for (SipSession session : sessions.values()) {
                    try {
                        if (session.getStatus() == SipSession.SessionStatus.CONNECTED) {
                            sendBye(session); // 发送 BYE 请求
                        }
                        session.updateStatus(SipSession.SessionStatus.DISCONNECTED);
                    } catch (Exception e) {
                        log.error("[{}] 关闭会话失败 sessionId: {}", clientId, session.getSessionId(), e);
                    }
                }
            }

            // 3. 关闭线程池
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // 4. 关闭 SIP 栈
            if (sipProvider != null) {
                try {
                    // 获取 SIP 栈
                    SipStack sipStack = sipProvider.getSipStack();
                    if (sipStack != null) {
                        // 移除 SipListener
                        if (sipListener != null) {
                            sipProvider.removeSipListener(sipListener);
                        }
                        // 移除所有监听点
                        Iterator<ListeningPoint> listeningPoints = sipStack.getListeningPoints();
                        while (listeningPoints.hasNext()) {
                            ListeningPoint listeningPoint = listeningPoints.next();
                            sipProvider.removeListeningPoint(listeningPoint);
                            sipStack.deleteListeningPoint(listeningPoint);
                        }
                        // 移除 SIP Provider
                        sipStack.deleteSipProvider(sipProvider);
                        // 停止 SIP 栈
                        sipStack.stop();
                        sipFactory.resetFactory();
                    }
                } catch (Exception e) {
                    log.error("[{}] 关闭SIP栈失败", clientId, e);
                }
            }

            // 5. 清理会话管理器中的会话
            sessionManager.removeClientSessions(clientId);

            log.info("[{}] SIP客户端已销毁", clientId);
        } catch (Exception e) {
            log.error("[{}] 销毁SIP客户端失败", clientId, e);
            throw new RuntimeException("销毁SIP客户端失败", e);
        }
    }

    private void sendBye(SipSession session) throws Exception {
        Dialog dialog = session.getDialog();
        if (dialog == null) {
            log.warn("[{}] 无法发送BYE请求:dialog为空 sessionId: {}", clientId, session.getSessionId());
            return;
        }
        try {
            Request byeRequest = dialog.createRequest(Request.BYE);
            // 添加必要的头部
            MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
            byeRequest.addHeader(maxForwards);
            // 添加User-Agent头部
            UserAgentHeader userAgentHeader = headerFactory.createUserAgentHeader(Arrays.asList("KupuSIP"));
            byeRequest.addHeader(userAgentHeader);
            // 发送BYE请求
            dialog.sendRequest(sipProvider.getNewClientTransaction(byeRequest));
            log.info("[{}] 发送BYE请求 sessionId: {}", clientId, session.getSessionId());
        } catch (Exception e) {
            log.error("[{}] 发送BYE请求失败 sessionId: {}", clientId, session.getSessionId(), e);
            throw e;
        }
    }
}