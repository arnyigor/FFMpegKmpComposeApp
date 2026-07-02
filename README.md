# FFmpeg Desktop Converter

## Текущий рабочий сценарий

Приложение поддерживает четыре операции: быстрое копирование потоков, конвертацию в MP4,
извлечение WAV и локальную транскрибацию Whisper. После выбора источника `ffprobe`
автоматически получает видео-, аудио- и subtitle-дорожки. Для Whisper можно выбрать
аудиодорожку, модель, язык и пословные временные метки. Результат сохраняется одновременно
в TXT, SRT, VTT и JSON.

### Настройка Python/Whisper

Во время разработки приложение может использовать `.venv` в корне проекта:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-whisper.txt
```

Portable-сборка не содержит Python и модели. При первом запуске распознавания приложение
скачивает проверенный по SHA-256 `uv`, Python 3.12 и `faster-whisper` в
`%LOCALAPPDATA%\FFmpegMediaWorkshop\whisper`. На машине с совместимым CUDA runtime
используется GPU с автоматическим fallback на CPU. Модели загружаются в локальный cache.

### Дубляж и llama.cpp

`dubber_gpu_tts.py` остаётся отдельным расширенным pipeline: Whisper → перевод → TTS.
Перевод обращается к OpenAI-compatible endpoint. По умолчанию ожидается `llama.cpp server`
на `http://127.0.0.1:8080/v1`; другой адрес задаётся через `--llm-url`. LM Studio также
можно использовать, если включить его совместимый HTTP server. Для режима записи с
микрофона дополнительно нужен модуль `audio_recorder`, которого нет в текущем репозитории.

### Формат прогресса

FFmpeg и Whisper представлены явными стадиями: анализ, извлечение аудио, загрузка модели,
распознавание и сохранение. Внутреннее время FFmpeg хранится в микросекундах, а UI показывает
прошедшее время и ETA с учётом фактической скорости обработки.

Мощное кроссплатформенное десктопное приложение для конвертации видео файлов, построенное на базе Kotlin Multiplatform и Jetpack Compose.

## 🎯 Описание

FFmpeg Desktop Converter предоставляет удобный графический интерфейс для конвертации видео файлов с использованием FFmpeg. Приложение поддерживает автоматическую настройку FFmpeg, анализ медиа-файлов и отслеживание прогресса конвертации в реальном времени.

## ✨ Основные возможности

### 🎥 Конвертация видео
- **Прямопотоковое копирование** - быстрое копирование без перекодирования
- **Полная конвертация** - перекодирование с настройками качества
- **Поддержка популярных форматов**: MP4, AVI, MKV, MOV, WMV, FLV, WEBM
- **Тестовая конвертация** (5 секунд) для быстрой проверки качества

### 🔊 Аудио обработка
- **Замена аудио дорожки** - возможность добавить новую аудио дорожку
- **Поддержка аудио форматов**: MP3, WAV, FLAC, M4A, AAC, OGG, Opus

### 📊 Анализ и мониторинг
- **Детальный анализ медиа-файлов** (разрешение, FPS, длительность, размер, битрейт)
- **Отслеживание прогресса** в реальном времени с процентом выполнения
- **Система логирования** с 5 уровнями сообщений (DEBUG, INFO, SUCCESS, WARNING, ERROR)
- **Информация о кадрах** (VFR/CFR детекция, общее количество кадров)

### ⚙️ Настройка и конфигурация
- **Автоматическая настройка FFmpeg** - загрузка и конфигурация одним кликом
- **Ручной выбор пути** к существующей установке FFmpeg
- **Сохранение настроек** между сессиями
- **Системные уведомления** о завершении операций

## 🏗️ Архитектура

Проект построен на основе Clean Architecture с модульным подходом и современными практиками Kotlin Multiplatform:

