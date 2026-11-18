package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByDeviceId(String deviceId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByDeviceId(String deviceId);
}
