/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.test.client_1.x.integration.call;

import org.junit.Assert;
import org.junit.Test;

import com.dianping.pigeon.test.client_1.x.BaseInvokerTest;
import com.dianping.pigeon.test.client_1.x.PigeonAutoTest;
import com.dianping.pigeon.test.service.EchoService;

public class OnewayEchoServiceTest extends BaseInvokerTest {
	
	@PigeonAutoTest(serviceName = "http://service.dianping.com/testService/echoService_1.0.0", serialize = "java", callMethod = "oneway")
	public EchoService echoService;

	@Test
	public void testOneway() {
		String msg = System.currentTimeMillis() + "";
		System.out.println(msg);
		String echo = echoService.echo(msg);
		System.out.println(echo);
		Assert.assertEquals(null, echo);
	}

}
