package com.minimes.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IPv6 루프백(::1)에서도 요청을 받는다 (트러블 슈팅 4).
 * <p>
 * 레거시(ASP.NET)는 "localhost"로 설정하면 127.0.0.1과 ::1 양쪽에서 받는다. Spring Boot의 server.address는 주소를 하나만
 * 받아서 127.0.0.1에서만 받았고, localhost를 ::1로 먼저 시도하는 클라이언트(Python urllib, Java HttpClient 등)는
 * Windows에서 연결 거부를 확인하는 데 요청마다 약 2초를 썼다. 같은 주소로 부르면 레거시와 똑같이 응답하도록 ::1 커넥터를 더한다.
 * <p>
 * server.address가 127.0.0.1(루프백만 연다)일 때만 적용한다. 컨테이너처럼 모든 주소를 여는 환경에는 필요 없다.
 */
@Configuration
@ConditionalOnProperty(name = "server.address", havingValue = "127.0.0.1")
public class LoopbackConfig {

	private static final String IPV6_LOOPBACK = "::1";

	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> ipv6LoopbackConnector(
			@Value("${server.port}") int port) {
		return factory -> {
			if (!hasIpv6Loopback()) {
				return; // IPv6를 끈 PC에서는 ::1에 바인딩할 수 없어서 서버가 뜨지 않으므로 건너뛴다
			}
			Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
			connector.setPort(port);
			connector.setProperty("address", IPV6_LOOPBACK);
			factory.addAdditionalConnectors(connector);
		};
	}

	private static boolean hasIpv6Loopback() {
		try {
			return NetworkInterface.getByInetAddress(InetAddress.getByName(IPV6_LOOPBACK)) != null;
		}
		catch (IOException e) {
			return false;
		}
	}

}
