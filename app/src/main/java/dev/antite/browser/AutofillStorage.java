package dev.antite.browser;

import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;

import java.util.List;
import java.util.function.Supplier;

final class AutofillStorage implements Autocomplete.StorageDelegate {
    private final Vault vault;

    AutofillStorage(Vault vault) {
        this.vault = vault;
    }

    private <T> GeckoResult<T[]> fetch(Supplier<List<T>> query, T[] empty) {
        GeckoResult<T[]> r = new GeckoResult<>();
        vault.run(() -> r.complete(query.get().toArray(empty)));
        return r;
    }

    @Override
    public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch(String domain) {
        return fetch(() -> vault.loginsFor(domain), new Autocomplete.LoginEntry[0]);
    }

    @Override
    public GeckoResult<Autocomplete.CreditCard[]> onCreditCardFetch() {
        return fetch(vault::fullCards, new Autocomplete.CreditCard[0]);
    }

    @Override
    public GeckoResult<Autocomplete.Address[]> onAddressFetch() {
        return fetch(vault::addresses, new Autocomplete.Address[0]);
    }

    @Override
    public void onLoginSave(Autocomplete.LoginEntry login) {
        vault.run(() -> vault.saveLogin(login));
    }

    @Override
    public void onCreditCardSave(Autocomplete.CreditCard card) {
        vault.run(() -> vault.saveCard(card));
    }

    @Override
    public void onAddressSave(Autocomplete.Address address) {
        vault.run(() -> vault.saveAddress(address));
    }

    @Override
    public void onLoginUsed(Autocomplete.LoginEntry login, int fields) {
        vault.run(() -> vault.loginUsed(login.guid));
    }
}
