package com.mapploy.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MapployBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MapployBackendApplication.class, args);
	}

}
