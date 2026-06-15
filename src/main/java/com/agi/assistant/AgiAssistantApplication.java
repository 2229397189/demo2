package com.agi.assistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {KafkaAutoConfiguration.class})
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.agi.assistant.mapper")
public class AgiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgiAssistantApplication.class, args);
    }
}
