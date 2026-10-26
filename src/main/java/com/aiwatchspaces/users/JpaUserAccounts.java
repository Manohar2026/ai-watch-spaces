package com.aiwatchspaces.users;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.security.SystemRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaUserAccounts implements UserAccounts {

    static final String DEFAULT_SUBTITLE_LOCALE = "en";

    private final AppUserRepository repository;

    JpaUserAccounts(AppUserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UserProfile register(NewUserAccount account, Instant now) {
        if (repository.existsByNormalizedEmail(account.normalizedEmail())) {
            throw emailTaken();
        }
        AppUser user = new AppUser(UUID.randomUUID(), account.email(), account.normalizedEmail(),
                account.passwordHash(), account.displayName(), DEFAULT_SUBTITLE_LOCALE, now);
        try {
            return repository.saveAndFlush(user).toProfile();
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration won the unique constraint on normalized_email.
            throw emailTaken();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentials(String normalizedEmail) {
        return repository.findByNormalizedEmail(normalizedEmail).map(AppUser::toCredentials);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserProfile> findProfile(UUID userId) {
        return repository.findById(userId).map(AppUser::toProfile);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SystemRole> findSystemRole(UUID userId) {
        return repository.findById(userId).map(AppUser::systemRole);
    }

    @Override
    @Transactional
    public UserProfile updateProfile(UUID userId, ProfileChanges changes, Instant now) {
        AppUser user = repository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Resource not found."));
        if (changes.displayName() != null) {
            user.changeDisplayName(changes.displayName(), now);
        }
        if (changes.subtitleLocale() != null) {
            user.changeSubtitleLocale(changes.subtitleLocale(), now);
        }
        return repository.saveAndFlush(user).toProfile();
    }

    private static ApiException emailTaken() {
        return new ApiException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists.");
    }
}
