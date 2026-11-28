package com.bugtracker.security;

import com.bugtracker.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing. Failed attempts are counted per key (an account, a network address,
 * a user changing their password); when a key reaches its limit within the time window it is locked
 * for the lockout period, and every attempt for it is refused until then, even with the right password.
 * <p>
 * Counts are kept in memory: they reset when the app restarts and apply per app instance.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final int CLEANUP_EVERY = 1_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxFailuresPerAccount;
    private final int maxFailuresPerAddress;
    private final int maxPasswordChangeFailures;
    private final Duration window;
    private final Duration lockout;
    private int callsSinceCleanup;

    /** Replaceable in tests. */
    Clock clock = Clock.systemUTC();

    private static final class Attempts {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant lockedUntil;
    }

    public LoginAttemptLimiter(@Value("${app.security.login.max-failures-per-account:10}") int maxFailuresPerAccount,
                               @Value("${app.security.login.max-failures-per-ip:30}") int maxFailuresPerAddress,
                               @Value("${app.security.password-change.max-failures:5}") int maxPasswordChangeFailures,
                               @Value("${app.security.login.window-minutes:15}") long windowMinutes,
                               @Value("${app.security.login.lockout-minutes:15}") long lockoutMinutes) {
        this.maxFailuresPerAccount = maxFailuresPerAccount;
        this.maxFailuresPerAddress = maxFailuresPerAddress;
        this.maxPasswordChangeFailures = maxPasswordChangeFailures;
        this.window = Duration.ofMinutes(windowMinutes);
        this.lockout = Duration.ofMinutes(lockoutMinutes);
    }

    /** Refuses the sign-in if the account or the address it comes from is locked. */
    public void checkSignIn(String email, String address) {
        refuseIfLocked("Too many failed sign-in attempts.", accountKey(email), addressKey(address));
    }

    public void signInFailed(String email, String address) {
        failure(accountKey(email), maxFailuresPerAccount);
        failure(addressKey(address), maxFailuresPerAddress);
    }

    /** A successful sign-in clears the account's count (not the address's, so one valid account can't reset it). */
    public void signInSucceeded(String email) {
        attempts.remove(accountKey(email));
    }

    public void checkPasswordChange(Long userId) {
        refuseIfLocked("Too many wrong current passwords.", passwordKey(userId));
    }

    public void passwordChangeFailed(Long userId) {
        failure(passwordKey(userId), maxPasswordChangeFailures);
    }

    public void passwordChangeSucceeded(Long userId) {
        attempts.remove(passwordKey(userId));
    }

    private void refuseIfLocked(String reason, String... keys) {
        Instant now = clock.instant();
        Duration longest = Duration.ZERO;
        for (String key : keys) {
            Attempts a = attempts.get(key);
            if (a == null) {
                continue;
            }
            synchronized (a) {
                if (a.lockedUntil != null && a.lockedUntil.isAfter(now)) {
                    Duration remaining = Duration.between(now, a.lockedUntil);
                    if (remaining.compareTo(longest) > 0) {
                        longest = remaining;
                    }
                }
            }
        }
        if (!longest.isZero()) {
            long minutes = Math.max(1, (longest.toSeconds() + 59) / 60);
            throw new TooManyRequestsException(reason + " Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."),
                    longest.toSeconds() + 1);
        }
    }

    /**
     * Records a failure. The whole update runs inside the map's per-key atomic compute, so the cleanup can never
     * remove the entry halfway through and lose the failure.
     */
    private void failure(String key, int max) {
        Instant now = clock.instant();
        boolean[] lockedNow = {false};
        attempts.compute(key, (k, existing) -> {
            Attempts a = existing != null ? existing : new Attempts();
            synchronized (a) {
                if (a.lockedUntil != null && a.lockedUntil.isAfter(now)) {
                    return a; // already locked
                }
                a.lockedUntil = null;
                prune(a, now);
                a.failures.addLast(now);
                if (a.failures.size() >= max) {
                    a.lockedUntil = now.plus(lockout);
                    a.failures.clear();
                    lockedNow[0] = true;
                }
            }
            return a;
        });
        if (lockedNow[0]) {
            log.warn("Locked {} for {} minutes after {} failed attempts", describe(key), lockout.toMinutes(), max);
        }
        cleanUpOccasionally(now);
    }

    private void prune(Attempts a, Instant now) {
        Instant cutoff = now.minus(window);
        while (!a.failures.isEmpty() && !a.failures.peekFirst().isAfter(cutoff)) {
            a.failures.removeFirst();
        }
    }

    /** Drops entries with no recent failures and no lock, so memory stays bounded. */
    private void cleanUpOccasionally(Instant now) {
        synchronized (this) {
            if (++callsSinceCleanup < CLEANUP_EVERY) {
                return;
            }
            callsSinceCleanup = 0;
        }
        // computeIfPresent is atomic per key with compute() in failure(), so an entry is only removed when no
        // failure is being recorded on it at the same moment.
        for (String key : attempts.keySet()) {
            attempts.computeIfPresent(key, (k, a) -> {
                synchronized (a) {
                    prune(a, now);
                    boolean idle = a.failures.isEmpty() && (a.lockedUntil == null || !a.lockedUntil.isAfter(now));
                    return idle ? null : a;
                }
            });
        }
    }

    private static String accountKey(String email) {
        return "account:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "address:" + address;
    }

    private static String passwordKey(Long userId) {
        return "password-change:" + userId;
    }

    /** For the log: which kind of key, without the account's email address. */
    private static String describe(String key) {
        if (key.startsWith("account:")) {
            return "an account";
        }
        if (key.startsWith("address:")) {
            return "network address " + key.substring("address:".length());
        }
        return "password changes for user " + key.substring("password-change:".length());
    }
}
