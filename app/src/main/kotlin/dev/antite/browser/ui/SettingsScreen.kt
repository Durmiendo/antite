package dev.antite.browser.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.antite.browser.Auth
import dev.antite.browser.Browser
import dev.antite.browser.BuildConfig
import dev.antite.browser.DialogButton
import dev.antite.browser.FormField
import dev.antite.browser.Overlay
import dev.antite.browser.UiDialog
import dev.antite.browser.Urls
import dev.antite.browser.data.Option
import dev.antite.browser.data.Options
import dev.antite.browser.data.SavedLogin
import dev.antite.browser.data.SitePerms
import dev.antite.browser.engine.EngineUpdates
import dev.antite.browser.engine.Engines
import dev.antite.browser.engine.EngineStore
import dev.antite.browser.engine.SourceException
import dev.antite.browser.engine.ShellInstaller
import dev.antite.browser.engine.Sources
import dev.antite.browser.transfer.BackupCipher
import dev.antite.browser.transfer.WrongPasswordException
import dev.antite.engine.Address
import dev.antite.engine.BackgroundService
import dev.antite.engine.Card
import dev.antite.engine.Language
import dev.antite.engine.LanguageModel
import dev.antite.engine.PermissionValue
import dev.antite.engine.SitePermission
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private class Page(val title: String, val content: @Composable (Nav) -> Unit)

class Nav internal constructor(private val stack: MutableList<Any>) {
    fun push(title: String, content: @Composable (Nav) -> Unit) {
        stack += Page(title, content)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun pop(onRoot: () -> Unit) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onRoot()
    }
}

@Composable
fun SettingsScreen(b: Browser) {
    val stack = remember(b.settingsOpenCount) {
        val site = b.settingsSite
        mutableStateListOf<Any>(
            if (site != null) Page(site) { SitePage(b, it, site) } else Page("Настройки") { MainPage(b, it) },
        )
    }
    val nav = remember(stack) { Nav(stack) }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val page = stack.last() as Page
    page.content(nav)
}

@Composable
private fun Screen(b: Browser, nav: Nav, title: String, content: LazyListScope.() -> Unit) {
    PageScaffold(title, { nav.pop { b.overlay = Overlay.NONE } }, content = content)
}

private fun <T> LazyListScope.choice(b: Browser, nav: Nav, title: String, key: String, options: List<Option<T>>, def: Int,
                                     engine: Boolean, after: () -> Unit = {}) = item(key = "choice:$key") {
    val cur = b.prefs.index(key, options, def)
    SettingRow(title, options[cur].label, onClick = {
        nav.push(title) { n -> RadioPage(b, n, title, options.map { it.label }, { b.prefs.index(key, options, def) }) { i ->
            b.prefs.edit { putInt(key, i) }
            if (engine) b.applyEngineSettings()
            after()
        } }
    })
}

private fun LazyListScope.toggle(b: Browser, title: String, summary: String?, key: String, def: Boolean, engine: Boolean) =
    item(key = "toggle:$key") {
        SwitchRow(title, summary, b.prefs.bool(key, def)) { v ->
            b.prefs.edit { putBoolean(key, v) }
            if (engine) b.applyEngineSettings()
        }
    }

private fun LazyListScope.nav(nav: Nav, title: String, summary: String?, content: @Composable (Nav) -> Unit) =
    item(key = "nav:$title") { SettingRow(title, summary, onClick = { nav.push(title, content) }) }

@Composable
private fun RadioPage(b: Browser, nav: Nav, title: String, options: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
    Screen(b, nav, title) {
        options.forEachIndexed { i, label ->
            item { RadioRow(label, current() == i) { onPick(i) } }
        }
    }
}

@Composable
private fun MainPage(b: Browser, nav: Nav) {
    val p = b.prefs
    Screen(b, nav, "Настройки") {
        section("Основные")
        choice(b, nav, "Поисковая система", "engine", Options.engines, 0, false)
        nav(nav, "Менеджер паролей", "Сохранённые пароли, импорт и экспорт") { PasswordsPage(b, it) }
        nav(nav, "Способы оплаты", "Банковские карты") { CardsPage(b, it) }
        nav(nav, "Адреса и другие данные", "Имя, адрес, телефон, почта") { AddressesPage(b, it) }
        nav(nav, "Конфиденциальность и безопасность", "HTTPS, DNS, cookie, отслеживание, WebRTC") { PrivacyPage(b, it) }
        item {
            SettingRow("Уведомления", "Системные настройки уведомлений браузера", onClick = {
                b.activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, b.activity.packageName))
            })
        }
        choice(b, nav, "Тема", "theme", Options.themes, 2, true)
        item {
            val isDefault = b.activity.isDefaultBrowser()
            SettingRow("Браузер по умолчанию", if (isDefault) "Antite" else "Другой браузер. Нажмите, чтобы выбрать Antite", onClick = {
                if (isDefault) b.activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                else b.makeDefaultBrowser()
            })
        }
        section("Дополнительные")
        nav(nav, "Главная страница", if (p.bool("homeButton", false)) homeSummary(b) else "Выключено") { HomePage(b, it) }
        nav(nav, "Вкладки", "Вид списка, запуск, ссылки из приложений, инкогнито") { TabsPage(b, it) }
        nav(nav, "Специальные возможности", "Размер текста, масштабирование") { AccessibilityPage(b, it) }
        nav(nav, "Настройки сайтов", "Разрешения, JavaScript, всплывающие окна") { SiteSettingsPage(b, it) }
        nav(nav, "Языки", "Языки сайтов и перевод") { LanguagesPage(b, it) }
        nav(nav, "Скачанные файлы", if (p.bool("dlAsk", false)) "Спрашивать, куда сохранять" else "Сохранять в «Загрузки»") {
            Screen(b, it, "Скачанные файлы") {
                toggle(b, "Спрашивать, куда сохранять файлы", "Иначе файлы сохраняются в папку «Загрузки»", "dlAsk", false, false)
            }
        }
        item { SettingRow("uBlock Origin", "Панель управления, списки фильтров", onClick = b::openUboDashboard) }
        nav(nav, "Перенос данных", "Экспорт и импорт всех данных браузера") { TransferPage(b, it) }
        section("О программе")
        nav(nav, "Обновления", "Движок браузера и Antite, " + updatesSummary(b)) { UpdatesPage(b, it) }
        item { SettingRow("Antite 0.1", "Движок ${b.engine.info.type} ${b.engine.info.version}") }
    }
}

