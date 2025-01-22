package com.kupu.sip.modules.media.audio;

import lombok.extern.slf4j.Slf4j;
import javax.sound.sampled.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class AudioDevice {
    private TargetDataLine microphone;
    private SourceDataLine speaker;
    private volatile boolean running = false;
    private final BlockingQueue<byte[]> recordQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> playQueue = new LinkedBlockingQueue<>();
    
    // 音频格式配置
    private final AudioFormat audioFormat = new AudioFormat(
            8000.0f,    // 采样率
            16,         // 采样位数
            1,          // 单声道
            true,       // 有符号
            true        // big-endian
    );

    public void startRecording() {
        try {
            // 初始化麦克风
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(audioFormat);
            microphone.start();
            running = true;
            // 开启录音线程
            Thread recordThread = new Thread(() -> {
                byte[] buffer = new byte[1024];
                while (running) {
                    int count = microphone.read(buffer, 0, buffer.length);
                    if (count > 0) {
                        byte[] audioData = new byte[count];
                        System.arraycopy(buffer, 0, audioData, 0, count);
                        try {
                            recordQueue.put(audioData);
                        } catch (InterruptedException e) {
                            log.error("录音队列写入失败", e);
                        }
                    }
                }
            });
            recordThread.start();
            log.info("开始录音");
        } catch (LineUnavailableException e) {
            log.error("初始化麦克风失败", e);
        }
    }

    public void startPlaying() {
        try {
            // 初始化扬声器
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            speaker = (SourceDataLine) AudioSystem.getLine(info);
            speaker.open(audioFormat);
            speaker.start();
            running = true;
            // 开启播放线程
            Thread playThread = new Thread(() -> {
                while (running) {
                    try {
                        byte[] audioData = playQueue.take();
                        speaker.write(audioData, 0, audioData.length);
                    } catch (InterruptedException e) {
                        log.error("播放队列读取失败", e);
                    }
                }
            });
            playThread.start();
            log.info("开始播放");
        } catch (LineUnavailableException e) {
            log.error("初始化扬声器失败", e);
        }
    }

    public void stop() {
        running = false;
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        if (speaker != null) {
            speaker.stop();
            speaker.close();
        }
        log.info("停止音频设备");
    }

    public BlockingQueue<byte[]> getRecordQueue() {
        return recordQueue;
    }

    public BlockingQueue<byte[]> getPlayQueue() {
        return playQueue;
    }
}