package dev.antite.browser;

import android.app.Activity;
import android.content.SharedPreferences;

import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AutocompleteRequest;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;

final class AutofillPrompts {
    private final Activity a;
    private final SharedPreferences prefs;
    private final Vault vault;

    AutofillPrompts(Activity a, SharedPreferences prefs, Vault vault) {
        this.a = a;
        this.prefs = prefs;
        this.vault = vault;
    }

    GeckoResult<PromptResponse> loginSave(AutocompleteRequest<Autocomplete.LoginSaveOption> req) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        if (!prefs.getBoolean("passwordsSave", true) || req.options.length == 0) {
            r.complete(req.dismiss());
            return r;
        }
        Autocomplete.LoginSaveOption opt = req.options[0];
        Autocomplete.LoginEntry e = opt.value;
        vault.read(() -> new Object[]{vault.isNever(e.origin), vault.existing(e.origin, e.username)}, st -> {
            if ((Boolean) st[0]) {
                r.complete(req.dismiss());
                return;
            }
            boolean update = st[1] != null;
            String user = e.username == null || e.username.isEmpty() ? "Без имени пользователя" : e.username;
            String[] answer = {null};
            Sheet s = new Sheet(a)
                    .setIcon(R.drawable.ic_key)
                    .setTitle(update ? "Обновить пароль?" : "Сохранить пароль?")
                    .setMessage(Urls.host(e.origin) + "\n" + user + "\n" + mask(e.password))
                    .setStacked(true)
                    .setPositiveButton(update ? "Обновить" : "Сохранить", (d, w) -> answer[0] = "save")
                    .setNegativeButton("Не сейчас", null)
                    .setOnDismissListener(d -> {
                        if ("save".equals(answer[0])) {
                            r.complete(req.confirm(opt));
                        } else {
                            if ("never".equals(answer[0])) vault.run(() -> vault.addNever(e.origin));
                            r.complete(req.dismiss());
                        }
                    });
            if (!update) s.setNeutralButton("Никогда для этого сайта", (d, w) -> answer[0] = "never");
            s.show();
        });
        return r;
    }

    GeckoResult<PromptResponse> loginSelect(AutocompleteRequest<Autocomplete.LoginSelectOption> req) {
        String[] labels = new String[req.options.length];
        for (int i = 0; i < labels.length; i++) {
            Autocomplete.LoginSelectOption o = req.options[i];
            if ((o.hint & Autocomplete.SelectOption.Hint.GENERATED) != 0) {
                labels[i] = "Предложить надёжный пароль";
            } else {
                String u = o.value.username;
                labels[i] = u == null || u.isEmpty() ? "Без имени пользователя" : u;
            }
        }
        return choose("Войти как", labels, req);
    }

    GeckoResult<PromptResponse> cardSelect(AutocompleteRequest<Autocomplete.CreditCardSelectOption> req) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        String[] labels = new String[req.options.length];
        for (int i = 0; i < labels.length; i++) {
            Autocomplete.CreditCard c = req.options[i].value;
            String n = c.number == null ? "" : c.number.replaceAll("\\D", "");
            labels[i] = "•••• " + (n.length() >= 4 ? n.substring(n.length() - 4) : n)
                    + (c.name == null || c.name.isEmpty() ? "" : "  " + c.name);
        }
        Auth.require(a, "Заполнить данные карты",
                () -> choose("Выберите карту", labels, req).accept(r::complete),
                () -> r.complete(req.dismiss()));
        return r;
    }

    GeckoResult<PromptResponse> addressSelect(AutocompleteRequest<Autocomplete.AddressSelectOption> req) {
        String[] labels = new String[req.options.length];
        for (int i = 0; i < labels.length; i++) labels[i] = addressLine(req.options[i].value);
        return choose("Выберите адрес", labels, req);
    }

    GeckoResult<PromptResponse> cardSave(AutocompleteRequest<Autocomplete.CreditCardSaveOption> req) {
        if (!prefs.getBoolean("cardsEnabled", true) || req.options.length == 0) {
            return GeckoResult.fromValue(req.dismiss());
        }
        Autocomplete.CreditCard c = req.options[0].value;
        String n = c.number == null ? "" : c.number.replaceAll("\\D", "");
        String text = "•••• " + (n.length() >= 4 ? n.substring(n.length() - 4) : n)
                + (c.expirationMonth == null || c.expirationMonth.isEmpty() ? ""
                : "\nДействует до " + c.expirationMonth + "/" + c.expirationYear);
        return confirmSave("Сохранить карту?", text, req, req.options[0]);
    }

    GeckoResult<PromptResponse> addressSave(AutocompleteRequest<Autocomplete.AddressSaveOption> req) {
        if (!prefs.getBoolean("addressesEnabled", true) || req.options.length == 0) {
            return GeckoResult.fromValue(req.dismiss());
        }
        return confirmSave("Сохранить адрес?", addressLine(req.options[0].value), req, req.options[0]);
    }

    private GeckoResult<PromptResponse> confirmSave(String title, String text, AutocompleteRequest<?> req,
                                                    Autocomplete.Option<?> opt) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        boolean[] save = {false};
        new Sheet(a)
                .setTitle(title)
                .setMessage(text)
                .setStacked(true)
                .setPositiveButton("Сохранить", (d, w) -> save[0] = true)
                .setNegativeButton("Не сейчас", null)
                .setOnDismissListener(d -> r.complete(save[0] ? req.confirm(opt) : req.dismiss()))
                .show();
        return r;
    }

    private GeckoResult<PromptResponse> choose(String title, String[] labels, AutocompleteRequest<?> req) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        int[] picked = {-1};
        new Sheet(a)
                .setTitle(title)
                .setItems(labels, (d, w) -> picked[0] = w)
                .setOnDismissListener(d -> r.complete(picked[0] >= 0
                        ? req.confirm(req.options[picked[0]]) : req.dismiss()))
                .show();
        return r;
    }

    static String addressLine(Autocomplete.Address ad) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{ad.name, ad.streetAddress, ad.addressLevel2, ad.postalCode, ad.tel,
                ad.email}) {
            if (part == null || part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(part.replace('\n', ' '));
        }
        return sb.length() == 0 ? "Пустой адрес" : sb.toString();
    }

    private static String mask(String password) {
        int n = password == null ? 0 : Math.min(password.length(), 12);
        return "•".repeat(Math.max(n, 6));
    }
}