@Composable
private fun TransferPage(b: Browser, nav: Nav) = Screen(b, nav, "Перенос данных") {
    item {
        SettingRow("Экспортировать данные", "Вкладки, история, закладки, пароли, входы на сайты, настройки. Файл шифруется паролем", onClick = {
            Auth.require(b.activity, "Экспорт данных", { exportAll(b) }, {})
        })
    }
    item { SettingRow("Импортировать данные", "Все текущие данные браузера будут заменены данными из файла", onClick = { importAll(b) }) }
    note("Браузер перезапустится. Кэш сайтов не переносится")
}

private fun exportAll(b: Browser) {
    val name = "antite-" + java.time.LocalDate.now() + ".antite"
    b.activity.createDocument(name, "application/octet-stream") { uri ->
        if (uri == null) return@createDocument
        var started = false
        b.show(UiDialog.Form("Пароль на файл", "Без него файл не открыть. Восстановить пароль нельзя", listOf(
            FormField("Пароль", kind = FormField.Kind.PASSWORD),
            FormField("Повторите пароль", kind = FormField.Kind.PASSWORD),
        ), "Экспортировать", { v ->
            when {
                v[0].isEmpty() -> b.toast("Введите пароль").let { false }
                v[0] != v[1] -> b.toast("Пароли не совпадают").let { false }
                else -> {
                    started = true
                    b.startTransfer(true, uri, v[0].toCharArray())
                    true
                }
            }
        }, onDismiss = {
            if (!started) runCatching { DocumentsContract.deleteDocument(b.activity.contentResolver, uri) }
        }))
    }
}

private fun importAll(b: Browser) = b.activity.openDocument(listOf("application/octet-stream")) { uri ->
    if (uri == null) return@openDocument
    b.show(UiDialog.Form("Пароль от файла", null, listOf(FormField("Пароль", kind = FormField.Kind.PASSWORD)), "Далее", { v ->
        val password = v[0].toCharArray()
        b.activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) {
                try {
                    b.activity.contentResolver.openInputStream(uri)!!.use { BackupCipher.decrypt(it, password).read() }
                    null
                } catch (e: WrongPasswordException) {
                    "Неверный пароль"
                } catch (e: Exception) {
                    "Это не файл переноса данных Antite"
                }
            }
            if (error != null) {
                password.fill(' ')
                return@launch b.toast(error)
            }
            b.show(UiDialog.Message("Заменить все данные?",
                "Вкладки, история, закладки, пароли и входы на сайты будут заменены данными из файла. Браузер перезапустится.",
                buttons = listOf(DialogButton("Отмена") { password.fill(' ') },
                    DialogButton("Импортировать") { b.startTransfer(false, uri, password) }),
                onDismiss = { password.fill(' ') }))
        }
        true
    }))
}

private fun homeSummary(b: Browser) =
    if (b.prefs.int("homeMode", 0) == 0) "Новая вкладка" else b.prefs.string("homeUrl", "")

@Composable
private fun PrivacyPage(b: Browser, nav: Nav) {
    val doh = b.prefs.index("doh", Options.doh, 0)
    Screen(b, nav, "Конфиденциальность и безопасность") {
        item { SettingRow("Удалить данные о работе в браузере", "Кэш, cookie, данные сайтов, история", onClick = b::clearDataDialog) }
        section("Безопасность")
        toggle(b, "Всегда использовать безопасные подключения",
            "Открывать сайты по HTTPS и предупреждать, если сайт его не поддерживает", "httpsOnly", false, true)
        b.engine.backgroundServices.firstOrNull { it.id == BackgroundService.SAFE_BROWSING }?.let { sb ->
            toggle(b, "Безопасный просмотр (Google Safe Browsing)",
                "Предупреждать об опасных сайтах и загрузках. Браузер скачивает списки угроз Google",
                b.prefs.serviceKey(sb.id), sb.defaultEnabled, true)
        }
        nav(nav, "Безопасный DNS", Options.doh[doh].label) { DnsPage(b, it) }
        section("Конфиденциальность")
        choice(b, nav, "Защита от отслеживания", "tracking", Options.tracking, 0, true)
        choice(b, nav, "Сторонние файлы cookie", "cookies", Options.cookies, 0, true)
        choice(b, nav, "Защита от цифровых отпечатков", "fingerprint", Options.fingerprint, 0, true)
        if (b.prefs.int("fingerprint", 0) == 2) {
            note("Максимальная защита подменяет часовой пояс на UTC, язык на английский и тему на светлую, некоторые сайты ломаются")
        }
        choice(b, nav, "WebRTC", "webrtc", Options.webRtc, 1, true)
        toggle(b, "Отправлять запрет отслеживания (Do Not Track)", null, "dnt", false, true)
        toggle(b, "Global Privacy Control", "Просить сайты не продавать и не передавать ваши данные", "gpc", true, true)
        toggle(b, "Очищать ссылки от меток отслеживания", "Удалять из адресов параметры вроде utm и fbclid", "strip", true, true)
        toggle(b, "Предзагрузка страниц", "Заранее соединяться с сайтами и загружать ссылки, которые вы можете открыть",
            "preload", false, true)
        toggle(b, "Подсказки поисковой системы", "Набираемый в адресной строке текст отправляется поисковику",
            "searchSuggest", true, false)
        nav(nav, "Что отправляет браузер", "Фоновые запросы: что и куда, выключатели") { NetworkPage(b, it) }
    }
}

private fun serviceSummary(s: BackgroundService, on: Boolean) = buildString {
    append(s.summary).append("\nКуда: ").append(s.hosts.joinToString(", "))
    if (!on && s.offEffect != null) append("\nВыключено: ").append(s.offEffect)
}

