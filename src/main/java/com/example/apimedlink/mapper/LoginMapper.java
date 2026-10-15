package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.LoginResponse;
import com.example.apimedlink.dto.SessionUserDto;
import com.example.apimedlink.service.Login;

/** Login to API shape. Called by controllers: services never see a DTO. */
public final class LoginMapper {

    private LoginMapper() {}

    public static LoginResponse toResponse(Login login) {
        return new LoginResponse(new SessionUserDto(
                login.user().getId(), login.user().getEmail(), login.firstName(), login.lastName(), login.roles()));
    }
}
