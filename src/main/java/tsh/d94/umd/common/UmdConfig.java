package tsh.d94.umd.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling // server: 응답 Stream TTL 갱신 / local: heartbeat, Pending 회수
@EnableConfigurationProperties(UmdProperties.class)
public class UmdConfig {
}
