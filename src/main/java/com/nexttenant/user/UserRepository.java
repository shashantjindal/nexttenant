package com.nexttenant.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Public profiles.
 *
 * <p>Spring Data generates the implementation at runtime by parsing method
 * names — there is deliberately no class implementing this interface, which is
 * worth knowing before going looking for one.
 *
 * <p>Nothing here can reach contact details: that is
 * {@link UserPrivateRepository}, and using it is a separate decision.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
}
