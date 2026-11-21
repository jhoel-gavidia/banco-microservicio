package com.banco.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApiGatewayApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("api-gateway");
        assertThat(environment.getProperty("server.port")).isEqualTo("8084");
    }

    @Test
    void staticRoutesAreConfigured() {
        assertThat(environment.getProperty("spring.cloud.gateway.server.webmvc.routes[0].id"))
                .isEqualTo("account-service");
        assertThat(environment.getProperty("spring.cloud.gateway.server.webmvc.routes[0].uri"))
                .isEqualTo("http://localhost:8081");
        assertThat(environment.getProperty("spring.cloud.gateway.server.webmvc.routes[1].id"))
                .isEqualTo("transfer-service");
        assertThat(environment.getProperty("spring.cloud.gateway.server.webmvc.routes[1].uri"))
                .isEqualTo("http://localhost:8083");
    }

    @Test
    void healthProbesAreConfigured() {
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("management.health.livenessstate.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("management.health.readinessstate.enabled", Boolean.class))
                .isTrue();
    }
}