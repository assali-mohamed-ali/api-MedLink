package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.UserDto;
import com.example.apimedlink.model.User;

/** Entity to API shape. Called by controllers: services never see a DTO. */
public final class UserMapper {

    private UserMapper() {}

    public static UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getEmail());
    }
}
