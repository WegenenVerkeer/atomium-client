package be.wegenenverkeer.atomium.client.springboot.demo;

import be.wegenenverkeer.atomium.client.springboot.admin.AtomiumAdminAuthorization;
import be.wegenenverkeer.atomium.client.springboot.admin.HasAuthorityAtomiumAdminAuthorization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Simple demo security: HTTP Basic with two in-memory users, plus the (mandatory)
 * {@link AtomiumAdminAuthorization} bean that determines who may access the admin endpoint
 * ({@code /rest/atomium/**}).
 *
 * <p>The in-memory feed ({@code /demo-feed/**}) and the actuator ({@code /management/**}) are public; everything
 * else requires authentication. The user {@code feed-admin} has the authority {@code feed-admin} and
 * may therefore access the admin endpoints; {@code viewer} does not have it and gets a 403 there —
 * showing the authorization in action.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Who may access the admin endpoint? Mandatory bean once {@code atomium.admin.enabled=true}. */
    @Bean
    AtomiumAdminAuthorization atomiumAdminAuthorization() {
        return new HasAuthorityAtomiumAdminAuthorization("feed-admin");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // stateless API for a demo; CSRF is then not relevant
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(spec -> spec
                        .requestMatchers("/demo-feed/**", "/management/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /**
     * Two demo users with a {@code {noop}} password (plaintext, for the demo only). Note: {@code authorities(...)}
     * — not {@code roles(...)} — so the authority is exactly {@code feed-admin} and {@code hasAuthority(...)}
     * matches (without the {@code ROLE_} prefix).
     */
    @Bean
    public UserDetailsService users() {
        UserDetails feedAdmin = User.withUsername("feed-admin")
                .password("{noop}feed-admin")
                .authorities("feed-admin")
                .build();
        UserDetails viewer = User.withUsername("viewer")
                .password("{noop}viewer")
                .authorities("viewer")
                .build();
        return new InMemoryUserDetailsManager(feedAdmin, viewer);
    }
}
