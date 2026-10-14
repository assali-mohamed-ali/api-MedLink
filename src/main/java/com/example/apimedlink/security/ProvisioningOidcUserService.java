package com.example.apimedlink.security;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.service.UserProvisioningService;
import java.util.UUID;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The redirect login's user loading, plus our row for the account (GitHub #29). A refused
 * account (email held by another account) fails the login: no session is created.
 * Sub-sprint 2's login endpoint calls {@link UserProvisioningService} the same way.
 */
public class ProvisioningOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final UserProvisioningService provisioning;

    public ProvisioningOidcUserService(UserProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        try {
            provisioning.provision(UUID.fromString(user.getSubject()), user.getEmail(), user.getPreferredUsername());
        } catch (ApiException refused) {
            OAuth2Error error = new OAuth2Error(refused.getCode().toLowerCase(), refused.getMessage(), null);
            throw new OAuth2AuthenticationException(error, refused);
        }
        return user;
    }
}
