package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class UserRepositoryAdapter implements UserRepositoryPort {

    private final UserRepository userRepository;

    public UserRepositoryAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User save(User user) {
        UserEntity entity = toEntity(user);
        UserEntity saved = userRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username).map(this::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email).map(this::toDomain);
    }

    @Override
    public Optional<User> findById(String id) {
        return userRepository.findById(id).map(this::toDomain);
    }

    private UserEntity toEntity(User domain){
        return new UserEntity(
                domain.id(),
                domain.username(),
                domain.email(),
                domain.password(),
                domain.phone(),
                domain.stripeCustomerId(),
                domain.role()
        );
    }

    private User toDomain(UserEntity entity){
        return new User(
                entity.getId(),
                entity.getUsername(),
                entity.getEmail(),
                entity.getPassword(),
                entity.getPhone(),
                entity.getStripeCustomerId(),
                entity.getRole()
        );
    }
}
