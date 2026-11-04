package cx.m42.restoapp

import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.protocol.MobileApiException

/** The host's own words, in the two languages it speaks. */
internal class HostWords(lang: String) {
    private val ru = lang.startsWith("ru")

    fun t(ru: String, en: String): String = if (this.ru) ru else en

    val signIn = t("Войти", "Sign in")
    val signOut = t("Выйти", "Sign out")
    val login = t("Логин", "Login")
    val password = t("Пароль", "Password")
    val solving = t("Проверяем, что вы не робот…", "Checking you are not a robot…")
    val signingIn = t("Входим…", "Signing in…")
    val needsSignIn = t("Нужно войти заново", "Sign in again")
    val offline = t("Нет связи с сервером — меню из прошлого раза", "No connection to the server — the menu is from last time")
    val retry = t("Повторить", "Retry")
    val updateRequired = t("Сервер новее приложения — обновите приложение", "The server is newer than the app — update the app")
    val nothingGranted = t("На этом сервере вам пока ничего не доступно — обратитесь к администратору", "Nothing on this server is open to you yet — ask your administrator")
    val rightsChanged = t("Права изменились", "Your rights changed")
    val address = t("Адрес", "Address")
    val name = t("Название", "Name")
    val next = t("Продолжить", "Continue")
    val checking = t("Проверяем адрес…", "Checking the address…")
    val demoServer = t("Демо-сервер", "Demo server")
    val demoServerHint = t("Блюда и заказы на двух точках, без сети — чтобы посмотреть, как всё работает", "Dishes and orders at two points, no network — to see how it all works")
    val ownServer = t("Свой сервер", "Your server")
    val ownServerHint = t("По адресу вашего RestoApp: вход по логину и паролю админки", "By your RestoApp's address: sign in with your admin panel login")
    val demoData = t("Демо-данные хранятся только на этом компьютере", "Demo data is kept on this computer only")
    val role = t("Роль", "Role")
    val change = t("Сменить", "Change")
    val resetDemo = t("Сбросить демо-данные", "Reset demo data")
    val demoTitle = t("Демо-сервер", "Demo server")

    fun signedInAs(login: String) = t("Вы вошли как $login", "Signed in as $login")

    fun placeGone(title: String) = t("Доступ к точке «$title» снят", "Access to the point “$title” was removed")

    /** An app whose code comes from the server (D12) could not be downloaded: the tile stays, empty. */
    fun scriptFailed(title: String) = t("«$title» не загрузилось с сервера", "“$title” did not download from the server")

    fun server(name: String, version: String?) = t("Сервер «$name»", "Server “$name”") + (version?.let { " · $it" } ?: "")

    fun progress(fraction: Float) = solving + " " + (fraction * 100).toInt() + "%"

    fun roleTitle(role: DemoRole) = when (role) {
        DemoRole.Admin -> t("Администратор", "Administrator")
        DemoRole.Operator -> t("Оператор", "Operator")
        DemoRole.Stock -> t("Склад", "Stock manager")
    }

    fun roleHint(role: DemoRole) = when (role) {
        DemoRole.Admin -> t("Всё на всех точках", "Everything at every point")
        DemoRole.Operator -> t("Только «Заказы», только первая точка — группа Operator", "«Orders» only, at the first point — the Operator group")
        DemoRole.Stock -> t("Только «Блюда», на всех точках — группа Stock Manager", "«Dishes» only, at every point — the Stock Manager group")
    }

    fun demoRole(role: DemoRole) = t("Демо · роль: ", "Demo · role: ") + roleTitle(role)

    /** A failure of sign-in or of adding a server, by the protocol's code. */
    fun failure(error: Throwable): String {
        val failure = error as? MobileApiException ?: return t("Не получилось решить задачу входа — попробуйте ещё раз", "Could not solve the sign-in task — try again")
        return when (failure.code) {
            MobileApiException.INVALID_CREDENTIALS -> t("Неверный логин или пароль", "Wrong login or password")
            MobileApiException.FORBIDDEN -> t("У этой учётной записи нет доступа в админку", "This account has no access to the admin panel")
            MobileApiException.RATE_LIMITED -> failure.retryAfterSeconds?.let { t("Слишком много попыток, подождите $it с", "Too many attempts, wait $it s") }
                ?: t("Слишком много попыток, подождите", "Too many attempts, wait a little")
            MobileApiException.CAPTCHA_INVALID -> t("Задача входа устарела — попробуйте ещё раз", "The sign-in task expired — try again")
            MobileApiException.NETWORK -> t("Нет связи с сервером", "No connection to the server")
            MobileApiException.HTTPS_REQUIRED -> t("Сервер принимает вход только по HTTPS", "The server takes sign-ins over HTTPS only")
            MobileApiException.UNAVAILABLE -> t("Мобильный API на сервере выключен или недоступен", "The mobile API of the server is off or unavailable")
            MobileApiException.PROTOCOL, MobileApiException.NOT_FOUND -> t("По этому адресу нет RestoApp с мобильным API", "There is no RestoApp with the mobile API at this address")
            MobileApiException.UPDATE_REQUIRED -> updateRequired
            else -> failure.message ?: failure.code
        }
    }
}
