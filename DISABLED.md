# Switched off for now

Features that exist in the code or were in the interface, but are off until they work well enough. Each line says how
to bring it back.

- Page translation (menu item, offer bar, section in Languages): off since 2026-11-10, the results were poor. Back:
  `Browser.TRANSLATION_ENABLED`.
- Reader mode: removed from the menu on 2026-11-07.
- Tab groups: removed from the menu on 2026-11-07; tab categories took their place.
- Bookmarks (menu star, Bookmarks, Bookmark all tabs, address bar suggestions): off since 2026-11-17, not used.
  The saved ones stay in the database and in backups. Back: `Browser.BOOKMARKS_ENABLED`.
- Shell updates without the system confirmation: off since 2026-11-17, every update shows the system installer
  dialog. Back: `USER_ACTION_NOT_REQUIRED` in `ShellInstaller.install`.
