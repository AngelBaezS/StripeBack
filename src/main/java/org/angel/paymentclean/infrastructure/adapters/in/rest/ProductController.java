package org.angel.paymentclean.infrastructure.adapters.in.rest;

import org.angel.paymentclean.domain.port.in.ManageCatalogUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.ProductForUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ManageCatalogUseCase manageCatalogUseCase;

    public ProductController(ManageCatalogUseCase manageCatalogUseCase) {
        this.manageCatalogUseCase = manageCatalogUseCase;
    }

    @GetMapping
    public List<ProductForUser> getAlllProductsUser() {
        return manageCatalogUseCase.listAllForUsers();
    }
}
