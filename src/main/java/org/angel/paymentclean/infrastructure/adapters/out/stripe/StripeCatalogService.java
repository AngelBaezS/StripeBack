package org.angel.paymentclean.infrastructure.adapters.out.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceUpdateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.ProductUpdateParams;
import org.angel.paymentclean.domain.model.StripeCatalogResult;
import org.angel.paymentclean.domain.port.out.StripeCatalogPort;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StripeCatalogService implements StripeCatalogPort {

    private final StripeClient client;

    public StripeCatalogService(StripeClient client) {
        this.client = client;
    }

    @Override
    public StripeCatalogResult crearProducto(String dbProductId, String nombre, String descripcion, Long precio) {

        try {
            ProductCreateParams productParams = ProductCreateParams.builder()
                    .setName(nombre)
                    .setDescription(descripcion)
                    .setDefaultPriceData(
                            ProductCreateParams.DefaultPriceData.builder()
                                    .setCurrency("mxn")
                                    .setUnitAmount(precio)
                                    .build())
                    .putAllMetadata(Map.of("db_product_id", dbProductId))
                    .build();

            Product product = client.v1().products().create(productParams);

            return new StripeCatalogResult(product.getId(), product.getDefaultPrice());
        } catch (StripeException e) {
            throw new RuntimeException("Error al crear Producto en Stripe: " + e.getMessage(), e);
        }
    }

    @Override
    public String actualizarPrecioDeProducto(String stripeProductId, String oldStripePriceId,
            Long nuevoPrecioEnCentavos) {

        try {
            PriceCreateParams priceParams = PriceCreateParams.builder()
                    .setProduct(stripeProductId)
                    .setCurrency("mxn")
                    .setUnitAmount(nuevoPrecioEnCentavos)
                    .build();
            Price nuevoPrice = client.v1().prices().create(priceParams);

            ProductUpdateParams productUpdateParams = ProductUpdateParams.builder()
                    .setDefaultPrice(nuevoPrice.getId())
                    .build();
            client.v1().products().update(stripeProductId, productUpdateParams);

            if (oldStripePriceId != null && !oldStripePriceId.isBlank()) {
                client.v1().prices().update(
                        oldStripePriceId,
                        PriceUpdateParams.builder().setActive(false).build());
            }

            return nuevoPrice.getId();
        } catch (StripeException e) {
            throw new RuntimeException("Error al actualizar Precio en Stripe: " + e.getMessage(), e);
        }
    }

}
