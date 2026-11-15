package org.angel.paymentclean.infrastructure.config;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StripeConfig {

    @Value("${stripe.key.secret}")
    private String secretKey;

    @Bean
    public StripeClient stripeClient(){
        return new StripeClient(this.secretKey);
    }
}
