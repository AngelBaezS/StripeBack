package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.exception.InvalidProductException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

public class ProductTest {

    @Test
    void shouldCreateActiveProductWhenValidDataProvided() {
        // Given & When: Instanciación directa del record
        Product product = new Product(
                "prd_test_1",
                "Laptop Gamer",
                "Laptop RTX 3080",
                10,
                true,
                "prod_123",
                "price_123",
                150000L,
                "mxn"
        );

        // Then: Verificación de reglas de negocio
        assertNotNull(product);
        assertTrue(product.active());
        assertEquals(150000L, product.unitAmountCents());
        assertNull(product.imageUrl());
    }

    @Test
    void shouldCreateProductWithImageUrlWhenProvided() {
        Product product = new Product(
                "prd_test_2",
                "Monitor 4K",
                "Monitor 144Hz IPS",
                8,
                true,
                "prod_456",
                "price_456",
                80000L,
                "mxn",
                "https://ejemplo.com/imagenes/monitor.png"
        );

        assertNotNull(product);
        assertEquals("https://ejemplo.com/imagenes/monitor.png", product.imageUrl());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void shouldThrowExceptionWhenNameIsInvalid(String invalidName) {
        assertThatThrownBy(() -> new Product("prd_test_1", invalidName, "Laptop RTX 3080", 10, true, "prod_123", "price_123", 150000L, "mxn"))
                .isInstanceOf(InvalidProductException.class)
                .hasMessage("El nombre no puede estar vacio")
                .hasFieldOrPropertyWithValue("code", "INVALID_ARTICLE_DATA");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void shouldThrowExceptionWhenDescriptionIsInvalid(String invalidDescription) {
        assertThatThrownBy(() -> new Product("prd_test_1", "Laptop Gamer", invalidDescription, 10, true, "prod_123", "price_123", 150000L, "mxn"))
                .isInstanceOf(InvalidProductException.class)
                .hasMessage("La descripción no puede estar vacía")
                .hasFieldOrPropertyWithValue("code", "INVALID_ARTICLE_DATA");
    }
}
