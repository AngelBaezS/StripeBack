package org.angel.paymentclean;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentCleanApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentCleanApplication.class, args);
    }

}
