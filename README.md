# NurAI — голосовой ассистент на Dify

Приложение распознаёт русскую речь, отправляет текст в Dify Chat API и озвучивает ответ
системным Android Text-to-Speech.

## Подключение Dify

Добавьте в корневой `local.properties`:

```properties
DIFY_API_KEY=app-ваш_ключ
DIFY_BASE_URL=https://api.dify.ai/v1
DIFY_API_MODE=workflow
```

После изменения выполните Sync Project with Gradle Files и запустите приложение.
Без ключа интерфейс работает в демонстрационном режиме.

> Важно: `BuildConfig` защищает ключ только от случайной публикации в исходниках, но ключ
> остаётся внутри APK. Для production-выпуска запросы к Dify следует проксировать через
> собственный backend, который хранит секрет на сервере.

## Сборка

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
