package com.relyon.economizai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EconomizaiApplication {

	public static void main(String[] args) {
		SpringApplication.run(EconomizaiApplication.class, args);
	}

}
