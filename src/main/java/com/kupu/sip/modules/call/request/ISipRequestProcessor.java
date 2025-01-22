package com.kupu.sip.modules.call.request;

import javax.sip.RequestEvent;


public interface ISipRequestProcessor {

	void process(RequestEvent evt) throws Exception;

}
