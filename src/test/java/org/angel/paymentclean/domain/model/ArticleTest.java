package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.exception.InvalidArticleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

public class ArticleTest {

    @Test
    void shouldCreateActiveArticleWhenValidDataProvided() {
        // Given & When: Instanciación directa del record
        Article article = new Article(
                1L,
                "Laptop Gamer",
                "https://example.com/image.png",
                new BigDecimal("1500.00"),
                "43211503",
                "H87",
                true
        );

        // Then: Verificación de reglas de negocio
        assertNotNull(article);
        assertTrue(article.active());
        assertEquals(new BigDecimal("1500.00"), article.unitPrice());
    }


    @ParameterizedTest
    @NullAndEmptySource // 1. Inyecta automáticamente: null y "" (empty)
    @ValueSource(strings = {" ", "   ","\t", "\n"}) // 2. Inyecta casos "blank" (espacios, tabulaciones, saltos)
    void shouldThrowExceptionWhenDescriptionIsInvalid(String invalidDescription) {
        assertThatThrownBy(() -> new Article(1L, invalidDescription, "img.png", new BigDecimal("100"), "43211503", "H87", true))
                .isInstanceOf(InvalidArticleException.class)
                .hasMessage("La descripción no puede estar vacía")
                .hasFieldOrPropertyWithValue("code", "INVALID_ARTICLE_DATA")
                .satisfies(ex -> {
                    InvalidArticleException domainEx = (InvalidArticleException) ex;
                    System.out.println("Código de error: " + domainEx.getCode());
                    System.out.println("Mensaje: " + domainEx.getMessage());
                });
    } 

    @ParameterizedTest
    @NullAndEmptySource // 1. Inyecta automáticamente: null y "" (empty)
    @ValueSource(strings = {" ", "   ","\t", "\n"}) // 2. Inyecta casos "blank" (espacios, tabulaciones, saltos)
    void shouldThrowExceptionWhenImagesUrlIsInvalid(String invalidImageUrl) {
        assertThatThrownBy(() -> new Article(1L, "Laptop Gamer", invalidImageUrl, new BigDecimal("100"), "43211503", "H87", true))
                .isInstanceOf(InvalidArticleException.class)
                .hasMessage("La imagen debe ser una URL válida")
                .hasFieldOrPropertyWithValue("code", "INVALID_ARTICLE_DATA")
                .satisfies(ex -> {
                    InvalidArticleException domainEx = (InvalidArticleException) ex;
                    System.out.println("Código de error: " + domainEx.getCode());
                    System.out.println("Mensaje: " + domainEx.getMessage());
                });
    }
}
