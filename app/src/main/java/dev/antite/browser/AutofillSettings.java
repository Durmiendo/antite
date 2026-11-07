package dev.antite.browser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PersistableBundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.mozilla.geckoview.Autocomplete;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;

final class AutofillSettings {
    interface Files {
        void openFile(String mime, Consumer<Uri> picked);

        void createFile(String name, String mime, Consumer<Uri> picked);
    }

    private final Activity a;
    private final Settings ui;
    private final SharedPreferences prefs;
    private final Vault vault;
    private final Files files;

    AutofillSettings(Activity a, Settings ui, SharedPreferences prefs, Vault vault, Files files) {
        this.a = a;
        this.ui = ui;
        this.prefs = prefs;
        this.vault = vault;
        this.files = files;
    }

    void buildPasswords() {
        ui.toggle("Предлагать сохранение паролей", null, "passwordsSave", true, true);
        ui.toggle("Автоматически заполнять пароли", "Подставлять сохранённые данные на страницах входа",
                "passwordsAutofill", true, true);
        int gen = ui.generation;
        vault.read(() -> new Object[]{vault.logins(null), vault.nevers()}, r -> {
            if (gen != ui.generation) return;
            @SuppressWarnings("unchecked") List<Vault.Login> logins = (List<Vault.Login>) r[0];
            @SuppressWarnings("unchecked") List<String> nevers = (List<String>) r[1];
            ui.section("Пароли");
            if (logins.isEmpty()) ui.note("Сохранённых паролей нет");
            for (Vault.Login l : logins) {
                ui.row(l.host(), l.username.isEmpty() ? "Без имени пользователя" : l.username, null,
                        () -> Auth.require(a, "Показать пароль", () -> ui.push(l.host(), () -> buildLogin(l)), () -> { }));
            }
            if (!nevers.isEmpty()) {
                ui.section("Никогда не сохранять");
                for (String h : nevers) {
                    ui.row(h, "Нажмите, чтобы снова предлагать сохранение", null, () -> {
                        vault.run(() -> vault.removeNever(h));
                        reload();
                    });
                }
            }
            ui.section("Перенос");
            ui.row("Импортировать пароли", "Из CSV-файла Chrome, Cromite или Firefox", null, this::importCsv);
            ui.row("Экспортировать пароли", "В CSV-файл. Пароли в нём не зашифрованы", null,
                    () -> Auth.require(a, "Экспорт паролей", this::exportCsv, () -> { }));
        });
    }

    private void reload() {
        ui.refresh();
    }

    private void buildLogin(Vault.Login l) {
        int gen = ui.generation;
        vault.read(() -> vault.password(l.guid), pass -> {
            if (gen != ui.generation) return;
            String p = pass == null ? "" : pass;
            boolean[] shown = {false};
            ui.row("Сайт", l.origin, null, null);
            ui.row("Имя пользователя", l.username.isEmpty() ? "Нет" : l.username, null,
                    () -> copy(l.username, false));
            LinearLayout row = ui.row("Пароль", "••••••••", null, null);
            row.setOnClickListener(v -> {
                shown[0] = !shown[0];
                ((android.widget.TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1))
                        .setText(shown[0] ? p : "••••••••");
            });
            ui.note("Нажмите на пароль, чтобы показать или скрыть его");
            ui.row("Копировать пароль", null, null, () -> copy(p, true));
            ui.row("Изменить", null, null, () -> editLogin(l, p));
            ui.row("Удалить", null, null, () -> ui.confirm("Удалить пароль для " + l.host() + "?", () -> {
                vault.run(() -> vault.deleteLogin(l.guid));
                ui.back();
            }));
        });
    }

