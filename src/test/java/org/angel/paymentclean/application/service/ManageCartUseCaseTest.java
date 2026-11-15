package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.UserNotFoundException;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.CartDetail;
import org.angel.paymentclean.domain.model.CartItem;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManageCartUseCaseTest {

    @Mock
    private CartRepositoryPort cartRepository;

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private ProductRepositoryPort productRepository;

    private ManageCartUseCaseImpl manageCartUseCase;

    private User sampleUser;
    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        manageCartUseCase = new ManageCartUseCaseImpl(cartRepository, userRepository, productRepository);
        sampleUser = new User("usr_123", "angel", "angel@test.com", "pass123", "5551234567", "cus_123");
        sampleProduct = new Product("prd_123", "Teclado Mecanico", "Switch Blue", 5, true, "prod_1", "price_1", 120000L, "mxn", "https://img.png");
    }

    @Test
    void shouldReturnEmptyCartWhenUserHasNoActiveCart() {
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus("usr_123", CartStatus.ACTIVE)).thenReturn(Optional.empty());

        CartDetail cart = manageCartUseCase.getCart("angel");

        assertThat(cart).isNotNull();
        assertThat(cart.userId()).isEqualTo("usr_123");
        assertThat(cart.status()).isEqualTo(CartStatus.ACTIVE);
        assertThat(cart.items()).isEmpty();
        assertThat(cart.totalAmountCents()).isEqualTo(0L);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void shouldThrowUserNotFoundWhenUsernameDoesNotExist() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manageCartUseCase.getCart("unknown"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void shouldAddNewItemToCartSuccessfully() {
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(sampleUser));
        when(productRepository.findById("prd_123")).thenReturn(Optional.of(sampleProduct));
        when(cartRepository.findByUserIdAndStatus("usr_123", CartStatus.ACTIVE)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CartDetail cart = manageCartUseCase.addItemToCart("angel", "prd_123", 2);

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).productId()).isEqualTo("prd_123");
        assertThat(cart.items().get(0).quantity()).isEqualTo(2);
        assertThat(cart.items().get(0).subtotalCents()).isEqualTo(240000L);
        assertThat(cart.totalAmountCents()).isEqualTo(240000L);
        verify(cartRepository, atLeastOnce()).save(any(Cart.class));
    }

    @Test
    void shouldIncrementQuantityWhenProductAlreadyInCart() {
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(sampleUser));
        when(productRepository.findById("prd_123")).thenReturn(Optional.of(sampleProduct));

        List<CartItem> existingItems = new ArrayList<>();
        existingItems.add(new CartItem("cit_100", "prd_123", 2));
        Cart existingCart = new Cart("crt_123", "usr_123", CartStatus.ACTIVE, LocalDateTime.now(), existingItems);

        when(cartRepository.findByUserIdAndStatus("usr_123", CartStatus.ACTIVE)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CartDetail cart = manageCartUseCase.addItemToCart("angel", "prd_123", 2);

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).quantity()).isEqualTo(4);
        assertThat(cart.totalAmountCents()).isEqualTo(480000L);
    }

    @Test
    void shouldThrowConflictWhenAddingMoreThanAvailableStock() {
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(sampleUser));
        when(productRepository.findById("prd_123")).thenReturn(Optional.of(sampleProduct)); // stock is 5
        when(cartRepository.findByUserIdAndStatus("usr_123", CartStatus.ACTIVE)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> manageCartUseCase.addItemToCart("angel", "prd_123", 10))
                .isInstanceOf(ArticleBusinessConflictException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void shouldRemoveItemFromCart() {
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(sampleUser));

        List<CartItem> existingItems = new ArrayList<>();
        existingItems.add(new CartItem("cit_100", "prd_123", 2));
        Cart existingCart = new Cart("crt_123", "usr_123", CartStatus.ACTIVE, LocalDateTime.now(), existingItems);

        when(cartRepository.findByUserIdAndStatus("usr_123", CartStatus.ACTIVE)).thenReturn(Optional.of(existingCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CartDetail cart = manageCartUseCase.removeItemFromCart("angel", "prd_123");

        assertThat(cart.items()).isEmpty();
        assertThat(cart.totalAmountCents()).isEqualTo(0L);
    }

    @Test
    void shouldClearCart() {
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(sampleUser));

        List<CartItem> existingItems = new ArrayList<>();
        existingItems.add(new CartItem("cit_100", "prd_123", 2));
        Cart existingCart = new Cart("crt_123", "usr_123", CartStatus.ACTIVE, LocalDateTime.now(), existingItems);

        when(cartRepository.findByUserIdAndStatus("usr_123", CartStatus.ACTIVE)).thenReturn(Optional.of(existingCart));

        manageCartUseCase.clearCart("angel");

        verify(cartRepository, times(1)).save(argThat(cart -> cart.items().isEmpty()));
    }
}
