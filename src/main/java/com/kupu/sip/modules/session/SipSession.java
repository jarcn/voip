package com.kupu.sip.modules.session;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.sip.Dialog;

import com.kupu.sip.modules.media.RtpMediaManager;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SipSession {

    private RtpMediaManager mediaManager;
    private String localAddress;
    private int localPort;
    private String remoteAddress;
    private int remotePort;
    private int sdpPort;
    private String sessionId;
    private String fromUser;
    private String fromDomain;
    private String toUser;
    private String toDomain;
    private Dialog dialog;
    private String callId;
    private SessionStatus status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String clientId;
    // 会话超时时间(秒)
    private int sessionExpires;
    // 会话刷新方角色(uac/uas)
    private String refresher;

    public enum SessionStatus {
        INIT,
        INVITING,
        RINGING,
        CONNECTED,
        DISCONNECTED,
        FAILED,
        CANCELLED,
        REGISTERED,
        REFRESHING,     // 会话刷新中
        REFRESH_FAILED  // 会话刷新失败
    }

    public SipSession(String sessionId, String clientId) {
        this.sessionId = sessionId;
        this.clientId = clientId;
        this.status = SessionStatus.INIT;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public void updateStatus(SessionStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
    }

    public void initializeMediaSession() {
        if (mediaManager == null) {
            mediaManager = new RtpMediaManager();
            mediaManager.initializeRtpSession(localAddress, localPort, remoteAddress, remotePort);
        }
    }

    public void startMediaSession() {
        if (mediaManager != null) {
            mediaManager.startMediaSession();
        }
    }

    public void stopMediaSession() {
        if (mediaManager != null) {
            mediaManager.stopMediaSession();
        }
    }

}