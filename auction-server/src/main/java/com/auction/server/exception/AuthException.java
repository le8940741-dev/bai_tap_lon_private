package com.auction.server.exception;

/**
 * FILE ROLE: Signals a Auth-domain business rule violation.
 *
 * Thrown by UserService when:
 *   - Username or email already taken during registration
 *   - Password is too short (< 4 characters)
 *   - Credentials don't match during login
 *   - Trying to log in with a banned (inactive) account
 *   - Non-admin tries to ban a user
 *
 * HOW IT IS HANDLED:
 *   ClientHandler's dispatch() method catches all three exception types in its
 *   try/catch block and converts them to an ERROR message with the exception's
 *   message text, which the client displays in the status label.
 *
 *   This means service methods never need to know about the network layer —
 *   they just throw and let the handler deal with it.
 *
 * EXTENDS RuntimeException:
 *   Unchecked so callers don't need to declare 'throws' everywhere.
 *   These are not recoverable programming errors — they are expected business
 *   rule violations that need to be reported to the user.
 */
public class AuthException extends RuntimeException {
    public AuthException(String message) { super(message); }
    public AuthException(String message, Throwable cause) { super(message, cause); }
}
