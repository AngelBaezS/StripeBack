package org.angel.paymentclean.infrastructure.adapters.out.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import org.angel.paymentclean.domain.port.out.StripeCustomerPort;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StripeCustomerService implements StripeCustomerPort {

    private final StripeClient client;

    public StripeCustomerService(StripeClient client) {
        this.client = client;
    }

    @Override
    public String crearClienteEnStripe(String nombre, String email, String telefono) {
        try{
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setName(nombre)
                    .setEmail(email)
                    .setPhone(telefono)
                    .setDescription("Cliente de Tienda de Tecnologia")
                    .setCashBalance(
                        CustomerCreateParams.CashBalance.builder()
                            .setSettings(
                                CustomerCreateParams.CashBalance.Settings.builder()
                                    .setReconciliationMode(
                                            CustomerCreateParams.CashBalance.Settings.ReconciliationMode.AUTOMATIC
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build();
            Customer customer = client.v1().customers().create(params);
            return customer.getId();
        } catch (StripeException e){
            throw new RuntimeException("Error al crear Customer en Stripe: " + e.getMessage(), e);
        }
    }
}
