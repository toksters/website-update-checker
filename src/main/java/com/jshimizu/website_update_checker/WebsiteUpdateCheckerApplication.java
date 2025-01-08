package com.jshimizu.website_update_checker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class WebsiteUpdateCheckerApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebsiteUpdateCheckerApplication.class, args);
	}

}
