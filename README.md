# Voice AI SIP 服务

## 项目简介
这是一个基于 Java 实现的 SIP 通信服务，主要用于处理语音通话相关的 SIP 信令。项目使用 Spring Boot 框架开发，实现了完整的 SIP 客户端功能。

## 主要功能
- SIP 信令处理
  - 支持 INVITE、BYE、CANCEL、ACK 等基本 SIP 方法
  - 实现了完整的会话管理
  - 支持 SDP 协商
- RTP 媒体流处理
- 会话管理
  - 支持多客户端并发
  - 会话状态维护
  - 会话保活机制

## 技术栈
- Spring Boot
- JAIN-SIP
- Java SDP
- Lombok
- Hutool 工具集

## 项目结构 