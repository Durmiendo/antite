package dev.antite.browser;

import android.graphics.Bitmap;
import android.widget.EditText;

import java.util.List;

final class Library {
    interface Host {
        void openListPage(ListPage.Source src);

        void openFromList(String url, boolean newTab);

        void restoreClosed(Db.Entry e);

        void copy(String text);
    }

    private final MainActivity a;
    private final Host host;
    private final Db db;
    private final IconCache icons;
    private final Downloads downloads;

    Library(MainActivity a, Host host, Db db, IconCache icons, Downloads downloads) {
        this.a = a;
        this.host = host;
        this.db = db;
        this.icons = icons;
        this.downloads = downloads;
    }

    private abstract class Page implements ListPage.Source {
        public Bitmap image(Db.Entry e) {
            return icons.get(Urls.host(e.url));
        }

        public void open(Db.Entry e) {
            host.openFromList(e.url, false);
        }

        public String clearLabel() {
            return "Очистить";
        }

        public void clearAll(Runnable refresh) {
        }

        void change(Runnable write, Runnable refresh) {
            db.run(write);
            refresh.run();
        }
    }

    void showHistory() {
        host.openListPage(new Page() {
            public String title() {
                return "История";
            }

            public int icon() {
                return R.drawable.ic_history;
            }

            public String emptyText() {
                return "История пуста";
            }

            public List<Db.Entry> load(String filter) {
                return db.history(filter, 2000);
            }

            public String subtitle(Db.Entry e) {
                return Urls.host(e.url) + " · " + ListPage.ago(e.time);
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                actions(e, new String[]{"Открыть в новой вкладке", "Копировать ссылку", "Удалить из истории"},
                        () -> host.openFromList(e.url, true), () -> host.copy(e.url),
                        () -> change(() -> db.deleteHistory(e.id), refresh));
            }

            public void clearAll(Runnable refresh) {
                confirm("Удалить всю историю просмотров?", "Удалить", () -> change(db::clearHistory, refresh));
            }
        });
    }

    void showBookmarks() {
        host.openListPage(new Page() {
            public String title() {
                return "Закладки";
            }

            public int icon() {
                return R.drawable.ic_star;
            }

            public String emptyText() {
                return "Закладок пока нет. Добавить можно звездой в меню.";
            }

            public List<Db.Entry> load(String filter) {
                return db.bookmarks(filter);
            }

            public String subtitle(Db.Entry e) {
                return e.url;
            }

            public String clearLabel() {
                return null;
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                actions(e, new String[]{"Открыть в новой вкладке", "Переименовать", "Копировать ссылку", "Удалить"},
                        () -> host.openFromList(e.url, true), () -> rename(e, refresh), () -> host.copy(e.url),
                        () -> change(() -> db.deleteBookmark(e.id), refresh));
            }

            private void rename(Db.Entry e, Runnable refresh) {
                EditText input = new EditText(a);
                input.setSingleLine(true);
                input.setText(e.title);
                new Sheet(a)
                        .setTitle("Название закладки")
                        .setView(input)
                        .setPositiveButton("Сохранить", (d, w) -> {
                            String name = input.getText().toString().trim();
                            change(() -> db.renameBookmark(e.id, name), refresh);
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            }
        });
    }

    void showClosedTabs() {
        host.openListPage(new Page() {
            public String title() {
                return "Недавние вкладки";
            }

            public int icon() {
                return R.drawable.ic_tab;
            }

            public String emptyText() {
                return "Недавно закрытых вкладок нет";
            }

            public List<Db.Entry> load(String filter) {
                return db.closed(filter);
            }

            public String subtitle(Db.Entry e) {
                return Urls.host(e.url) + " · закрыта " + ListPage.ago(e.time);
            }

            public void open(Db.Entry e) {
                host.restoreClosed(e);
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                actions(e, new String[]{"Восстановить", "Копировать ссылку", "Удалить из списка"},
                        () -> host.restoreClosed(e), () -> host.copy(e.url),
                        () -> change(() -> db.deleteClosed(e.id), refresh));
            }

            public void clearAll(Runnable refresh) {
                change(db::clearClosed, refresh);
            }
        });
    }

    void showDownloads() {
        host.openListPage(new Page() {
            public String title() {
                return "Скачанные файлы";
            }

            public int icon() {
                return R.drawable.ic_download_done;
            }

            public String emptyText() {
                return "Загрузок пока нет";
            }

            public List<Db.Entry> load(String filter) {
                return db.downloads(filter);
            }

            public Bitmap image(Db.Entry e) {
                return null;
            }

            public String subtitle(Db.Entry e) {
                return Downloads.status(e) + " · " + ListPage.ago(e.time);
            }

            public void open(Db.Entry e) {
                downloads.open(e);
            }

            public void longPress(Db.Entry e, Runnable refresh) {
                actions(e, new String[]{"Открыть", "Поделиться", "Убрать из списка"},
                        () -> downloads.open(e), () -> downloads.share(e),
                        () -> change(() -> db.deleteDownload(e.id), refresh));
            }

            public void clearAll(Runnable refresh) {
                confirm("Очистить список загрузок? Сами файлы останутся в папке Download.", "Очистить",
                        () -> change(db::clearDownloads, refresh));
            }
        });
    }

    private void actions(Db.Entry e, String[] labels, Runnable... actions) {
        new Sheet(a)
                .setTitle(e.title == null || e.title.isEmpty() ? e.url : e.title)
                .setItems(labels, (d, w) -> actions[w].run())
                .show();
    }

    void confirm(String text, String button, Runnable action) {
        new Sheet(a)
                .setMessage(text)
                .setPositiveButton(button, (d, w) -> action.run())
                .setNegativeButton("Отмена", null)
                .show();
    }
}
