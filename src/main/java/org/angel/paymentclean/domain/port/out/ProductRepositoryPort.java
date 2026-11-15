package org.angel.paymentclean.domain.port.out;

import org.angel.paymentclean.domain.model.Product;

import java.util.List;
import java.util.Optional;

public interface ProductRepositoryPort {
    Product save(Product product);
    Optional<Product> findById(String id);
    List<Product> findAll();
}
