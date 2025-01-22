package com.kupu.sip.modules.call.service;

import com.kupu.sip.modules.call.client.JainSipClient;
import com.kupu.sip.modules.media.RtpMediaManager;
import com.kupu.sip.modules.session.SessionManager;
import com.kupu.sip.modules.session.SipSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SipService {

    private final SessionManager sessionManager;
    private final RtpMediaManager rtpMediaManager;

    public SipService(SessionManager sessionManager, RtpMediaManager rtpMediaManager) {
        this.sessionManager = sessionManager;
        this.rtpMediaManager = rtpMediaManager;
    }

    public JainSipClient createSipClient(String clientId, String ip, int port, String uasHost) throws Exception {
        JainSipClient client = new JainSipClient(clientId, ip, port, uasHost, sessionManager, rtpMediaManager);
        try {
            client.init(clientId);
            sessionManager.registerSipClient(clientId, client);
            log.info("Created SIP client: {}", clientId);
        } catch (Exception e) {
            log.error("Failed to create SIP client: {}", clientId, e);
            throw e;
        }
        return client;
    }

    public SipSession getSession(String clientId, String sessionId) {
        return sessionManager.getSession(clientId, sessionId);
    }

    /**
     * 销毁指定的SIP客户端
     * 
     * @param clientId 客户端ID
     */
    public void destroyClient(String clientId) {
        sessionManager.destroySipClient(clientId);
    }

    /**
     * 销毁所有SIP客户端
     */
    public void destroyAllClients() {
        log.info("开始销毁所有SIP客户端");
        sessionManager.destroyAllSipClients();
        log.info("所有SIP客户端销毁完成");
    }
}