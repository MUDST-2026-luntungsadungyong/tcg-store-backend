package com.mythix.tcg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TcgStoreBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TcgStoreBackendApplication.class, args);
	}

}
