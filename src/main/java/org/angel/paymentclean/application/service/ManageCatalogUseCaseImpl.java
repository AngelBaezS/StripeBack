package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.exception.ProductNotFoundException;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.StripeCatalogResult;
import org.angel.paymentclean.domain.port.in.ManageCatalogUseCase;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.StripeCatalogPort;
import org.angel.paymentclean.domain.util.IdGenerator;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.ProductForUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ManageCatalogUseCaseImpl implements ManageCatalogUseCase {

    private final ProductRepositoryPort productRepository;
    private final StripeCatalogPort stripeCatalogPort;

    public ManageCatalogUseCaseImpl(ProductRepositoryPort productRepository, StripeCatalogPort stripeCatalogPort) {
        this.productRepository = productRepository;
        this.stripeCatalogPort = stripeCatalogPort;
    }

    @Override
    @Transactional
    public Product createProduct(String name, String description, Integer stock, Long unitAmountCents, String imageUrl) {
        String prodId = IdGenerator.productId();

        Product initial = productRepository.save(
                new Product(prodId, name, description, stock, true, null, null, unitAmountCents, "mxn", imageUrl)
        );

        StripeCatalogResult stripeResult = stripeCatalogPort.crearProducto(
                initial.id(), name, description, unitAmountCents
        );

        return productRepository.save(new Product(
                initial.id(), name, description, stock, true, stripeResult.stripeProductId(), stripeResult.stripePriceId(),
                unitAmountCents, "mxn", imageUrl
        ));
    }

    @Override
    @Transactional
    public Product createProduct(String name, String description, Integer stock, Long unitAmountCents) {
        return createProduct(name, description, stock, unitAmountCents, null);
    }

    @Override
    @Transactional
    public Product updateProductPrice(String productId, Long newUnitAmountCents) {
        Product current = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        String newStripePriceId = stripeCatalogPort.actualizarPrecioDeProducto(
                current.stripeProductId(),
                current.stripePriceId(),
                newUnitAmountCents
        );

        return productRepository.save(new Product(
                current.id(), current.name(), current.description(), current.stock(), current.active(),
                current.stripeProductId(), newStripePriceId, newUnitAmountCents, "mxn", current.imageUrl()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> listAll() {
        return productRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductForUser> listAllForUsers() {
        return productRepository.findAll().stream()
                .map(product -> new ProductForUser(
                        product.id(),
                        product.name(),
                        product.description(),
                        product.unitAmountCents(),
                        product.imageUrl()
                ))
                .toList();
    }
}