```
├── presentation (UI Layer)
│   ├── components/          # Decompose компоненты
│   │   ├── root/           # Корневая навигация
│   │   ├── setup/          # Экран настройки FFmpeg
│   │   ├── home/           # Основной экран конвертации
│   │   └── utils/          # Вспомогательные функции
│   └── ui/                 # Jetpack Compose экраны
├── domain (Business Logic)  # Бизнес-логика (готово для расширения)
├── data (Data Layer)        # Управление данными
│   ├── config/             # ConfigManager
│   ├── models/             # Модели данных
│   └── FFmpegExecutor      # Выполнение FFmpeg команд
├── di (Dependency Injection) # Koin модули
└── desktop/                # Desktop-специфичный код
```

### Ключевые компоненты

- **RootComponent** - корневой компонент с навигацией между Setup и Home
- **SetupComponent** - настройка FFmpeg (загрузка или выбор пути)
- **HomeComponent** - основной экран с режимами конвертации:
  - **STREAM_COPY** - быстрое копирование потоков
  - **CONVERT** - полная конвертация с перекодированием
- **FFmpegManager** - управление загрузкой и верификацией FFmpeg
- **FFmpegExecutor** - выполнение команд конвертации с прогрессом
- **ConfigManager** - управление конфигурацией приложения

## 🛠️ Технологический стек

