/*
 * Copyright 2012-2015, the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.phantom.task.spi;

import com.github.kristofa.brave.ServerSpan;

/**
 * <code>RequestContext</code> holds information around a task request. This class holds a reference to {@link ServerSpan} implementation of the Zipkin
 * Java instrumentation library.
 * 
 * @author Regunath B
 * @date : 13 Nov 2014
 */

public class RequestContext {

	/** Instance variables used in request tracing */
	private ServerSpan currentServerSpan;
	private ServiceEndpoint currentClientEndpoint;

	/** Setter/Getter methods*/
	public ServerSpan getCurrentServerSpan() {
		return currentServerSpan;
	}
	public void setCurrentServerSpan(ServerSpan currentServerSpan) {
		this.currentServerSpan = currentServerSpan;
	}
	public ServiceEndpoint getCurrentClientEndpoint() {
		return currentClientEndpoint;
	}
	public void setCurrentClientEndpoint(ServiceEndpoint currentClientEndpoint) {
		this.currentClientEndpoint = currentClientEndpoint;
	}

	/** Service endpoint details. We dont use the Zipkin Endpoint class as it requires resolved host name which is expensive due to DNS lookups*/
	public static final class ServiceEndpoint {
		private String host;
		private int port;
		private String serviceName;
		public ServiceEndpoint(String host, int port, String serviceName) {
			this.host = host;
			this.port = port;
			this.serviceName = serviceName;
		}
		public String getHost() {
			return host;
		}
		public int getPort() {
			return port;
		}
		public String getServiceName() {
			return serviceName;
		}		
	}
	
}
