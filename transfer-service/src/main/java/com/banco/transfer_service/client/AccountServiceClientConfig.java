package com.banco.transfer_service.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AccountServiceClientConfig {

    private static final String REGISTRATION_ID = "account-service";
    private static final String PRINCIPAL = "transfer-service";

    @Bean
    OAuth2AuthorizedClientManager accountAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService
    ) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService
                );
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build()
        );
        return manager;
    }

    @Bean
    RestClient accountServiceRestClient(
            OAuth2AuthorizedClientManager accountAuthorizedClientManager,
            @Value("${account-service.base-url}") String baseUrl,
            @Value("${account-service.connect-timeout}") Duration connectTimeout,
            @Value("${account-service.read-timeout}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    OAuth2AuthorizedClient authorized = accountAuthorizedClientManager.authorize(
                            OAuth2AuthorizeRequest
                                    .withClientRegistrationId(REGISTRATION_ID)
                                    .principal(PRINCIPAL)
                                    .build()
                    );
                    if (authorized == null) {
                        throw new IllegalStateException(
                                "Could not obtain a token for account-service"
                        );
                    }
                    request.getHeaders().setBearerAuth(
                            authorized.getAccessToken().getTokenValue()
                    );
                    return execution.execute(request, body);
                })
                .build();
    }
}
