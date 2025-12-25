package org.keepassdx.output;

// Minimal IPC contract
interface IOutputCredentialsService {
    // Returns a provider identifier like "BluKeyborg"
    String getProviderName();

    // Sends credentials to provider for typing via dongle.
    // Returns 0 on success, non-zero error codes on failure.
    int sendPayload(
        String requestId,
        String mode,          // e.g. "user", "pass", "user_tab_pass_enter", "user_enter_pass_enter"
        String username,
        String password,
        String otp,           // optional, can be null
        String entryTitle,    // optional, can be null
        String entryUuid      // optional, can be null
    );
}
