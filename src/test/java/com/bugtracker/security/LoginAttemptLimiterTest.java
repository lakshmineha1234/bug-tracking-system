package com.bugtracker.security;

import com.bugtracker.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Limits: 3 failures per account, 5 per address, 2 wrong current passwords; 15-minute window and lockout. */
class LoginAttemptLimiterTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-28T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MutableClock clock;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new LoginAttemptLimiter(3, 5, 2, 15, 15);
        limiter.clock = clock;
    }

    @Test
    void accountLocksAfterTooManyFailures() {
        fail("tess@x.test", "10.0.0.1", 2);
        assertThatCode(() -> limiter.checkSignIn("tess@x.test", "10.0.0.1")).doesNotThrowAnyException();

        fail("tess@x.test", "10.0.0.1", 1);
        assertThatThrownBy(() -> limiter.checkSignIn("tess@x.test", "10.0.0.2")) // any address: the account is locked
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many failed sign-in attempts. Try again in 15 minutes.");
        assertThatCode(() -> limiter.checkSignIn("other@x.test", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void lockEndsAfterTheLockoutPeriod() {
        fail("tess@x.test", "10.0.0.1", 3);
        clock.advance(Duration.ofMinutes(14).plusSeconds(30));
        assertThatThrownBy(() -> limiter.checkSignIn("tess@x.test", "10.0.0.1"))
                .hasMessageContaining("Try again in 1 minute.");
        clock.advance(Duration.ofSeconds(31));
        assertThatCode(() -> limiter.checkSignIn("tess@x.test", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() {
        fail("tess@x.test", "10.0.0.1", 2);
        clock.advance(Duration.ofMinutes(16));
        fail("tess@x.test", "10.0.0.1", 2); // only 2 within the last 15 minutes
        assertThatCode(() -> limiter.checkSignIn("tess@x.test", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void successfulSignInClearsTheAccountCount() {
        fail("tess@x.test", "10.0.0.1", 2);
        limiter.signInSucceeded("TESS@x.test "); // same account, different case and spacing
        fail("tess@x.test", "10.0.0.1", 2);
        assertThatCode(() -> limiter.checkSignIn("tess@x.test", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void addressLocksAcrossAccounts() {
        for (int i = 0; i < 5; i++) {
            limiter.signInFailed("user" + i + "@x.test", "10.9.9.9"); // one failure each: no account locks
        }
        assertThatThrownBy(() -> limiter.checkSignIn("someone-else@x.test", "10.9.9.9"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.checkSignIn("someone-else@x.test", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void retryAfterMatchesTheRemainingLock() {
        fail("tess@x.test", "10.0.0.1", 3);
        clock.advance(Duration.ofMinutes(5));
        try {
            limiter.checkSignIn("tess@x.test", "10.0.0.1");
        } catch (TooManyRequestsException e) {
            assertThat(e.getRetryAfterSeconds()).isEqualTo(10 * 60 + 1);
            assertThat(e.getMessage()).endsWith("Try again in 10 minutes.");
            return;
        }
        throw new AssertionError("expected the account to be locked");
    }

    @Test
    void passwordChangeIsLimitedPerUser() {
        limiter.passwordChangeFailed(7L);
        limiter.passwordChangeFailed(7L);
        assertThatThrownBy(() -> limiter.checkPasswordChange(7L))
                .hasMessage("Too many wrong current passwords. Try again in 15 minutes.");
        assertThatCode(() -> limiter.checkPasswordChange(8L)).doesNotThrowAnyException();
    }

    private void fail(String email, String address, int times) {
        for (int i = 0; i < times; i++) {
            limiter.signInFailed(email, address);
        }
    }
}
