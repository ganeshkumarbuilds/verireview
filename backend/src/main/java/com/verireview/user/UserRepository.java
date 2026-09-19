package com.verireview.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String email);

  @EntityGraph(attributePaths = "roles")
  Optional<User> findWithRolesByEmail(String email);
}
