package org.angel.paymentclean.domain.port.in;

import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.ProductForUser;

import java.util.List;

public interface ManageCatalogUseCase {
    Product createProduct(String name, String description, Integer stock, Long unitAmountCents, String imageUrl);
    default Product createProduct(String name, String description, Integer stock, Long unitAmountCents) {
        return createProduct(name, description, stock, unitAmountCents, null);
    }
    Product updateProductPrice(String productId, Long newUnitAmountCents);
    List<Product> listAll();
    List<ProductForUser> listAllForUsers();
}
