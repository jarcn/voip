package com.kupu.sip.modules.call.response.impl;

import com.kupu.sip.modules.call.response.AbstractResponseProcessor;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;

import javax.sip.*;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.ArrayList;

@Slf4j
public class RegisterResponseProcessor extends AbstractResponseProcessor {

    private final HeaderFactory headerFactory;
    private final AddressFactory addressFactory;
    private final MessageFactory messageFactory;
    private final SipProvider sipProvider;

    public RegisterResponseProcessor(SessionManager sessionManager, HeaderFactory headerFactory, AddressFactory addressFactory, SipProvider sipProvider, SipFactory sipFactory, MessageFactory messageFactory, RtpMediaManager rtpMediaManager) {
        super(sessionManager, rtpMediaManager);
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
        this.messageFactory = messageFactory;
        this.sipProvider = sipProvider;
    }

    @Override
    public void process(ResponseEvent evt) throws Exception {
        Response response = evt.getResponse();
        String clientId = extractClientId(evt);
        int statusCode = response.getStatusCode();
        log.info("[{}] 处理REGISTER响应: {}", clientId, statusCode);
        if (statusCode == Response.UNAUTHORIZED) {
            handleAuthChallenge(evt);
        } else if (statusCode >= 200 && statusCode < 300) {
            handleRegistrationSuccess(evt);
        } else {
            handleRegistrationFailure(evt);
        }
    }

    @Override
    protected void updateStatus(SipSession session, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            session.updateStatus(SipSession.SessionStatus.REGISTERED);
        } else if (statusCode >= 400) {
            session.updateStatus(SipSession.SessionStatus.FAILED);
        }
    }

    private void handleAuthChallenge(ResponseEvent evt) throws Exception {
        Response response = evt.getResponse();
        String clientId = extractClientId(evt);
        ClientTransaction clientTransaction = evt.getClientTransaction();
        // 获取WWW-Authenticate或Proxy-Authenticate头
        WWWAuthenticateHeader authHeader = (WWWAuthenticateHeader) response.getHeader(WWWAuthenticateHeader.NAME);
        if (authHeader == null) {
            authHeader = (WWWAuthenticateHeader) response.getHeader(ProxyAuthenticateHeader.NAME);
        }
        if (authHeader != null) {
            // 获取认证信息
            String realm = authHeader.getRealm();
            String nonce = authHeader.getNonce();
            String scheme = authHeader.getScheme();
            // 获取原始请求
            Request originalRequest = clientTransaction.getRequest();
            FromHeader fromHeader = (FromHeader) originalRequest.getHeader(FromHeader.NAME);
            ToHeader toHeader = (ToHeader) originalRequest.getHeader(ToHeader.NAME);
            // 创建新的认证请求
            Request authRequest = createAuthRequest(originalRequest, realm, nonce, scheme, fromHeader, toHeader, "1001", "1234"); // 用户名和密码应该从配置中获取
            // 发送认证请求
            ClientTransaction newClientTransaction = sipProvider.getNewClientTransaction(authRequest);
            newClientTransaction.sendRequest();
            log.info("[{}] 发送认证请求: {}", clientId, authRequest);
        }
    }

    private Request createAuthRequest(Request originalRequest, String realm, String nonce, String scheme,
                                      FromHeader fromHeader, ToHeader toHeader, String username, String password) throws Exception {
        // 创建新的请求URI
        SipURI requestURI = (SipURI) originalRequest.getRequestURI();
        // 创建新的Via头
        String branchId = "z9hG4bK" + System.currentTimeMillis();
        ViaHeader viaHeader = headerFactory.createViaHeader(requestURI.getHost(), requestURI.getPort(), "udp", branchId);
        ArrayList<ViaHeader> viaHeaders = new ArrayList<>();
        viaHeaders.add(viaHeader);
        // 创建新的Call-ID
        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        // 创建新的CSeq头
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L, Request.REGISTER);
        // 创建新的MaxForwards头
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
        // 创建新的请求
        Request request = messageFactory.createRequest(requestURI, Request.REGISTER, callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards);
        // 添加Contact头
        SipURI contactURI = addressFactory.createSipURI(fromHeader.getAddress().getDisplayName(), requestURI.getHost() + ":" + requestURI.getPort());
        Address contactAddress = addressFactory.createAddress(contactURI);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);
        // 添加Expires头
        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(3600);
        request.addHeader(expiresHeader);
        // 计算认证响应
        String response = calculateAuthResponse(username, realm, password, nonce, Request.REGISTER, requestURI.toString());
        // 创建Authorization头
        AuthorizationHeader authHeader = headerFactory.createAuthorizationHeader(scheme);
        authHeader.setUsername(username);
        authHeader.setRealm(realm);
        authHeader.setNonce(nonce);
        authHeader.setURI(requestURI);
        authHeader.setResponse(response);
        request.addHeader(authHeader);
        return request;
    }

    private String calculateAuthResponse(String username, String realm, String password, String nonce, String method, String uri) {
        // MD5(username:realm:password)
        String a1 = md5(username + ":" + realm + ":" + password);
        // MD5(method:uri)
        String a2 = md5(method + ":" + uri);
        // MD5(A1:nonce:A2)
        return md5(a1 + ":" + nonce + ":" + a2);
    }

    private String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xFF & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handleRegistrationSuccess(ResponseEvent evt) {
        String clientId = extractClientId(evt);
        Dialog dialog = evt.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.info("[{}] 注册成功 callId: {}", clientId, callId);
            // 更新会话状态
            sessionManager.getClientSessions(clientId).values().stream()
                    .filter(session -> callId.equals(session.getCallId()))
                    .findFirst()
                    .ifPresent(session -> {
                        session.updateStatus(SipSession.SessionStatus.REGISTERED);
                        session.setDialog(dialog);
                    });
        }
    }

    private void handleRegistrationFailure(ResponseEvent evt) {
        Response response = evt.getResponse();
        String clientId = extractClientId(evt);
        Dialog dialog = evt.getDialog();
        if (dialog != null) {
            String callId = dialog.getCallId().getCallId();
            log.error("[{}] 注册失败 callId: {} 状态码: {}", clientId, callId, response.getStatusCode());
            // 更新会话状态
            sessionManager.getClientSessions(clientId).values().stream()
                    .filter(session -> callId.equals(session.getCallId()))
                    .findFirst()
                    .ifPresent(session -> {
                        session.updateStatus(SipSession.SessionStatus.FAILED);
                        session.setDialog(null);
                    });
        }
    }
}
