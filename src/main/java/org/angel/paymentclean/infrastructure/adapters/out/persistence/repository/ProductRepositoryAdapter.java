package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.ProductEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ProductRepositoryAdapter implements ProductRepositoryPort {

    private final DataProductRepository dataProductRepository;

    public ProductRepositoryAdapter(DataProductRepository dataProductRepository) {
        this.dataProductRepository = dataProductRepository;
    }

    @Override
    public Product save(Product product) {
        ProductEntity entity = toEntity(product);
        ProductEntity saved = dataProductRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<Product> findById(String id) {
        return dataProductRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Product> findAll() {
        return dataProductRepository.findAll().stream()
                .map(this::toDomain)
                .toList();
    }

    private ProductEntity toEntity(Product domain) {
        return new ProductEntity(
                domain.id(),
                domain.name(),
                domain.description(),
                domain.stock(),
                domain.active(),
                domain.stripeProductId(),
                domain.stripePriceId(),
                domain.unitAmountCents(),
                domain.currency(),
                domain.imageUrl()
        );
    }

    private Product toDomain(ProductEntity entity) {
        return new Product(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStock(),
                entity.getActive(),
                entity.getStripeProductId(),
                entity.getStripePriceId(),
                entity.getUnitAmountCents(),
                entity.getCurrency(),
                entity.getImageUrl()
        );
    }
}
