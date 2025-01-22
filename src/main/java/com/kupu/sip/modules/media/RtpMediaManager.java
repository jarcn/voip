package com.kupu.sip.modules.media;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RtpMediaManager {
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private DatagramSocket rtpSocket;
    private DatagramSocket rtcpSocket;
    private InetAddress remoteAddress;
    private int remoteRtpPort;
    private Thread receiveThread;

    private static final int RTP_HEADER_SIZE = 12;
    private static final int PAYLOAD_TYPE_PCMA = 8; // G.711 A-law
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private final long ssrc = (long) (Math.random() * Long.MAX_VALUE); // 随机SSRC

    private static final int SILENCE_THRESHOLD = 150; // 静音阈值
    private static final int SILENCE_DURATION_MS = 1000; // 静音持续时间阈值（毫秒）
    private static final int BUFFER_SIZE = 160; // 每个RTP包的音频数据大小

    private ByteArrayOutputStream audioBuffer;
    private long lastActiveTime;
    private boolean isReceiving;
    private Consumer<byte[]> audioDataCallback;
    private final Object receiveLock = new Object();

    public void initializeRtpSession(String localAddress, int localPort, String remoteAddress, int remotePort) {
        try {
            log.info("RTP会话初始化开始 - 本地: {}:{}, 远程: {}:{}", localAddress, localPort, remoteAddress, remotePort);
            String bindAddress = getLocalBindAddress(localAddress); // 获取本地可用IP地址
            log.info("使用本地绑定地址: {}", bindAddress);
            this.rtpSocket = new DatagramSocket(localPort, InetAddress.getByName(bindAddress)); // 创建RTP
            this.rtcpSocket = new DatagramSocket(localPort + 1, InetAddress.getByName(bindAddress)); // 创建RTCP
            this.remoteAddress = InetAddress.getByName(remoteAddress);
            this.remoteRtpPort = remotePort;
            // 设置socket选项
            rtpSocket.setReuseAddress(true);
            rtcpSocket.setReuseAddress(true);
            log.info("RTP会话初始化成功 - 本地绑定: {}:{}, 远程: {}:{}", bindAddress, localPort, remoteAddress, remotePort);
        } catch (Exception e) {
            log.error("RTP会话初始化失败", e);
            throw new RuntimeException("RTP会话初始化失败", e);
        }
    }

    /**
     * 获取合适的本地绑定地址
     * 如果提供的地址可用，则使用提供的地址
     * 否则使用本地回环地址或者第一个可用的非回环地址
     */
    private String getLocalBindAddress(String preferredAddress) {
        try {
            // 1. 获取本机所有网络接口
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface
                    .getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                // 跳过禁用的接口和回环接口
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                // 获取接口的IP地址
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    // 只使用IPv4地址
                    if (addr instanceof java.net.Inet4Address) {
                        String ipAddress = addr.getHostAddress();
                        // 优先选择内网地址
                        if (isPrivateAddress(ipAddress)) {
                            log.info("找到可用的内网地址: {}", ipAddress);
                            return ipAddress;
                        }
                    }
                }
            }
            // 2. 如果没有找到内网地址，尝试使用0.0.0.0
            log.info("未找到内网地址,使用0.0.0.0");
            return "0.0.0.0";

        } catch (Exception e) {
            log.warn("获取本地绑定地址失败,使用0.0.0.0", e);
            return "0.0.0.0";
        }
    }

    /**
     * 判断是否是内网地址
     */
    private boolean isPrivateAddress(String ipAddress) {
        try {
            String[] parts = ipAddress.split("\\.");
            int firstOctet = Integer.parseInt(parts[0]);
            int secondOctet = Integer.parseInt(parts[1]);
            // 10.0.0.0/8
            if (firstOctet == 10) {
                return true;
            }
            // 172.16.0.0/12
            if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) {
                return true;
            }
            // 192.168.0.0/16
            if (firstOctet == 192 && secondOctet == 168) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void startMediaSession() {
        if (isActive.compareAndSet(false, true)) {
            try {
                startReceiveThread();
                log.info("媒体会话已启动");
            } catch (Exception e) {
                log.error("启动媒体会话失败", e);
                stopMediaSession();
            }
        }
    }

    private void startReceiveThread() {
        Thread receiveThread = new Thread(() -> {
            byte[] buffer = new byte[1500]; // MTU大小，确保能接收完整的RTP包
            audioBuffer = new ByteArrayOutputStream();
            isReceiving = true;
            lastActiveTime = System.currentTimeMillis();
            while (isReceiving) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    rtpSocket.receive(packet);
                    // 1. 提取RTP头部信息
                    byte[] rtpData = packet.getData();
                    int rtpVersion = (rtpData[0] >> 6) & 0x03;
                    int payloadType = rtpData[1] & 0xFF;
                    int seqNumber = ((rtpData[2] & 0xFF) << 8) | (rtpData[3] & 0xFF);

                    // 2. 检查是否是PCMA(G.711 A-law)包
                    if (payloadType != PAYLOAD_TYPE_PCMA) {
                        log.warn("收到非PCMA格式的RTP包，PayloadType: {}", payloadType);
                        continue;
                    }

                    // 3. 提取音频数据（跳过12字节RTP头）
                    byte[] alawData = new byte[packet.getLength() - RTP_HEADER_SIZE];
                    System.arraycopy(packet.getData(), RTP_HEADER_SIZE, alawData, 0, alawData.length);

                    // 4. 将G.711 A-law解码为PCM
                    byte[] pcmData = ALawToLinearDecoder.decode(alawData);

                    // 5. 检查是否是静音
                    if (!isSilence(pcmData)) {
                        lastActiveTime = System.currentTimeMillis();
                        synchronized (receiveLock) {
                            audioBuffer.write(pcmData);
                        }
                    } else if (System.currentTimeMillis() - lastActiveTime > SILENCE_DURATION_MS) {
                        // 如果静音持续超过阈值，认为说话结束
                        if (audioBuffer.size() > 0) {
                            byte[] completeAudio;
                            synchronized (receiveLock) {
                                completeAudio = audioBuffer.toByteArray();
                                audioBuffer.reset();
                            }
                            if (audioDataCallback != null) {
                                audioDataCallback.accept(completeAudio);
                                log.info("语音片段接收完成，PCM数据大小: {} 字节", completeAudio.length);
                            }
                        }
                    }

                } catch (IOException e) {
                    if (isReceiving) {
                        log.error("接收RTP包失败", e);
                    }
                }
            }
        });
        receiveThread.setName("RTP-Receive-Thread");
        receiveThread.start();
    }

    private boolean isSilence(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            return true;
        }
        // 对于16位PCM数据，每两个字节组成一个采样点
        double sum = 0;
        int sampleCount = pcmData.length / 2;
        for (int i = 0; i < pcmData.length; i += 2) {
            // 将两个字节组合成一个16位整数
            short sample = (short) ((pcmData[i + 1] << 8) | (pcmData[i] & 0xFF));
            sum += sample * sample;
        }
        // 计算平均能量
        double rms = Math.sqrt(sum / sampleCount);
        return rms < SILENCE_THRESHOLD; // 使用RMS能量作为静音阈值
    }

    public void stopMediaSession() {
        if (isActive.compareAndSet(true, false)) {
            try {
                if (receiveThread != null) {
                    receiveThread.interrupt();
                    receiveThread.join(1000);
                }
                // 关闭socket
                if (rtpSocket != null && !rtpSocket.isClosed()) {
                    rtpSocket.close();
                }
                if (rtcpSocket != null && !rtcpSocket.isClosed()) {
                    rtcpSocket.close();
                }
                log.info("媒体会话已停止");
            } catch (Exception e) {
                log.error("停止媒体会话失败", e);
            }
        }
    }

    // 修改：播放音频文件方法
    public void playAudioFile(String audioFilePath) {
        try {
            log.info("开始播放音频文件: {}", audioFilePath);
            // 1. 按照协商格式配置音频参数
            AudioFormat targetFormat = new AudioFormat(8000.0f, 16, 1, true, false);
            // 2. 加载并转换音频文件
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File(audioFilePath));
            AudioFormat sourceFormat = audioInputStream.getFormat();
            if (!sourceFormat.matches(targetFormat)) {
                audioInputStream = AudioSystem.getAudioInputStream(targetFormat, audioInputStream);
            }
            // 3. 配置RTP参数
            byte[] buffer = new byte[160 * 2]; // 20ms @ 8kHz * 16bit = 320 bytes
            int bytesRead;
            long startTime = System.nanoTime();
            long packetCount = 0;
            long packetInterval = 20_000_000L; // 20ms in nanoseconds
            // 4. 发送循环
            while ((bytesRead = audioInputStream.read(buffer)) != -1 && isActive.get()) {
                if (bytesRead == 320) { // 确保读取了完整的一帧
                    // 转换为G.711 A-law
                    byte[] alawData = LinearToALawEncoder.encode(buffer);
                    // 构建RTP包
                    byte[] rtpPacket = new byte[alawData.length + RTP_HEADER_SIZE];
                    // 设置RTP头
                    rtpPacket[0] = (byte) 0x80; // RTP版本 2
                    rtpPacket[1] = (byte) PAYLOAD_TYPE_PCMA; // PT=8 for PCMA
                    // 序列号
                    rtpPacket[2] = (byte) (sequenceNumber >> 8);
                    rtpPacket[3] = (byte) sequenceNumber;
                    // 时间戳 (每包增加160个采样点)
                    long currentTimestamp = timestamp + (packetCount * 160);
                    rtpPacket[4] = (byte) (currentTimestamp >> 24);
                    rtpPacket[5] = (byte) (currentTimestamp >> 16);
                    rtpPacket[6] = (byte) (currentTimestamp >> 8);
                    rtpPacket[7] = (byte) currentTimestamp;
                    // SSRC
                    rtpPacket[8] = (byte) (ssrc >> 24);
                    rtpPacket[9] = (byte) (ssrc >> 16);
                    rtpPacket[10] = (byte) (ssrc >> 8);
                    rtpPacket[11] = (byte) ssrc;
                    // 复制音频数据
                    System.arraycopy(alawData, 0, rtpPacket, RTP_HEADER_SIZE, alawData.length);
                    // 发送RTP包
                    DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length, remoteAddress,
                            remoteRtpPort);
                    rtpSocket.send(packet);
                    // 更新计数器
                    sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
                    packetCount++;
                    // 精确控制发送时间
                    long expectedTime = startTime + (packetCount * packetInterval);
                    long currentTime = System.nanoTime();
                    long sleepTime = (expectedTime - currentTime) / 1_000_000L;
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                }
            }
            log.info("音频文件播放完成: {}", audioFilePath);
        } catch (Exception e) {
            log.error("播放音频文件失败: {}", audioFilePath, e);
        }
    }

    // 新增：PCM线性音频转G.711 A-law编码器
    private static class LinearToALawEncoder {
        private static final int[] LINEAR_TO_ALAW_TABLE = new int[65536];
        private static final int QUANT_MASK = 0xF;
        private static final int SEG_SHIFT = 4;
        private static final int SEG_MASK = 0x70;
        private static final int SIGN_BIT = 0x80;

        static {
            for (int i = -32768; i <= 32767; i++) {
                LINEAR_TO_ALAW_TABLE[i & 0xFFFF] = linearToALaw(i);
            }
        }

        private static byte linearToALaw(int pcm) {
            int sign = (pcm & 0x8000) >> 8;
            if (sign != 0) {
                pcm = -pcm;
            }
            if (pcm > 32635) {
                pcm = 32635;
            }

            int exp = 7;
            for (int expMask = 0x4000; (pcm & expMask) == 0 && exp > 0; exp--, expMask >>= 1) {
            }

            int mantis = (pcm >> (exp + 3)) & QUANT_MASK;
            int alaw = (exp << SEG_SHIFT) | mantis;
            if (sign != 0) {
                alaw |= SIGN_BIT;
            }

            return (byte) (alaw ^ 0x55);
        }

        public static byte[] encode(byte[] pcmData) {
            byte[] alawData = new byte[pcmData.length / 2];
            for (int i = 0; i < alawData.length; i++) {
                short sample = (short) ((pcmData[2 * i + 1] & 0xFF) << 8 | (pcmData[2 * i] & 0xFF));
                alawData[i] = (byte) LINEAR_TO_ALAW_TABLE[sample & 0xFFFF];
            }
            return alawData;
        }
    }

    // 新增：G.711 A-law解码器
    private static class ALawToLinearDecoder {
        private static final int[] ALAW_TO_LINEAR_TABLE = new int[256];

        static {
            for (int i = 0; i < 256; i++) {
                ALAW_TO_LINEAR_TABLE[i] = aLawToLinear((byte) i);
            }
        }

        public static byte[] decode(byte[] alawData) {
            byte[] pcmData = new byte[alawData.length * 2];
            for (int i = 0; i < alawData.length; i++) {
                int sample = ALAW_TO_LINEAR_TABLE[alawData[i] & 0xFF];
                pcmData[i * 2] = (byte) sample;
                pcmData[i * 2 + 1] = (byte) (sample >> 8);
            }
            return pcmData;
        }

        private static int aLawToLinear(byte alaw) {
            alaw ^= 0x55;
            int sign = alaw & 0x80;
            int exponent = (alaw & 0x70) >> 4;
            int mantissa = alaw & 0x0f;
            int sample = mantissa << (exponent + 3);
            if (exponent > 0) {
                sample += (1 << (exponent + 3 - 1));
            }
            if (sign == 0) {
                sample = -sample;
            }
            return sample;
        }
    }

    /**
     * 播放PCM音频数据
     * 
     * @param pcmData PCM格式的音频数据 (16bit, 8kHz, 单声道)
     */
    public void playAudioData(byte[] pcmData) {
        try {
            if (!isActive.get() || rtpSocket == null) {
                log.error("RTP会话未初始化或已停止");
                return;
            }

            log.info("开始发送音频数据, 数据长度: {} bytes", pcmData.length);

            // 1. 将PCM数据分割成20ms的帧
            int frameSize = 320; // 20ms @ 8kHz * 16bit = 320 bytes
            int frameCount = pcmData.length / frameSize;

            // 2. 发送参数初始化
            long startTime = System.nanoTime();
            long packetInterval = 20_000_000L; // 20ms in nanoseconds

            // 3. 逐帧发送
            for (int i = 0; i < frameCount && isActive.get(); i++) {
                // 提取一帧PCM数据
                byte[] framePcm = new byte[frameSize];
                System.arraycopy(pcmData, i * frameSize, framePcm, 0, frameSize);

                // 转换为G.711 A-law
                byte[] alawData = LinearToALawEncoder.encode(framePcm);

                // 构建RTP包
                byte[] rtpPacket = new byte[alawData.length + RTP_HEADER_SIZE];

                // 设置RTP头
                rtpPacket[0] = (byte) 0x80; // RTP版本 2
                rtpPacket[1] = (byte) PAYLOAD_TYPE_PCMA; // PT=8 for PCMA

                // 序列号
                rtpPacket[2] = (byte) (sequenceNumber >> 8);
                rtpPacket[3] = (byte) sequenceNumber;

                // 时间戳 (每包增加160个采样点)
                long currentTimestamp = timestamp + (i * 160);
                rtpPacket[4] = (byte) (currentTimestamp >> 24);
                rtpPacket[5] = (byte) (currentTimestamp >> 16);
                rtpPacket[6] = (byte) (currentTimestamp >> 8);
                rtpPacket[7] = (byte) currentTimestamp;

                // SSRC
                rtpPacket[8] = (byte) (ssrc >> 24);
                rtpPacket[9] = (byte) (ssrc >> 16);
                rtpPacket[10] = (byte) (ssrc >> 8);
                rtpPacket[11] = (byte) ssrc;

                // 复制音频数据
                System.arraycopy(alawData, 0, rtpPacket, RTP_HEADER_SIZE, alawData.length);

                // 发送RTP包
                DatagramPacket packet = new DatagramPacket(
                        rtpPacket,
                        rtpPacket.length,
                        remoteAddress,
                        remoteRtpPort);
                rtpSocket.send(packet);

                // 更新计数器
                sequenceNumber = (sequenceNumber + 1) & 0xFFFF;

                // 精确控制发送时间
                long expectedTime = startTime + ((i + 1) * packetInterval);
                long currentTime = System.nanoTime();
                long sleepTime = (expectedTime - currentTime) / 1_000_000L;

                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            }

            // 更新时间戳，为下一次发送准备
            timestamp += (frameCount * 160);

            log.info("音频数据发送完成, 发送 {} 帧", frameCount);

        } catch (Exception e) {
            log.error("发送音频数据失败", e);
        }
    }
}