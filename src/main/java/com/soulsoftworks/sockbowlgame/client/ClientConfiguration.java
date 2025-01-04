package com.soulsoftworks.sockbowlgame.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.okhttp.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class ClientConfiguration {

    @Value("${sockbowl.questions.url}")
    private String baseUrl;

    @Bean
    public OkHttpClient client() {
        return new OkHttpClient();
    }

    @Bean
    public RequestInterceptor urlInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                template.target(baseUrl);
            }
        };
    }
}