    private void editLogin(Vault.Login l, String password) {
        LinearLayout box = form();
        EditText user = field(box, "Имя пользователя", l.username, InputType.TYPE_CLASS_TEXT);
        EditText pass = field(box, "Пароль", password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        new Sheet(a)
                .setTitle("Изменить пароль")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String u = user.getText().toString();
                    String p = pass.getText().toString();
                    if (p.isEmpty()) return;
                    vault.run(() -> vault.editLogin(l.guid, u, p));
                    reload();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void importCsv() {
        files.openFile("text/*", uri -> vault.read(() -> {
            try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                return vault.importCsv(in);
            } catch (Exception e) {
                android.util.Log.w("Antite", "import failed", e);
                return -1;
            }
        }, n -> {
            Toast.makeText(a, n < 0 ? "Не удалось прочитать файл" : "Импортировано паролей: " + n,
                    Toast.LENGTH_LONG).show();
            ui.refresh();
        }));
    }

    private void exportCsv() {
        files.createFile("Antite Passwords.csv", "text/csv", uri -> vault.read(() -> {
            try (OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                return vault.exportCsv(out);
            } catch (Exception e) {
                android.util.Log.w("Antite", "export failed", e);
                return -1;
            }
        }, n -> Toast.makeText(a, n < 0 ? "Не удалось записать файл" : "Экспортировано паролей: " + n,
                Toast.LENGTH_LONG).show()));
    }

    void buildCards() {
        ui.toggle("Сохранять и заполнять способы оплаты", "Номер карты хранится в зашифрованном виде, CVC не сохраняется",
                "cardsEnabled", true, true);
        int gen = ui.generation;
        vault.read(vault::cards, cards -> {
            if (gen != ui.generation) return;
            ui.section("Карты");
            if (cards.isEmpty()) ui.note("Сохранённых карт нет");
            for (Vault.Card c : cards) {
                ui.row(c.label(), c.name, null, () -> new Sheet(a)
                        .setTitle(c.label())
                        .setItems(new String[]{"Изменить", "Удалить"}, (d, w) -> {
                            if (w == 0) {
                                Auth.require(a, "Изменить карту", () -> editCard(c.guid), () -> { });
                            } else {
                                ui.confirm("Удалить карту " + c.label() + "?", () -> {
                                    vault.run(() -> vault.deleteCard(c.guid));
                                    reload();
                                });
                            }
                        })
                        .show());
            }
            ui.row("Добавить карту", null, null, () -> cardForm(null));
        });
    }

    private void editCard(String guid) {
        vault.read(vault::fullCards, all -> {
            for (Autocomplete.CreditCard c : all) {
                if (c.guid.equals(guid)) cardForm(c);
            }
        });
    }

    private void cardForm(Autocomplete.CreditCard card) {
        LinearLayout box = form();
        EditText number = field(box, "Номер карты", card == null ? "" : card.number, InputType.TYPE_CLASS_NUMBER);
        EditText name = field(box, "Имя на карте", card == null ? "" : card.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText month = field(box, "Месяц (ММ)", card == null ? "" : card.expirationMonth, InputType.TYPE_CLASS_NUMBER);
        EditText year = field(box, "Год (ГГГГ)", card == null ? "" : card.expirationYear, InputType.TYPE_CLASS_NUMBER);
        new Sheet(a)
                .setTitle(card == null ? "Новая карта" : "Изменить карту")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String digits = number.getText().toString().replaceAll("\\D", "");
                    if (digits.length() < 12) {
                        Toast.makeText(a, "Неверный номер карты", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Autocomplete.CreditCard c = new Autocomplete.CreditCard.Builder()
                            .guid(card == null ? null : card.guid)
                            .number(digits)
                            .name(name.getText().toString().trim())
                            .expirationMonth(month.getText().toString().trim())
                            .expirationYear(year.getText().toString().trim())
                            .build();
                    vault.run(() -> vault.saveCard(c));
                    reload();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    void buildAddresses() {
        ui.toggle("Сохранять и заполнять адреса", "Имя, адрес, телефон и почта для форм на сайтах",
                "addressesEnabled", true, true);
        int gen = ui.generation;
        vault.read(vault::addresses, list -> {
            if (gen != ui.generation) return;
            ui.section("Адреса");
            if (list.isEmpty()) ui.note("Сохранённых адресов нет");
            for (Autocomplete.Address ad : list) {
                ui.row(AutofillPrompts.addressLine(ad), null, null, () -> new Sheet(a)
                        .setTitle(AutofillPrompts.addressLine(ad))
                        .setItems(new String[]{"Изменить", "Удалить"}, (d, w) -> {
                            if (w == 0) {
                                addressForm(ad);
                            } else {
                                ui.confirm("Удалить адрес?", () -> {
                                    vault.run(() -> vault.deleteAddress(ad.guid));
                                    reload();
                                });
                            }
                        })
                        .show());
            }
            ui.row("Добавить адрес", null, null, () -> addressForm(null));
        });
    }

    private void addressForm(Autocomplete.Address ad) {
        LinearLayout box = form();
        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
        EditText name = field(box, "Имя и фамилия", ad == null ? "" : ad.name, text);
        EditText org = field(box, "Организация", ad == null ? "" : ad.organization, text);
        EditText street = field(box, "Улица, дом, квартира", ad == null ? "" : ad.streetAddress, text);
        EditText city = field(box, "Город", ad == null ? "" : ad.addressLevel2, text);
        EditText region = field(box, "Регион", ad == null ? "" : ad.addressLevel1, text);
        EditText zip = field(box, "Индекс", ad == null ? "" : ad.postalCode, InputType.TYPE_CLASS_NUMBER);
        EditText country = field(box, "Страна (код, например RU)", ad == null ? "" : ad.country,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText tel = field(box, "Телефон", ad == null ? "" : ad.tel, InputType.TYPE_CLASS_PHONE);
        EditText email = field(box, "Эл. почта", ad == null ? "" : ad.email,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        new Sheet(a)
                .setTitle(ad == null ? "Новый адрес" : "Изменить адрес")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Autocomplete.Address out = new Autocomplete.Address.Builder()
                            .guid(ad == null ? null : ad.guid)
                            .name(val(name))
                            .organization(val(org))
                            .streetAddress(val(street))
                            .addressLevel2(val(city))
                            .addressLevel1(val(region))
                            .postalCode(val(zip))
                            .country(val(country))
                            .tel(val(tel))
                            .email(val(email))
                            .build();
                    vault.run(() -> vault.saveAddress(out));
                    reload();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static String val(EditText e) {
        return e.getText().toString().trim();
    }

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), 0);
        return box;
    }

    private EditText field(LinearLayout box, String hint, String value, int type) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setInputType(type);
        e.setText(value == null ? "" : value);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private void copy(String text, boolean sensitive) {
        if (text == null || text.isEmpty()) return;
        ClipData clip = ClipData.newPlainText(sensitive ? "password" : "username", text);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        a.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(a, "Скопировано", Toast.LENGTH_SHORT).show();
    }
}