@Composable
private fun NetworkPage(b: Browser, nav: Nav) = Screen(b, nav, "Что отправляет браузер") {
    note("Запросы, которые браузер делает сам. Открытые вами страницы и их собственные запросы сюда не входят")
    section("Antite")
    nav(nav, "Обновления", updatesSummary(b).replaceFirstChar { it.uppercase() } + ": список движков\nКуда: " +
        Sources.updateSource(b.activity).let { Uri.parse(it.url).host ?: it.url }) {
        UpdatesPage(b, it)
    }
    toggle(b, "Подсказки поисковой системы",
        "Набираемый в адресной строке текст\nКуда: ${Uri.parse(b.prefs.suggestUrl).host}", "searchSuggest", true, false)
    toggle(b, "Значки сайтов", "Значок открытого сайта, раз в 7 дней\nКуда: сам сайт", "siteIcons", true, false)
    item(key = "dns") {
        val doh = Options.doh[b.prefs.index("doh", Options.doh, 0)].value
        SettingRow("Безопасный DNS",
            if (doh == null) "Выключен: адреса сайтов узнаются через системный DNS" else "Все DNS-запросы\nКуда: ${Uri.parse(doh).host}",
            onClick = { nav.push("Безопасный DNS") { DnsPage(b, it) } })
    }
    section("Движок ${b.engine.info.type}")
    b.engine.backgroundServices.forEach { s ->
        item(key = "bg:${s.id}") {
            val on = b.prefs.serviceEnabled(s)
            SwitchRow(s.title, serviceSummary(s, on), on) { v ->
                b.prefs.edit { putBoolean(b.prefs.serviceKey(s.id), v) }
                b.applyEngineSettings()
            }
        }
    }
    section("Расширения")
    item {
        SettingRow("uBlock Origin",
            "При первом запуске: установка с addons.mozilla.org\nСписки фильтров: адрес каждого списка " +
                "(ublockorigin.github.io, ublockorigin.pages.dev и другие). Настраивается в панели uBlock",
            onClick = b::openUboDashboard)
    }
    section("Не отправляется")
    note("Телеметрия, отчёты о сбоях, синхронизация и аккаунты Mozilla: в Antite их нет")
}

private class UpdateInfo(
    val active: String?, val activeFrom: String?,
    val pending: String?, val pendingFrom: String?,
    val previous: String?, val previousFrom: String?,
    val lastCheck: Long, val lastError: String?, val available: String?, val shell: Int,
)

private fun from(origin: String?) = if (origin == null) "" else ", источник «$origin»"

@Composable
private fun UpdatesPage(b: Browser, nav: Nav) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var shellBusy by remember { mutableStateOf<String?>(null) }
    val info by produceState<UpdateInfo?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            val store = Engines.store(ctx)
            val st = EngineUpdates.state(ctx)
            val a = store.active()
            val p = store.pending()
            val prev = store.previous()
            UpdateInfo(
                a?.let(store::version), a?.let { EngineUpdates.origin(ctx, it) },
                p?.let(store::version), p?.let { EngineUpdates.origin(ctx, it) },
                prev?.let(store::version), prev?.let { EngineUpdates.origin(ctx, it) },
                st.getLong("lastCheck", 0), st.getString("lastError", null), st.getString("available", null),
                st.getInt("shellAvailable", 0),
            )
        }
    }
    fun checkNow() = ctx.lifecycleScope.launch {
        busy = "Проверяем…"
        val r = withContext(Dispatchers.IO) { runCatching { EngineUpdates.check(ctx) } }
        busy = null
        revision++
        val offer = r.getOrNull()?.engine
        when {
            r.isFailure -> b.toast("Не удалось проверить обновления")
            offer == null -> if (r.getOrNull()?.shell == null) b.toast("Обновлений нет")
            else -> b.confirm("Доступен Gecko ${offer.entry.version} из источника «${offer.source.name}», " +
                "${(offer.size + 512 * 1024) / (1024 * 1024)} МБ. Скачать?", "Скачать") {
                busy = "Скачиваем…"
                b.engineNews.install(offer, { busy = "Скачиваем… $it%" }) {
                    busy = null
                    revision++
                }
            }
        }
    }
    fun rollBack(i: UpdateInfo) = b.confirm("Вернуть Gecko ${i.previous}? Gecko ${i.active} больше не будет предлагаться. " +
        "Браузер перезапустится.", "Вернуть") {
        ctx.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val store = Engines.store(ctx)
                store.active()?.let(store::reject)
                store.previous()?.let(store::setPending)
            }
            b.engineNews.restart()
        }
    }
    Screen(b, nav, "Обновления") {
        val i = info ?: return@Screen
        if (i.pending != null) {
            item(key = "restart") {
                SettingRow("Перезапустить браузер", "Скачан Gecko ${i.pending}, начнёт работать после перезапуска",
                    onClick = b.engineNews::restart)
            }
        }
        if (i.shell > BuildConfig.VERSION_CODE) {
            item(key = "shell") {
                SettingRow("Обновить Antite", shellBusy ?: "Доступна сборка ${i.shell}. Система попросит подтвердить установку",
                    onClick = {
                        if (shellBusy == null) {
                            shellBusy = "Скачиваем…"
                            b.engineNews.updateShell({ shellBusy = "Скачиваем… $it%" }) { shellBusy = null }
                        }
                    })
            }
        }
        item(key = "check") {
            val last = when {
                i.lastCheck == 0L -> "Ещё не проверялось"
                else -> "Последняя проверка: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT).format(java.util.Date(i.lastCheck)) + when {
                    i.available != null -> ", доступен Gecko ${i.available}"
                    i.shell > BuildConfig.VERSION_CODE -> ", доступна новая версия Antite"
                    i.lastError != null -> ", ошибка: ${i.lastError}"
                    else -> ", обновлений нет"
                }
            }
            SettingRow("Проверить сейчас", busy ?: last, onClick = { if (busy == null) checkNow() })
        }
        section("Как обновлять")
        item(key = "auto") {
            SwitchRow("Проверять автоматически", null, b.prefs.bool(EngineUpdates.AUTO, true)) { v ->
                b.prefs.edit { putBoolean(EngineUpdates.AUTO, v) }
                EngineUpdates.schedule(ctx)
            }
        }
        if (b.prefs.bool(EngineUpdates.AUTO, true)) {
            choice(b, nav, "Как часто", EngineUpdates.INTERVAL, Options.updateInterval, EngineUpdates.INTERVAL_DEFAULT, false) {
                EngineUpdates.schedule(ctx)
            }
        }
        choice(b, nav, "Установка", EngineUpdates.INSTALL, Options.updateInstall, EngineUpdates.INSTALL_ASK, false)
        if (b.prefs.int(EngineUpdates.INSTALL, EngineUpdates.INSTALL_ASK) == EngineUpdates.INSTALL_AUTO) {
            toggle(b, "Скачивать только по Wi-Fi", "Около 95 МБ за обновление", EngineUpdates.WIFI_ONLY, true, false)
        }
        item(key = "updateSource") {
            SettingRow("Источник обновлений", remember(revision) { Sources.updateSource(ctx).name }, onClick = {
                nav.push("Источник обновлений") { n -> UpdateSourcePage(b, n) { revision++ } }
            })
        }
        nav(nav, "Источники", Sources.enabled(ctx).joinToString(", ") { it.name }) { SourcesPage(b, it) }
        section("Установлено")
        item(key = "shellVersion") { SettingRow("Antite ${BuildConfig.VERSION_NAME}", "Сборка ${BuildConfig.VERSION_CODE}") }
        item(key = "active") { SettingRow("${b.engine.info.type} ${i.active ?: b.engine.info.version}", "Работает" + from(i.activeFrom)) }
        if (i.pending != null) item(key = "pending") { SettingRow("gecko ${i.pending}", "После перезапуска" + from(i.pendingFrom)) }
        if (i.previous != null) {
            item(key = "previous") {
                SettingRow("gecko ${i.previous}", "Предыдущий" + from(i.previousFrom) + ". Нажмите, чтобы вернуть его",
                    onClick = { rollBack(i) })
            }
        }
        note("Если новый движок ${EngineStore.MAX_FAILED_STARTS} раза подряд не запустится, браузер сам вернёт предыдущий")
        section("Отладка")
        item(key = "debug") { DebugInstall(b) { revision++ } }
    }
}

