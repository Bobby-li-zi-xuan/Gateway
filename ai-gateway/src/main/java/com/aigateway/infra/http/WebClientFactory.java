package com.aigateway.infra.http;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientFactory {
    
    @Bean
    public WebClient webClient(){
        // 所有上游模型的 HTTP 请求都通过此客户端发送，集中管理超时和连接池
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> 
                        conn.addHandlerLast(
                            new ReadTimeoutHandler(30, TimeUnit.SECONDS)
                        )
                );

        return WebClient.builder()
                .clientConnector(
                    new ReactorClientHttpConnector(httpClient)
                ).build();
    }
}
