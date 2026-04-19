package com.chatbot;

import com.chatbot.service.OllamaHealthService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class ChatbotApplication {

    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(ChatbotApplication.class, args);
        ctx.getBean(OllamaHealthService.class).validateOnStartup();
    }
}
