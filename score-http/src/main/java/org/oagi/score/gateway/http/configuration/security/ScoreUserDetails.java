package org.oagi.score.gateway.http.configuration.security;

import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.oagi.score.gateway.http.api.account_management.model.UserId;

import java.util.Collection;

public class ScoreUserDetails extends User implements AuthenticatedPrincipal {

    private static final long serialVersionUID = -8153203188717677779L;

    private final UserId userId;

    public ScoreUserDetails(String username, String password,
                            Collection<? extends GrantedAuthority> authorities) {
        this(null, username, password, true, true, true, true, authorities);
    }

    public ScoreUserDetails(UserId userId, String username, String password,
                            Collection<? extends GrantedAuthority> authorities) {
        this(userId, username, password, true, true, true, true, authorities);
    }

    public ScoreUserDetails(String username, String password,
                            boolean enabled, boolean accountNonExpired,
                            boolean credentialsNonExpired, boolean accountNonLocked,
                            Collection<? extends GrantedAuthority> authorities) {
        this(null, username, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
    }

    public ScoreUserDetails(UserId userId, String username, String password,
                            boolean enabled, boolean accountNonExpired,
                            boolean credentialsNonExpired, boolean accountNonLocked,
                            Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
        this.userId = userId;
    }

    @Override
    public String getName() {
        return getUsername();
    }

    public UserId getUserId() {
        return userId;
    }

}
