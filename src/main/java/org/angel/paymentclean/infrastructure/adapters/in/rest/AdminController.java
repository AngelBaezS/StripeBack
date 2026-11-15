package org.angel.paymentclean.infrastructure.adapters.in.rest;

import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.port.in.ManageCatalogUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.CreateProductRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.UpdatePriceRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ManageCatalogUseCase manageCatalogUseCase;

    public AdminController(ManageCatalogUseCase manageCatalogUseCase) {
        this.manageCatalogUseCase = manageCatalogUseCase;
    }

    @GetMapping({"/products"})
    public List<Product> getAllProducts() {
        return manageCatalogUseCase.listAll();
    }

    @PostMapping("/products")
    public Product createProduct(@RequestBody CreateProductRequest req) {
        return manageCatalogUseCase.createProduct(
                req.name(), req.description(), req.stock(), req.unitAmountCents(), req.imageUrl()
        );
    }

    @PutMapping("/products/{productId}/price")
    public Product updateProductPrice(@PathVariable String productId, @RequestBody UpdatePriceRequest req) {
        return manageCatalogUseCase.updateProductPrice(productId, req.newUnitAmountCents());
    }
}
