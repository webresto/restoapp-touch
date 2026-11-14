# Выпуск Android-сборки

RestoApp touch в Google Play — `org.restoapp.touch` (пакет кода остаётся `cx.m42.restoapp`, D2).
Почему Android вернулся и что в нём не так, как на десктопе, — `.ai-notes/README.md`, D14.

## Ключ загрузки (upload key)

Play App Signing: приложение в магазине подписывает Google, мы подписываем только загрузку.
Ключ загрузки живёт на guibot2 и в репозиторий не попадает никогда:

| Что | Где |
|---|---|
| Хранилище ключа | `~/.android-keys/restoapp-touch-upload.jks` (PKCS12) |
| Пароль хранилища | `~/.android-keys/restoapp-touch-upload.password` |
| Что видит сборка | `.secrets/keystore.properties` в корне checkout-а (`.secrets/` в `.gitignore` целиком) |

Ключ создан так (в `~/.android-keys/`):

```bash
keytool -genkeypair -v -keystore restoapp-touch-upload.jks -storetype PKCS12 \
  -storepass:file restoapp-touch-upload.password -alias restoapp-touch-upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=RestoApp touch, OU=Mobile, O=RestoApp, C=RU"
```

`.secrets/keystore.properties` — четыре ключа (у PKCS12 пароль ключа равен паролю хранилища):

```properties
storeFile=/home/dev0/.android-keys/restoapp-touch-upload.jks
storePassword=<из restoapp-touch-upload.password>
keyAlias=restoapp-touch-upload
keyPassword=<то же>
```

Путь — лучше абсолютный; `~/` тоже понимается, относительный считается от корня проекта.
Вместо файла можно задать переменные окружения (так удобнее в CI): `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` — они главнее файла.
Нет ни того, ни другого — release собирается **неподписанным** (свежий клон обязан собираться без
ключа); такой AAB Play не примет.

Потеря ключа загрузки не смертельна (его сбрасывают через поддержку Play), но пароль и `.jks`
храните вместе и в резервной копии, а не в git, чате или журнале.

## Сборка AAB на guibot2

Собирать только на guibot2 (локальная машина не тянет Gradle + Kotlin-демон). JDK 21, SDK — в
`local.properties` (`sdk.dir=/home/dev0/Android/Sdk`), Superizer — исходники в `../superizer`.

```bash
cd /prj/restoapp-touch/restoapp-android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
# ~/.gradle/gradle.properties на guibot2 урезает демон до Metaspace 512m и компилирует Kotlin внутри
# него — на этом проекте так OutOfMemoryError: Metaspace. Поэтому память — явно, с командной строки.
./gradlew --max-workers=3 "-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8" \
  :composeApp:bundleRelease :composeApp:assembleDebug
# → composeApp/build/outputs/bundle/release/composeApp-release.aab
# → composeApp/build/outputs/apk/debug/composeApp-debug.apk
./gradlew --stop                           # не оставлять демоны на несколько ГБ
```

Подписан ли AAB: `jarsigner -verify composeApp/build/outputs/bundle/release/composeApp-release.aab`
(«jar verified» — подписан; «jar is unsigned» — ключ не нашёлся).

- `versionCode` — 1, либо `ANDROID_VERSION_CODE` из окружения. Play не примет код, который уже видел:
  следующая загрузка — `ANDROID_VERSION_CODE=2 ./gradlew :composeApp:bundleRelease`.
- `versionName` — `appVersionName` в `composeApp/build.gradle.kts`.
- Release идёт через R8; `composeApp/build/outputs/mapping/release/mapping.txt` загружать в Play
  Console вместе с AAB, иначе стектрейсы падений не прочитать.

## Перед загрузкой

- Debug-APK поставить на эмулятор/телефон и открыть: Play отклоняет приложения, падающие на старте.
- Графика и тексты страницы — `store/google-play/`, значок лаунчера оттуда уже в
  `composeApp/src/androidMain/res/`.
- В анкетах Play: рекламного ID нет (`AD_ID` убран из манифеста), уведомлений нет (D3,
  `POST_NOTIFICATIONS` убран). Свои разрешения — `INTERNET` и `VIBRATE`; библиотеки добавляют
  `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `USE_BIOMETRIC` и `c2dm.permission.RECEIVE` (FCM, вкомпилирован
  в `superizer:host`, без `google-services.json` не запускается).
- В меню видна версия и коммит; собранное из копии без `.git` (как на guibot2 после `tar`)
  показывает `unknown`.
