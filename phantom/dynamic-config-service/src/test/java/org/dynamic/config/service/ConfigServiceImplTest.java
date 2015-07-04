package org.dynamic.config.service;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConfigServiceImplTest {

	ConfigServiceImpl service = new ConfigServiceImpl();

	@Test
	public void testGetConfigVersion() {
		try {
			System.out.println("================================");
			service.setHost("http://remoteconfigserv7153.qa.paypal.com");
			service.setPort("8080");
			System.out.println(service
					.getConfigValue("FaultCanister",
							ConfigService.ACTIVE_CHANGELIST_ID,
							ConfigService.TYPE_META_DATA));
			System.out.println("================================");
		} catch (Exception e) {
			fail("Not yet implemented");
		}

	}

}
