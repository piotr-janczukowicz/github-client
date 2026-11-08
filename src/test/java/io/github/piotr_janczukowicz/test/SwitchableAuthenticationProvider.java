package io.github.piotr_janczukowicz.test;

import io.github.piotr_janczukowicz.http.client.authentication.Authentication;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class SwitchableAuthenticationProvider implements AuthenticationProvider {

    private final AtomicReference<AuthenticationProvider> current;

    public SwitchableAuthenticationProvider(final AuthenticationProvider initial) {
        this.current = new AtomicReference<>(initial);
    }

    public void use(final AuthenticationProvider next) {
        current.set(next);
    }

    @Override
    public Optional<Authentication> authentication() {
        return current.get().authentication();
    }

    @Override
    public boolean isRenewableAfterRejection(final Authentication rejected) {
        return current.get().isRenewableAfterRejection(rejected);
    }
}
