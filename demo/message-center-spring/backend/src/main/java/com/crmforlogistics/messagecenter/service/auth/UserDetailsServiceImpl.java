package com.crmforlogistics.messagecenter.service.auth;

import com.crmforlogistics.messagecenter.entity.RoleEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;

    public UserDetailsServiceImpl(UserMapper userMapper, RoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = normalizeUsername(username);
        UserEntity user = userMapper.findByUsernameNormalized(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return toUserDetails(user);
    }

    public UserDetails loadUserById(UUID userId) throws UsernameNotFoundException {
        UserEntity user = userMapper.findByIdNotDeleted(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        return toUserDetails(user);
    }

    private UserDetails toUserDetails(UserEntity user) {
        if (!"active".equals(user.getStatus())) {
            throw new org.springframework.security.authentication.DisabledException(
                    "User is not active: " + user.getStatus());
        }

        List<RoleEntity> roles = roleMapper.findByUserId(user.getId());
        List<GrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getCode().toUpperCase()))
                .collect(Collectors.toList());

        return new User(user.getId().toString(), user.getPasswordHash(), authorities);
    }

    static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        return Normalizer.normalize(username, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }
}
