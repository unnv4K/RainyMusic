Запись тегов и обложек прямо в mp3 (Android)
============================================

Что это
  Когда в плеере меняешь название, исполнителя или обложку, эти данные записываются в сам mp3-файл (ID3v2).
  Работает только для .mp3. Остальные форматы (flac, m4a и др.) плеер не трогает: нативный метод отклонит их, файл останется нетронутым.

Куда что класть (проект Capacitor, папка android/)
  1. Скопируй Id3Writer.java и TagWriter.java в
       android/app/src/main/java/com/rainy/player/
  2. Открой BgPlugin.java (там же) и перенеси в него из BgPlugin_writeTags_snippet.java:
       - все import-строки, которых ещё нет;
       - поле tagIo и методы writeTags, tryWrite, askUser, writeTagsResult, resolveMediaUri.
     Класс-обёртку BgPlugin_writeTags_snippet в проект не копируй: она нужна только для проверки компиляции.
     Если в BgPlugin уже есть метод с именем resolveMediaUri, переименуй один из них.
  3. Положи index.html из этой папки в www/index.html вместо старого (в нём уже есть вызов Capacitor.Plugins.Bg.writeTags).
  4. Собери: npx cap sync android, затем Build -> Build APK(s) в Android Studio (или ./gradlew assembleRelease).
  Новые разрешения в AndroidManifest не нужны, достаточно доступа к музыке, который уже есть.

Как это работает
  - Метод в JS: Capacitor.Plugins.Bg.writeTags({uri, title, artist, cover}). cover: base64 jpeg/png без "data:" или null (оставить как есть).
    Объекта window.cordova.plugins.BgPlugin в Capacitor нет: плагин зарегистрирован под именем Bg.
  - Оригинал копируется в кэш (это и резервная копия), собирается новый файл, проверяется, что аудиопоток не изменился,
    потом файл перезаписывается через ContentResolver и сверяется хеш. При любом сбое оригинал возвращается из копии.
  - Android 11+: если у приложения нет права менять файл, система один раз покажет окно подтверждения (MediaStore.createWriteRequest),
    затем запись повторяется сама. Android 10: через RecoverableSecurityException. Android 9 и ниже: прямая запись.

Что проверено, а что нет
  Проверено на настоящих mp3 (ID3 v2.2/2.3/2.4 и файл без тега, кириллица, юникод, обложки):
    теги читаются ffprobe, остальные кадры (альбом, комментарий, год) сохраняются, хеш аудиопотока не меняется.
    Откат при обрыве записи посреди файла возвращает оригинал побайтово.
  Не проверено: связка с Android (MediaStore.createWriteRequest, окно подтверждения, ActivityCallback).
    Код компилируется только на заглушках Android API, на телефоне его нужно проверить: сначала на копии одного трека.