### Основные технологии
- **[Kotlin 2.2.0](https://kotlinlang.org/)** - современный язык программирования
- **[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)** - кроссплатформенная разработка
- **[Jetpack Compose Multiplatform 1.8.2](https://www.jetbrains.com/lp/compose-multiplatform/)** - декларативный UI
- **[Decompose 3.4.0](https://github.com/arkivanov/Decompose)** - навигация и управление состоянием
- **[Koin 4.1.1](https://insert-koin.io/)** - dependency injection
- **[Room 2.8.3](https://developer.android.com/training/data-storage/room)** - база данных
- **[Ktor 3.1.3](https://ktor.io/)** - HTTP клиент для загрузок
- **[Android Gradle Plugin 8.10.1](https://developer.android.com/studio/releases/gradle-plugin)** - сборка проекта

### Архитектурные паттерны
- **MVI (Model-View-Intent)** - архитектура представления
- **Clean Architecture** - разделение слоев и ответственности
- **StateFlow** - реактивное управление состоянием
- **Coroutines** - асинхронное программирование
- **Kotlinx.Serialization** - сериализация данных
- **Kotlinx.DateTime** - работа с датой и временем

## 📋 Системные требования

- **Java 17+** (для запуска приложения)
- **Операционная система**: Windows 10+, macOS 10.15+, Linux (Ubuntu 18.04+)
- **ОЗУ**: минимум 4 GB, рекомендуется 8 GB+
- **Место на диске**: ~500 MB (включая FFmpeg)
- **Интернет**: для загрузки FFmpeg при первоначальной настройке

## 🚀 Установка и запуск

### Предварительные требования
1. Установите [JDK 17+](https://adoptium.net/) (рекомендуется Eclipse Temurin)
2. Установите [Android Studio](https://developer.android.com/studio) или [IntelliJ IDEA](https://www.jetbrains.com/idea/)

### Сборка проекта

```bash
# Клонирование репозитория
git clone <repository-url>
cd FfmpegKmpCompose

# Сборка всех модулей
./gradlew build

# Запуск десктопного приложения
./gradlew :desktopApp:run
```

### Создание исполняемого файла

```bash
# Создание нативных дистрибутивов для всех платформ
./gradlew :desktopApp:createDistributable

# Создание конкретного формата:
# macOS
./gradlew :desktopApp:packageMacosDmg

# Windows
./gradlew :desktopApp:packageMsi
./gradlew :desktopApp:packageExe

# Linux
./gradlew :desktopApp:packageDeb
```

## 📖 Использование

### Первоначальная настройка

1. **Запуск приложения** - при первом запуске откроется экран настройки FFmpeg
2. **Выбор способа настройки:**
   - **"Указать путь"** - выберите существующий ffmpeg.exe
   - **"Скачать"** - автоматическая загрузка FFmpeg (~90 MB)
3. **Завершение настройки** - после верификации произойдет переход к основному экрану

### Конвертация видео

#### Выбор файлов
1. **Входной файл** - выберите видео файл для конвертации
2. **Выходной файл** - укажите имя и расположение результата
3. **Аудио файл** (опционально) - выберите для замены аудио дорожки

#### Анализ медиа
- Нажмите **"Анализировать"** для получения детальной информации:
  - Разрешение и формат
  - Частота кадров (VFR/CFR детекция)
  - Длительность и размер файла
  - Битрейт видео и аудио
  - Количество кадров

#### Запуск конвертации

**Режимы конвертации:**
- **STREAM_COPY** (прямопотоковое копирование)
  - ⚡ Быстрая обработка без перекодирования
  - 🔧 Поддерживает только совместимые форматы
  - 💾 Сохраняет оригинальное качество

- **CONVERT** (полная конвертация)
  - 🎯 Полное перекодирование
  - ⚙️ Гибкие настройки качества
  - 🌍 Поддержка любых форматов

**Типы операций:**
- **Полная конвертация** - обработка всего файла
- **Тест (5 сек)** - быстрая проверка качества результата

### Поддерживаемые форматы

**Видео форматы:**
- Входные: MP4, AVI, MKV, MOV, WMV, FLV, WEBM
- Выходные: MP4 (рекомендуется), AVI, MKV

**Аудио форматы:**
- MP3, WAV, FLAC, M4A, AAC, OGG, Opus

## ⚙️ Настройки

### Конфигурация FFmpeg

Приложение использует оптимизированные настройки конвертации:
- **Видео кодек**: H.264 (libx264) для максимальной совместимости
- **Аудио кодек**: AAC с высоким качеством
- **Битрейт**: автоматический выбор для оптимального качества
- **Перезапись**: автоматическая (опция `-y`)

### Сохранение настроек

Настройки приложения сохраняются в:
- **Windows**: `%USERPROFILE%\AppData\Local\FFmpegConverter\config.ini`
- **macOS**: `~/Library/Application Support/FFmpegConverter/config.ini`  
- **Linux**: `~/.config/FFmpegConverter/config.ini`

## 🔧 Разработка

### Структура проекта

```
FfmpegKmpCompose/
├── build.gradle.kts                 # Корневая конфигурация сборки
├── settings.gradle.kts              # Настройки проекта и модулей
├── gradle/
│   └── libs.versions.toml          # Версии зависимостей
├── shared/                          # Общий код для всех платформ
│   ├── build.gradle.kts            # Конфигурация shared модуля
│   ├── src/
│   │   ├── commonMain/             # Общая логика
│   │   │   ├── components/         # Decompose компоненты
│   │   │   │   ├── root/          # RootComponent
│   │   │   │   ├── setup/         # SetupComponent  
│   │   │   │   ├── home/          # HomeComponent
│   │   │   │   └── utils/         # Вспомогательные функции
│   │   │   ├── data/              # Модели данных и менеджеры
│   │   │   │   ├── config/        # ConfigManager
│   │   │   │   └── models/        # Data классы
│   │   │   ├── di/                # Koin модули
│   │   │   ├── domain/            # Бизнес-логика (готов для расширения)
│   │   │   └── ui/                # Compose UI компоненты
│   │   └── desktopMain/           # Desktop-специфичная реализация
│   └── schemas/                    # Room схемы базы данных
└── desktopApp/                     # Desktop приложение
    ├── build.gradle.kts            # Конфигурация desktop модуля
    └── src/
        └── desktopMain/
            └── kotlin/
                └── com/arny/ffmpegcompose/
                    ├── Main.kt             # Точка входа
                    ├── Platform.desktop.kt  # Desktop реализация
                    └── di/
                        └── DesktopKoinModules.kt
```

### Основные зависимости

```kotlin
// Compose Multiplatform BOM
implementation(platform("org.jetbrains.compose:compose-bom:2024.09.00"))
implementation("org.jetbrains.compose.ui:ui")
implementation("org.jetbrains.compose.material3:material3")
implementation("org.jetbrains.compose.material:material-icons-extended")

// Decompose - навигация
implementation("com.arkivanov.decompose:decompose:3.4.0")
implementation("com.arkivanov.decompose:extensions-compose:3.4.0")

// Koin - dependency injection
implementation("io.insert-koin:koin-core:4.1.1")
implementation("io.insert-koin:koin-compose:4.1.1")

// Room - база данных
implementation("androidx.room:room-runtime:2.8.3")
implementation("androidx.room:room-ktx:2.8.3")

// Ktor - HTTP клиент
implementation("io.ktor:ktor-client-core:3.1.3")
implementation("io.ktor:ktor-client-okhttp:3.1.3")
implementation("io.ktor:ktor-client-logging:3.1.3")

// Kotlinx
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
```

### Добавление новой функциональности

1. **Создание нового экрана:**
   ```kotlin
   // Создать компонент в components/
   // Создать UI экран в ui/
   // Добавить навигацию в RootComponent
   ```

2. **Добавление новой зависимости:**
   ```kotlin
   // В shared/build.gradle.kts
   implementation("library:version")
   ```

3. **Создание нового модуля данных:**
   ```kotlin
   // В data/ добавить модель и менеджер
   // Зарегистрировать в SharedModule.kt
   ```

## 🐛 Отладка

### Система логирования

Логи отображаются в правой панели приложения с возможностями:
- **Фильтрации по уровням**: DEBUG, INFO, SUCCESS, WARNING, ERROR
- **Очистки журнала** - кнопка "Очистить логи"
- **Автоматического сохранения** последних 200 записей
- **Временных меток** для каждой записи

### Отладка FFmpeg

При возникновении проблем с конвертацией:

1. **Проверьте логи** в разделе "Консоль" - ищите сообщения уровня ERROR
2. **Убедитесь в корректности путей** к файлам
3. **Проверьте наличие места** на диске
4. **Попробуйте тестовую конвертацию** 5 секунд для диагностики
5. **Проверьте настройки FFmpeg** - переустановите при необходимости

### Распространенные проблемы

- **"ffmpeg не найден"** → Переустановите FFmpeg через экран настройки
- **"Нет места на диске"** → Освободите место или выберите другой диск
- **"Неподдерживаемый формат"** → Используйте режим CONVERT вместо STREAM_COPY
- **"Ошибка анализа файла"** → Проверьте целостность исходного файла

## 🤝 Участие в разработке

### Как внести вклад

1. **Fork** репозитория
2. Создайте **feature branch** (`git checkout -b feature/AmazingFeature`)
3. **Commit** изменения (`git commit -m 'Add some AmazingFeature'`)
4. **Push** в branch (`git push origin feature/AmazingFeature`)
5. Откройте **Pull Request**

### Стандарты кодирования

- Используйте **Kotlin Coding Conventions**
- Соблюдайте **Clean Architecture** принципы
- Добавляйте **KDoc** документацию для публичных API
- Покрывайте критическую логику **тестами**
- Следуйте **MVVM + MVI** паттернам

## 📄 Лицензия

Этот проект распространяется под лицензией MIT.

## 🗺️ Планы развития

### Ближайшие обновления (v1.1.0)
- [ ] **Пакетная конвертация** - обработка нескольких файлов одновременно
- [ ] **Выбор качества и битрейта** - пользовательские настройки
- [ ] **Предпросмотр видео** - встроенный плеер для результата
- [ ] **Сохранение пресетов** - шаблоны настроек конвертации
- [ ] **Расширенные форматы вывода** - WebM, AV1, HEVC

### Среднесрочные цели (v1.5.0)
- [ ] **Плагинная архитектура** - расширения для новых форматов

### Долгосрочные цели (v2.0.0)
- [ ] **Видео редактор** - базовые функции монтажа
- [ ] **Потоковая обработка** - обработка в реальном времени

---

**Создано с ❤️ с использованием Kotlin Multiplatform**

*Версия проекта: 1.0.0*  
*Дата последнего обновления: 2025-11-11*