@Composable
private fun DebugInstall(b: Browser, changed: () -> Unit) {
    val ctx = b.activity
    // Kept in the browser prefs, so the row follows it even when the unlock prompt recreates this item.
    val on = b.prefs.bool(EngineUpdates.DEBUG, false)
    var busy by remember { mutableStateOf<String?>(null) }
    SwitchRow("Режим отладки", "Установка движка и Antite с любого адреса, в том числе http, без проверки подписи", on) { v ->
        if (!v) {
            b.prefs.edit { putBoolean(EngineUpdates.DEBUG, false) }
        } else {
            b.confirm("Без проверки подписи поставить можно любой код. Он будет работать внутри браузера с доступом ко " +
                "всему, включая пароли. Включайте только для своих сборок.", "Включить") {
                Auth.require(ctx, "Включить режим отладки", { b.prefs.edit { putBoolean(EngineUpdates.DEBUG, true) } }, {})
            }
        }
    }
    if (!on) return
    fun install(updates: dev.antite.browser.engine.Updates) {
        val choices = listOfNotNull(
            updates.engine?.let { o ->
                DialogButton("Движок Gecko ${o.entry.version}, ${(o.size + 512 * 1024) / (1024 * 1024)} МБ") {
                    busy = "Скачиваем движок…"
                    b.engineNews.install(o, { busy = "Скачиваем движок… $it%" }) {
                        busy = null
                        changed()
                    }
                }
            },
            updates.shell?.let { s ->
                DialogButton("Antite, сборка ${s.version}") {
                    busy = "Скачиваем Antite…"
                    b.engineNews.installShell(s, { busy = "Скачиваем Antite… $it%" }) { busy = null }
                }
            },
        )
        if (choices.isEmpty()) b.toast("По этому адресу нет ни движка для этой версии, ни сборки Antite")
        else b.show(UiDialog.Items("Установить", choices))
    }
    SettingRow("Установить с адреса", busy ?: "Адрес engines.json, например http://192.168.1.5:8790/engines.json", onClick = {
        if (busy != null) return@SettingRow
        b.show(UiDialog.Form("Установить с адреса", null, listOf(
            FormField("Адрес engines.json", b.prefs.string("debugUrl", ""), kind = FormField.Kind.URL),
        ), "Проверить", { v ->
            val url = v[0].trim()
            b.prefs.edit { putString("debugUrl", url) }
            busy = "Проверяем…"
            ctx.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { EngineUpdates.fromAddress(ctx, url) } }
                busy = null
                r.onSuccess(::install).onFailure { b.toast("Не удалось: ${it.message ?: it.javaClass.simpleName}") }
            }
            true
        }))
    })
}

private fun updatesSummary(b: Browser): String =
    if (!b.prefs.bool(EngineUpdates.AUTO, true)) "проверка выключена"
    else "проверка " + Options.updateInterval[b.prefs.index(EngineUpdates.INTERVAL, Options.updateInterval,
        EngineUpdates.INTERVAL_DEFAULT)].label.lowercase()

@Composable
private fun UpdateSourcePage(b: Browser, nav: Nav, changed: () -> Unit) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    val sources = remember(revision) { Sources.enabled(ctx) }
    val current = remember(revision) { Sources.updateSource(ctx).id }
    RadioPage(b, nav, "Источник обновлений", sources.map { it.name }, { sources.indexOfFirst { it.id == current } }) { i ->
        val s = sources[i]
        if (s.id == current) return@RadioPage
        b.confirm("Обновления движка и Antite будут приходить только из «${s.name}».\n\n" +
            "Отпечаток ключа SHA-256:\n${s.fingerprint}", "Сменить") {
            try {
                Sources.setUpdateSource(ctx, s.id)
                revision++
                changed()
            } catch (e: SourceException) {
                b.toast(e.message ?: "")
            }
        }
    }
}

@Composable
private fun SourcesPage(b: Browser, nav: Nav) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    val list = remember(revision) { Sources.list(ctx) }
    fun addSource() = b.show(UiDialog.Form("Добавить источник", "Источник сможет устанавливать код, который выполняется в браузере",
        listOf(
            FormField("Название", kind = FormField.Kind.TEXT),
            FormField("Адрес индекса (https://…/engines.json)", kind = FormField.Kind.URL),
            FormField("Открытый ключ издателя", kind = FormField.Kind.URL),
        ), "Далее", { v ->
            val key = try {
                Sources.parseKey(v[2])
            } catch (e: SourceException) {
                b.toast(e.message ?: "")
                return@Form false
            }
            b.confirm("Отпечаток ключа SHA-256:\n${Sources.fingerprint(key)}\n\nСверьте его с отпечатком, который опубликовал " +
                "издатель. Добавлять источник?", "Добавить") {
                try {
                    Sources.add(ctx, v[0].trim(), v[1].trim(), key)
                    revision++
                } catch (e: SourceException) {
                    b.toast(e.message ?: "")
                }
            }
            true
        }))
    Screen(b, nav, "Источники") {
        note("Источник - адрес списка движков и ключ его издателя. Движок ставится, только если список подписан этим ключом. " +
            "Обновления приходят только из источника обновлений, остальные источники можно проверить вручную на их странице")
        list.forEach { s ->
            item(key = "src:${s.id}") {
                SettingRow(s.name, (if (s.enabled) "" else "Выключен\n") + s.url, onClick = {
                    nav.push(s.name) { n -> SourcePage(b, n, s.id) { revision++ } }
                })
            }
        }
        item(key = "add") { SettingRow("Добавить источник", onClick = ::addSource) }
    }
}

