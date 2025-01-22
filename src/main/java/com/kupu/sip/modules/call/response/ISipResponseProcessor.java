package com.kupu.sip.modules.call.response;

import javax.sip.ResponseEvent;

/**
 * 统一处理SIP响应
 */
public interface ISipResponseProcessor {

    void process(ResponseEvent responseEvent) throws Exception;

}
