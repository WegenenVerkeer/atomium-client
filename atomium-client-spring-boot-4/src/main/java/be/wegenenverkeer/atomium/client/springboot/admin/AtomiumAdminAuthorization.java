package be.wegenenverkeer.atomium.client.springboot.admin;

import org.springframework.security.access.AccessDeniedException;

/**
 * The authorization seam of the admin endpoint: determines who may execute the {@code /rest/atomium/**} operations.
 * As soon as {@code atomium.admin.enabled=true} this bean is <b>required</b> — the application does not start
 * otherwise (fail fast; an admin endpoint that is unintentionally open cannot happen that way). With the admin
 * endpoint off, no bean is needed.
 *
 * <p>Every endpoint method calls the appropriate assert as its first statement: the read-only diagnostics (GET)
 * via {@link #assertReadPermission()}, the mutating operations (PUT/POST) via {@link #assertWritePermission()}. If the
 * current user may not execute the operation — including when there is no authenticated user at all — the
 * implementation throws an {@link AccessDeniedException}; the security filter chain translates it into a 403 (or an
 * authentication challenge for an anonymous user).
 *
 * <p>For the common case — one authority for both reading and writing — there is the bundled
 * {@link HasAuthorityAtomiumAdminAuthorization}:
 * <pre>{@code
 * @Bean
 * AtomiumAdminAuthorization atomiumAdminAuthorization() {
 *     return new HasAuthorityAtomiumAdminAuthorization("my-admin-role");
 * }
 * }</pre>
 */
public interface AtomiumAdminAuthorization {

    /** May the current user execute the read-only diagnostics (GETs)? If not: throw. */
    void assertReadPermission() throws AccessDeniedException;

    /** May the current user execute the mutating operations (PUT/POST)? If not: throw. */
    void assertWritePermission() throws AccessDeniedException;
}
