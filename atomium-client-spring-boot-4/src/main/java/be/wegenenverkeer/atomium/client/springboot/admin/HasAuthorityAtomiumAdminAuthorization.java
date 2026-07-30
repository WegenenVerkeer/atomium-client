package be.wegenenverkeer.atomium.client.springboot.admin;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Ready-made {@link AtomiumAdminAuthorization}: read and write permission for whoever carries the given authority
 * (exact, case-sensitive match on the {@code SecurityContext} authorities — like {@code hasAuthority}, so
 * without the implicit {@code ROLE_} prefix). To tie reading and writing to different permissions, implement
 * the interface yourself.
 */
public final class HasAuthorityAtomiumAdminAuthorization implements AtomiumAdminAuthorization {

    private final String authority;

    public HasAuthorityAtomiumAdminAuthorization(String authority) {
        this.authority = authority;
    }

    @Override
    public void assertReadPermission() {
        assertAuthority();
    }

    @Override
    public void assertWritePermission() {
        assertAuthority();
    }

    private void assertAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        if (!allowed) {
            throw new AccessDeniedException("requires the authority '%s'".formatted(authority));
        }
    }
}