@Composable
private fun SourcePage(b: Browser, nav: Nav, id: String, changed: () -> Unit) {
    val ctx = b.activity
    var revision by remember { mutableIntStateOf(0) }
    val s = remember(revision) { Sources.find(ctx, id) } ?: return
    val isUpdateSource = remember(revision) { Sources.updateSource(ctx).id == id }
    var busy by remember { mutableStateOf<String?>(null) }
    fun checkThis() = ctx.lifecycleScope.launch {
        busy = "Проверяем…"
        val (found, offer) = withContext(Dispatchers.IO) {
            EngineUpdates.fromSource(ctx, s).let { it to EngineUpdates.newerThanInstalled(ctx, it.offer) }
        }
        busy = null
        when {
            found.errors.isNotEmpty() -> b.toast("Не удалось проверить: ${found.errors.joinToString()}")
            offer == null -> b.toast(found.offer?.let { "Gecko ${it.entry.version} из этого источника не новее установленного" }
                ?: "Подходящего движка в источнике нет")
            else -> b.confirm("В источнике «${s.name}» есть Gecko ${offer.entry.version}, " +
                "${(offer.size + 512 * 1024) / (1024 * 1024)} МБ. Это не источник обновлений: следующие обновления придут из " +
                "«${Sources.updateSource(ctx).name}». Скачать?", "Скачать") {
                busy = "Скачиваем…"
                b.engineNews.install(offer, { busy = "Скачиваем… $it%" }) { busy = null }
            }
        }
    }
    Screen(b, nav, s.name) {
        item(key = "enabled") {
            val summary = listOfNotNull(if (s.builtin) "Встроенный источник Antite" else null,
                if (isUpdateSource) "Источник обновлений" else null).joinToString(". ").ifEmpty { null }
            SwitchRow("Использовать", summary, s.enabled) { v ->
                try {
                    Sources.setEnabled(ctx, id, v)
                    revision++
                    changed()
                } catch (e: SourceException) {
                    b.toast(e.message ?: "")
                }
            }
        }
        if (!isUpdateSource) {
            item(key = "check") {
                SettingRow("Проверить этот источник", busy ?: "Обновления отсюда сами не приходят",
                    onClick = { if (busy == null) checkThis() })
            }
        }
        item(key = "url") { SettingRow("Адрес", s.url) }
        item(key = "key") { SettingRow("Отпечаток ключа SHA-256", s.fingerprint) }
        if (!s.builtin) {
            item(key = "remove") {
                SettingRow("Удалить источник", onClick = {
                    b.confirm("Удалить источник «${s.name}»? Уже установленные движки останутся.", "Удалить") {
                        try {
                            Sources.remove(ctx, id)
                            changed()
                            nav.back()
                        } catch (e: SourceException) {
                            b.toast(e.message ?: "")
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun DnsPage(b: Browser, nav: Nav) {
    val doh = b.prefs.index("doh", Options.doh, 0)
    Screen(b, nav, "Безопасный DNS") {
        item {
            SwitchRow("Использовать безопасный DNS", "DNS-запросы шифруются и уходят выбранному провайдеру", doh != 0) { on ->
                b.prefs.edit { putInt("doh", if (on) maxOf(1, b.prefs.int("dohLast", 1)) else 0) }
                b.applyEngineSettings()
            }
        }
        if (doh != 0) {
            section("Провайдер")
            Options.doh.drop(1).forEachIndexed { i, o ->
                item {
                    RadioRow(o.label, doh == i + 1) {
                        b.prefs.edit { putInt("doh", i + 1); putInt("dohLast", i + 1) }
                        b.applyEngineSettings()
                    }
                }
            }
            choice(b, nav, "Режим", "dohMode", Options.dohModes, 0, true)
        }
    }
}

@Composable
private fun HomePage(b: Browser, nav: Nav) {
    fun editUrl(then: () -> Unit = {}) = b.show(UiDialog.Form("Адрес главной страницы", null,
        listOf(FormField("https://example.com", b.prefs.string("homeUrl", ""), FormField.Kind.URL)),
        onConfirm = { v ->
            var url = v[0].trim()
            if (url.isEmpty()) return@Form false
            if ("://" !in url) url = "https://$url"
            b.prefs.edit { putString("homeUrl", url) }
            then()
            true
        }))
    Screen(b, nav, "Главная страница") {
        toggle(b, "Кнопка «Главная страница»", "Показывать кнопку слева от адресной строки", "homeButton", false, false)
        section("Открывать")
        Options.homeModes.forEachIndexed { i, o ->
            item {
                RadioRow(o.label, b.prefs.int("homeMode", 0) == i) {
                    if (i == 1 && b.prefs.string("homeUrl", "").isEmpty()) editUrl { b.prefs.edit { putInt("homeMode", 1) } }
                    else b.prefs.edit { putInt("homeMode", i) }
                }
            }
        }
        if (b.prefs.int("homeMode", 0) == 1) item { SettingRow("Адрес", b.prefs.string("homeUrl", ""), onClick = { editUrl() }) }
    }
}

@Composable
private fun TabsPage(b: Browser, nav: Nav) = Screen(b, nav, "Вкладки") {
    choice(b, nav, "Вид списка вкладок", "tabLayout", Options.tabLayouts, 0, false)
    choice(b, nav, "При запуске открывать", "startup", Options.startup, 0, false)
    choice(b, nav, "Ссылки из других приложений", "external", Options.external, 0, false)
    toggle(b, "Закрывать вкладки инкогнито при сворачивании", null, "closeIncognito", false, false)
    toggle(b, "Всегда открывать версию для ПК", "Для новых вкладок", "desktopDefault", false, false)
}

@Composable
private fun AccessibilityPage(b: Browser, nav: Nav) = Screen(b, nav, "Специальные возможности") {
    choice(b, nav, "Размер текста", "font", Options.fonts, 2, true)
    toggle(b, "Принудительное масштабирование", "Разрешать увеличивать любую страницу, даже если сайт это запрещает",
        "forceZoom", false, true)
    toggle(b, "Масштаб двойным касанием", null, "doubleTapZoom", true, true)
}

@Composable
private fun SiteSettingsPage(b: Browser, nav: Nav) = Screen(b, nav, "Настройки сайтов") {
    nav(nav, "Все сайты", "Разрешения и данные по каждому сайту") { AllSitesPage(b, it) }
    section("Разрешения")
    SitePerms.ALL.forEach { c -> nav(nav, c.title, c.labels[b.perms.defaultIndex(c)]) { CategoryPage(b, it, c) } }
    note("Буфер обмена: движок каждый раз показывает кнопку «Вставить», постоянного разрешения нет.")
}

@Composable
private fun CategoryPage(b: Browser, nav: Nav, c: SitePerms.Cat) {
    val cur = b.perms.defaultIndex(c)
    fun setDefault(i: Int) {
        b.perms.setDefaultIndex(c, i)
        b.applyEngineSettings()
    }
    Screen(b, nav, c.title) {
        if (c.labels.size == 2) {
            item { SwitchRow(c.title, c.labels[cur], cur == 0) { on -> setDefault(if (on) 0 else 1) } }
        } else {
            section("По умолчанию")
            c.labels.forEachIndexed { i, l -> item { RadioRow(l, i == cur) { setDefault(i) } } }
        }
        if (c == SitePerms.NOTIFICATIONS) {
            note("Уведомления приходят, пока сайт открыт во вкладке. Push от закрытых сайтов не поддерживается.")
        }
        if (!c.perSite) {
            note(c.note ?: "")
            return@Screen
        }
        section("Исключения")
        val ex = b.perms.exceptions(c)
        if (ex.isEmpty()) note("Нет сайтов с особыми настройками")
        ex.forEach { (h, v) -> item(key = "ex:$h") { SettingRow(h, b.perms.label(c, v), onClick = { exceptionPage(b, nav, c, h) }) } }
        item {
            SettingRow("Добавить сайт", onClick = {
                b.show(UiDialog.Form("Добавить сайт", null, listOf(FormField("example.com", "", FormField.Kind.URL)), "Далее",
                    onConfirm = { v ->
                        val h = Urls.hostOfInput(v[0])
                        if (h.isNotEmpty()) exceptionPage(b, nav, c, h)
                        true
                    }))
            })
        }
    }
}

private fun exceptionPage(b: Browser, nav: Nav, c: SitePerms.Cat, h: String) {
    val pairs = c.values.indices.filter { c.values[it] != SitePerms.ASK }.map { c.labels[it] to c.values[it] }.toMutableList()
    if (pairs.none { it.second == SitePerms.ALLOW }) pairs.add(0, "Разрешать" to SitePerms.ALLOW)
    pairs += "По умолчанию (${c.labels[b.perms.defaultIndex(c)].lowercase(Locale.ROOT)})" to -1
    nav.push("$h · ${c.title}") { n ->
        RadioPage(b, n, "$h · ${c.title}", pairs.map { it.first }, { pairs.indexOfFirst { it.second == b.perms.exception(c, h) } }) { i ->
            b.perms.setException(c, h, pairs[i].second)
        }
    }
}

@Composable
private fun AllSitesPage(b: Browser, nav: Nav) {
    val tracking by produceState(emptyList<String>()) {
        value = suspendCancellableCoroutine { cont ->
            b.engine.permissions.list { list ->
                cont.resume(list.filter { !it.privateMode && it.permission == SitePermission.TRACKING_EXCEPTION }
                    .mapNotNull { android.net.Uri.parse(it.origin).host })
            }
        }
    }
    val hosts = (b.perms.hosts() + tracking).distinct().sorted()
    Screen(b, nav, "Все сайты") {
        if (hosts.isEmpty()) note("Сайтов с особыми настройками пока нет")
        hosts.forEach { h ->
            item(key = h) {
                val n = SitePerms.ALL.count { b.perms.exception(it, h) >= 0 }
                SettingRow(h, if (n == 0) "Особых разрешений нет" else "Особых разрешений: $n",
                    onClick = { nav.push(h) { SitePage(b, it, h) } })
            }
        }
    }
}

@Composable
private fun SitePage(b: Browser, nav: Nav, h: String) {
    var revision by remember { mutableIntStateOf(0) }
    val trackingOff by produceState(false, revision) {
        value = suspendCancellableCoroutine { cont ->
            b.engine.permissions.list { list ->
                cont.resume(list.any {
                    it.permission == SitePermission.TRACKING_EXCEPTION && Urls.siteHost(it.origin) == h &&
                        it.value == PermissionValue.ALLOW
                })
            }
        }
    }
    Screen(b, nav, h) {
        section("Разрешения")
        SitePerms.ALL.filter { it.perSite }.forEach { c ->
            item(key = c.key) {
                val ex = b.perms.exception(c, h)
                SettingRow(c.title, if (ex >= 0) b.perms.label(c, ex)
                else "По умолчанию: " + c.labels[b.perms.defaultIndex(c)].lowercase(Locale.ROOT),
                    onClick = { exceptionPage(b, nav, c, h) })
            }
        }
        section("Защита")
        item {
            SwitchRow("Защита от отслеживания", if (trackingOff) "Выключена для этого сайта" else "Включена", !trackingOff) { on ->
                b.engine.permissions.set("https://$h", SitePermission.TRACKING_EXCEPTION,
                    if (on) PermissionValue.BLOCK else PermissionValue.ALLOW)
                revision++
            }
        }
        section("Данные")
        item {
            SettingRow("Удалить данные и сбросить разрешения", "Cookie, хранилище и кэш сайта $h", onClick = {
                b.confirm("Удалить данные сайта $h и сбросить его разрешения?", "Удалить") {
                    b.perms.forgetSite(h)
                    b.engine.storage.clearSite(h) { ok -> if (ok) b.toast("Удалено") }
                    revision++
                }
            })
        }
    }
}

@Composable
private fun LanguagesPage(b: Browser, nav: Nav) {
    val tr = b.engine.translation
    val cur = b.prefs.locales
    fun save(list: List<String>) {
        b.prefs.edit { putString("langs", list.joinToString(",")) }
        b.applyEngineSettings()
    }
    Screen(b, nav, "Языки") {
        section("Языки сайтов")
        note("Сайты получают этот список в заголовке Accept-Language и выбирают по нему язык")
        if (cur.isEmpty()) item { SettingRow(Urls.langName(Locale.getDefault().language), "Язык системы") }
        cur.forEachIndexed { i, code ->
            item(key = "lang:$code") {
                SettingRow(Urls.langName(code), if (i == 0) "Основной" else null, onClick = {
                    b.show(UiDialog.Items(Urls.langName(code), buildList {
                        if (i > 0) add(DialogButton("Сделать основным") { save(listOf(code) + (cur - code)) })
                        add(DialogButton("Удалить") { save(cur - code) })
                    }))
                })
            }
        }
        item {
            SettingRow("Добавить язык", onClick = {
                val have = cur.ifEmpty { listOf(Locale.getDefault().language) }
                nav.push("Добавить язык") { n ->
                    Screen(b, n, "Добавить язык") {
                        Options.languages.filter { it !in have }.forEach { code ->
                            item { SettingRow(Urls.langName(code), onClick = { save(have + code); n.back() }) }
                        }
                    }
                }
            })
        }
        if (tr != null) {
            section("Перевод")
            toggle(b, "Предлагать перевод страниц", "Перевод выполняется на устройстве, текст никуда не отправляется",
                "translateOffer", true, true)
            item {
                SettingRow("Переводить на", Urls.langName(b.prefs.translateTarget), onClick = {
                    tr.supported({ _, to ->
                        nav.push("Переводить на") { n ->
                            RadioPage(b, n, "Переводить на", to.map { it.name }, { to.indexOfFirst { it.code == b.prefs.translateTarget } }) { i ->
                                b.prefs.edit { putString("translateTo", to[i].code) }
                            }
                        }
                    }, { b.toast("Перевод недоступен") })
                })
            }
            nav(nav, "Языковые модели", "Скачанные модели перевода") { ModelsPage(b, it) }
            nav(nav, "Сайты без перевода", null) { NeverTranslatePage(b, it) }
        }
    }
}

@Composable
private fun ModelsPage(b: Browser, nav: Nav) {
    val tr = b.engine.translation ?: return
    var revision by remember { mutableIntStateOf(0) }
    val models by produceState<List<LanguageModel>?>(null, revision) {
        value = suspendCancellableCoroutine { cont -> tr.models({ cont.resume(it) }, { cont.resume(emptyList()) }) }
    }
    Screen(b, nav, "Языковые модели") {
        note("Модель скачивается при первом переводе с языка. Здесь можно скачать заранее или удалить.")
        val list = models ?: return@Screen
        if (list.isEmpty()) note("Список моделей пуст")
        list.forEach { m ->
            item(key = m.language.code) {
                val size = android.text.format.Formatter.formatShortFileSize(b.activity, m.size)
                SettingRow(m.language.name, (if (m.downloaded) "Скачана, " else "Не скачана, ") + size, onClick = {
                    val done: (Boolean) -> Unit = { ok -> if (ok) revision++ else b.toast("Не получилось") }
                    if (m.downloaded) tr.deleteModel(m.language.code, done) else tr.downloadModel(m.language.code, done)
                })
            }
        }
    }
}

@Composable
private fun NeverTranslatePage(b: Browser, nav: Nav) {
    val tr = b.engine.translation ?: return
    var revision by remember { mutableIntStateOf(0) }
    val sites by produceState(emptyList<String>(), revision) {
        value = suspendCancellableCoroutine { cont -> tr.neverTranslateSites { cont.resume(it) } }
    }
    Screen(b, nav, "Сайты без перевода") {
        if (sites.isEmpty()) note("Нет сайтов")
        sites.forEach { s ->
            item(key = s) { SettingRow(s, "Нажмите, чтобы снова предлагать перевод", onClick = { tr.allowTranslateSite(s) { revision++ } }) }
        }
    }
}

@Composable
private fun PasswordsPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val data by produceState<Pair<List<SavedLogin>, List<String>>?>(null, revision) {
        value = b.vault.io { logins(null) to nevers() }
    }
    Screen(b, nav, "Менеджер паролей") {
        toggle(b, "Предлагать сохранение паролей", null, "passwordsSave", true, true)
        toggle(b, "Автоматически заполнять пароли", "Подставлять сохранённые данные на страницах входа", "passwordsAutofill", true, true)
        val (logins, nevers) = data ?: return@Screen
        section("Пароли")
        if (logins.isEmpty()) note("Сохранённых паролей нет")
        logins.forEach { l ->
            item(key = l.guid) {
                SettingRow(l.host, l.username.ifEmpty { "Без имени пользователя" }, onClick = {
                    Auth.require(b.activity, "Показать пароль", { nav.push(l.host) { LoginPage(b, it, l) } }, {})
                })
            }
        }
        if (nevers.isNotEmpty()) {
            section("Никогда не сохранять")
            nevers.forEach { h ->
                item(key = "never:$h") {
                    SettingRow(h, "Нажмите, чтобы снова предлагать сохранение", onClick = {
                        b.activity.lifecycleScope.launch { b.vault.io { removeNever(h) }; revision++ }
                    })
                }
            }
        }
        section("Перенос")
        item { SettingRow("Импортировать пароли", "Из CSV-файла Chrome, Cromite или Firefox", onClick = { importCsv(b) { revision++ } }) }
        item {
            SettingRow("Экспортировать пароли", "В CSV-файл. Пароли в нём не зашифрованы", onClick = {
                Auth.require(b.activity, "Экспорт паролей", { exportCsv(b) }, {})
            })
        }
    }
}

private fun importCsv(b: Browser, done: () -> Unit) = b.activity.openDocument(listOf("text/*", "text/csv", "text/comma-separated-values")) { uri ->
    if (uri == null) return@openDocument
    b.activity.lifecycleScope.launch {
        val n = b.vault.io {
            runCatching { b.activity.contentResolver.openInputStream(uri)!!.use { importCsv(it) } }.getOrDefault(-1)
        }
        b.toast(if (n < 0) "Не удалось прочитать файл" else "Импортировано паролей: $n")
        done()
    }
}

private fun exportCsv(b: Browser) = b.activity.createDocument("Antite Passwords.csv", "text/csv") { uri ->
    if (uri == null) return@createDocument
    b.activity.lifecycleScope.launch {
        val n = b.vault.io {
            runCatching { b.activity.contentResolver.openOutputStream(uri)!!.use { exportCsv(it) } }.getOrDefault(-1)
        }
        b.toast(if (n < 0) "Не удалось записать файл" else "Экспортировано паролей: $n")
    }
}

@Composable
private fun LoginPage(b: Browser, nav: Nav, l: SavedLogin) {
    var revision by remember { mutableIntStateOf(0) }
    val password by produceState<String?>(null, revision) { value = b.vault.io { password(l.guid) } }
    var shown by remember { mutableStateOf(false) }
    Screen(b, nav, l.host) {
        val pass = password ?: ""
        item { SettingRow("Сайт", l.origin) }
        item { SettingRow("Имя пользователя", l.username.ifEmpty { "Нет" }, onClick = { b.activity.copy(l.username) }) }
        item { SettingRow("Пароль", if (shown) pass else "••••••••", onClick = { shown = !shown }) }
        note("Нажмите на пароль, чтобы показать или скрыть его")
        item { SettingRow("Копировать пароль", onClick = { b.activity.copy(pass, sensitive = true) }) }
        item {
            SettingRow("Изменить", onClick = {
                b.show(UiDialog.Form("Изменить пароль", null, listOf(
                    FormField("Имя пользователя", l.username),
                    FormField("Пароль", pass, FormField.Kind.VISIBLE_PASSWORD),
                ), onConfirm = { v ->
                    if (v[1].isEmpty()) return@Form false
                    b.activity.lifecycleScope.launch { b.vault.io { editLogin(l.guid, v[0], v[1]) }; revision++ }
                    true
                }))
            })
        }
        item {
            SettingRow("Удалить", onClick = {
                b.confirm("Удалить пароль для ${l.host}?", "Удалить") {
                    b.activity.lifecycleScope.launch { b.vault.io { deleteLogin(l.guid) } }
                    nav.back()
                }
            })
        }
    }
}

@Composable
private fun CardsPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val cards by produceState(emptyList<dev.antite.browser.data.SavedCard>(), revision) { value = b.vault.io { cards() } }
    fun form(card: Card?) = b.show(UiDialog.Form(if (card == null) "Новая карта" else "Изменить карту", null, listOf(
        FormField("Номер карты", card?.number ?: "", FormField.Kind.NUMBER),
        FormField("Имя на карте", card?.name ?: "", FormField.Kind.CAPS),
        FormField("Месяц (ММ)", card?.expirationMonth ?: "", FormField.Kind.NUMBER),
        FormField("Год (ГГГГ)", card?.expirationYear ?: "", FormField.Kind.NUMBER),
    ), onConfirm = { v ->
        val digits = v[0].filter { it.isDigit() }
        if (digits.length < 12) {
            b.toast("Неверный номер карты")
            return@Form false
        }
        val c = Card(card?.guid, v[1].trim(), digits, v[2].trim(), v[3].trim())
        b.activity.lifecycleScope.launch { b.vault.io { saveCard(c) }; revision++ }
        true
    }))
    Screen(b, nav, "Способы оплаты") {
        toggle(b, "Сохранять и заполнять способы оплаты", "Номер карты хранится в зашифрованном виде, CVC не сохраняется",
            "cardsEnabled", true, true)
        section("Карты")
        if (cards.isEmpty()) note("Сохранённых карт нет")
        cards.forEach { c ->
            item(key = c.guid) {
                SettingRow(c.label, c.name, onClick = {
                    b.show(UiDialog.Items(c.label, listOf(
                        DialogButton("Изменить") {
                            Auth.require(b.activity, "Изменить карту", {
                                b.activity.lifecycleScope.launch {
                                    b.vault.io { fullCards() }.firstOrNull { it.guid == c.guid }?.let(::form)
                                }
                            }, {})
                        },
                        DialogButton("Удалить") {
                            b.confirm("Удалить карту ${c.label}?", "Удалить") {
                                b.activity.lifecycleScope.launch { b.vault.io { deleteCard(c.guid) }; revision++ }
                            }
                        },
                    )))
                })
            }
        }
        item { SettingRow("Добавить карту", onClick = { form(null) }) }
    }
}

@Composable
private fun AddressesPage(b: Browser, nav: Nav) {
    var revision by remember { mutableIntStateOf(0) }
    val list by produceState(emptyList<Address>(), revision) { value = b.vault.io { addresses() } }
    fun form(a: Address?) = b.show(UiDialog.Form(if (a == null) "Новый адрес" else "Изменить адрес", null, listOf(
        FormField("Имя и фамилия", a?.name ?: "", FormField.Kind.NAME),
        FormField("Организация", a?.organization ?: "", FormField.Kind.NAME),
        FormField("Улица, дом, квартира", a?.streetAddress ?: "", FormField.Kind.NAME),
        FormField("Город", a?.addressLevel2 ?: "", FormField.Kind.NAME),
        FormField("Регион", a?.addressLevel1 ?: "", FormField.Kind.NAME),
        FormField("Индекс", a?.postalCode ?: "", FormField.Kind.NUMBER),
        FormField("Страна (код, например RU)", a?.country ?: "", FormField.Kind.CAPS),
        FormField("Телефон", a?.tel ?: "", FormField.Kind.PHONE),
        FormField("Эл. почта", a?.email ?: "", FormField.Kind.EMAIL),
    ), onConfirm = { v ->
        val out = (a ?: Address(null)).copy(
            name = v[0].trim(), organization = v[1].trim(), streetAddress = v[2].trim(), addressLevel2 = v[3].trim(),
            addressLevel1 = v[4].trim(), postalCode = v[5].trim(), country = v[6].trim(), tel = v[7].trim(), email = v[8].trim(),
        )
        b.activity.lifecycleScope.launch { b.vault.io { saveAddress(out) }; revision++ }
        true
    }))
    Screen(b, nav, "Адреса и другие данные") {
        toggle(b, "Сохранять и заполнять адреса", "Имя, адрес, телефон и почта для форм на сайтах", "addressesEnabled", true, true)
        section("Адреса")
        if (list.isEmpty()) note("Сохранённых адресов нет")
        list.forEach { a ->
            item(key = a.guid) {
                SettingRow(addressLine(a), onClick = {
                    b.show(UiDialog.Items(addressLine(a), listOf(
                        DialogButton("Изменить") { form(a) },
                        DialogButton("Удалить") {
                            b.confirm("Удалить адрес?", "Удалить") {
                                b.activity.lifecycleScope.launch { b.vault.io { deleteAddress(a.guid!!) }; revision++ }
                            }
                        },
                    )))
                })
            }
        }
        item { SettingRow("Добавить адрес", onClick = { form(null) }) }
    }
}
