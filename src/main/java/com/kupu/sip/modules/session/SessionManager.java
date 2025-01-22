package com.kupu.sip.modules.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.kupu.sip.modules.call.client.JainSipClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SessionManager {
    // clientId -> (sessionId -> Session)
    private final Map<String, Map<String, SipSession>> clientSessions = new ConcurrentHashMap<>();
    // clientId -> JainSipClient
    private final Map<String, JainSipClient> sipClients = new ConcurrentHashMap<>();

    public void registerSipClient(String clientId, JainSipClient sipClient) {
        sipClients.put(clientId, sipClient);
    }

    public SipSession createSession(String clientId, String sessionId) {
        SipSession session = new SipSession(sessionId, clientId);
        clientSessions.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>()).put(sessionId, session);
        log.info("[{}] Created new session: {}", clientId, sessionId);
        return session;
    }

    public SipSession getSession(String clientId, String sessionId) {
        return clientSessions.getOrDefault(clientId, new ConcurrentHashMap<>()).get(sessionId);
    }

    public void removeSession(String clientId, String sessionId) {
        Map<String, SipSession> sessions = clientSessions.get(clientId);
        if (sessions != null) {
            SipSession removed = sessions.remove(sessionId);
            if (removed != null) {
                log.info("[{}] Removed session: {}", clientId, sessionId);
                // 如果该客户端没有更多会话，则销毁SIP客户端
                if (sessions.isEmpty()) {
                    destroySipClient(clientId);
                }
            }
        }
    }

    public Map<String, SipSession> getClientSessions(String clientId) {
        return clientSessions.getOrDefault(clientId, new ConcurrentHashMap<>());
    }

    /**
     * 移除指定客户端的所有会话
     * @param clientId 客户端ID
     */
    public void removeClientSessions(String clientId) {
        Map<String, SipSession> removed = clientSessions.remove(clientId);
        if (removed != null) {
            log.info("[{}] 已移除所有会话，共 {} 个", clientId, removed.size());
            // 销毁对应的SIP客户端
            destroySipClient(clientId);
        }
    }

    /**
     * 销毁指定客户端的SIP实例
     * @param clientId 客户端ID
     */
    public void destroySipClient(String clientId) {
        JainSipClient sipClient = sipClients.remove(clientId);
        if (sipClient != null) {
            try {
                sipClient.destroy();
                log.info("[{}] SIP客户端已销毁", clientId);
            } catch (Exception e) {
                log.error("[{}] 销毁SIP客户端失败", clientId, e);
                throw new RuntimeException("销毁SIP客户端失败", e);
            }
        }
    }

    /**
     * 销毁所有SIP客户端
     */
    public void destroyAllSipClients() {
        log.info("开始销毁所有SIP客户端，共 {} 个", sipClients.size());
        for (String clientId : sipClients.keySet()) {
            try {
                destroySipClient(clientId);
            } catch (Exception e) {
                log.error("[{}] 销毁SIP客户端失败", clientId, e);
            }
        }
        sipClients.clear();
    }
} 