#!/usr/bin/env python3
"""
Скрипт *audio‑pipeline*

Преобразует произвольный аудиофайл:
1️⃣ транскрипция Whisper → 2️⃣ перевод через LLM →
3️⃣ синтез речи XTTS → 4️⃣ финальный файл + SRT.

Использование:
    python script.py [input.wav] [-l <LLM_MODEL>]

Параметры:

* `input.wav` – путь к аудиофайлу (по умолчанию берётся из переменной окружения INPUT_FILE).
* `-l, --llm` – имя модели LLM, которую будет использовать translate_text()
  (можно переопределить через $LLM_MODEL_NAME).

Пример:
    python script.py my_audio.wav -l gpt4o
"""

# …

import argparse
import logging
import os
import re
import shutil
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from TTS.api import TTS
from dotenv import load_dotenv
from faster_whisper import WhisperModel
from pydub import AudioSegment

# Загрузка переменных окружения
load_dotenv()

ffmpeg_path = os.getenv("FFMPEG_PATH")
# ────────────────────── Logging configuration ──────────────────────
log_file = Path(__file__).parent / "app.log"

# Удаляем файл логов, если он уже существует
if log_file.exists():
    log_file.unlink()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(log_file, encoding="utf-8"),  # файл в UTF‑8
        logging.StreamHandler(
            sys.stdout
        ),  # поток stdout (будет использовать кодировку терминала)
    ],
)
# Теперь можно использовать логгер по всему файлу:
logger = logging.getLogger(__name__)

# === КРИТИЧЕСКИ ВАЖНО: БЛОКИРУЕМ TORCHCODEC ===
os.environ["TORCHAUDIO_USE_BACKEND_DISPATCHER"] = "0"

# Импортируем torchaudio
import torchaudio

# === ПАТЧ: ЗАМЕНЯЕМ load() ФУНКЦИЮ НА ПРЯМОЙ ВЫЗОВ SOUNDFILE ===
# Это обходит попытку использовать torchcodec
original_load = torchaudio.load


def patched_load(filepath, *args, **kwargs):
    """Патч для torchaudio.load - принудительно использует soundfile backend"""
    try:
        # Пытаемся через soundfile backend напрямую
        import soundfile as sf
        import torch

        data, samplerate = sf.read(filepath, dtype="float32")

        # Конвертируем в torch tensor
        if len(data.shape) == 1:
            data = data.reshape(-1, 1)

        # Транспонируем: soundfile даёт (samples, channels), torch ожидает (channels, samples)
        data = torch.from_numpy(data.T)

        return data, samplerate
    except Exception as e:
        logger.info(f"⚠️ Ошибка загрузки через soundfile: {e}")
        # Fallback на оригинальный метод (если вдруг soundfile не сработал)
        return original_load(filepath, *args, **kwargs)


# Подменяем функцию
torchaudio.load = patched_load
logger.info("✅ torchaudio backend: soundfile (patched)")
# ==============================================


import json
import subprocess
import torch
from tqdm import tqdm
from urllib import request

# Попытка импорта F5 (если не установлен, скрипт упадет с понятной ошибкой)
try:
    from f5_tts.model import DiT, CFM
    from f5_tts.infer.utils_infer import (
        load_checkpoint,
        infer_process,
        preprocess_ref_audio_text,
    )
    from f5_tts.model.utils import convert_char_to_pinyin
except ImportError:
    raise ImportError(
        "F5-TTS не установлен! Выполните: pip install git+https://github.com/SWivid/F5-TTS.git"
    )

if ffmpeg_path:
    AudioSegment.converter = ffmpeg_path
    AudioSegment.ffmpeg = ffmpeg_path
    ffmpeg_dir = os.path.dirname(ffmpeg_path)
    os.environ["PATH"] += os.pathsep + ffmpeg_dir

DEFAULT_INPUT = os.getenv("INPUT_FILE", "sample/audio.wav")
DEFAULT_MODEL = os.getenv("LLM_MODEL_NAME", "local-model")
OUTPUT_FILE_NAME = "final_dubbed.wav"
LLM_SERVER_URL = os.getenv("LLM_SERVER_URL", "http://127.0.0.1:8080/v1")

WHISPER_SIZE = "large-v3"
TARGET_LANG = "ru"
SPEAKER_WAV = "speaker_ref.wav"

# Параметры синхронизации
MAX_SPEED_FACTOR = 1.35
DRIFT_THRESHOLD = 1500


def cleanup_temp_files(temp_dir="temp_segments", ref_wav=SPEAKER_WAV):
    """
    Удаляет каталог с сегментами и, при желании, сам файл‑референс.
    Вызывайте после того, как финальный аудио уже сохранено.
    """
    # 1. Каталог со временными wav‑файлами
    if os.path.isdir(temp_dir):
        try:
            shutil.rmtree(temp_dir)
            logger.info(f"✅ Удалён каталог {temp_dir}")
        except Exception as e:
            logger.warning(f"⚠️ Не удалось удалить {temp_dir}: {e}")

    # 2. Файл‑референс (можно оставить, если понадобится позже)
    if os.path.isfile(ref_wav):
        try:
            os.remove(ref_wav)
            logger.info(f"✅ Удалён референс {ref_wav}")
        except Exception as e:
            logger.warning(f"⚠️ Не удалось удалить {ref_wav}: {e}")


# === ОСТАЛЬНЫЕ ФУНКЦИИ (БЕЗ ИЗМЕНЕНИЙ) ===
def extract_reference_audio(
    input_path, output_ref, duration=10
):  # Увеличил до 10 сек для F5
    """F5 любит референсы подлиннее (10-15 сек)"""
    logger.info(f"✂️ Создание референса голоса (10s) из {input_path}...")
    try:
        audio = AudioSegment.from_wav(input_path)
        start_ms = 10000
        if len(audio) < start_ms + (duration * 1000):
            start_ms = 0
        ref_audio = audio[start_ms : start_ms + (duration * 1000)]
        ref_audio.export(output_ref, format="wav")
    except Exception as e:
        logger.info(f"⚠️ Ошибка референса: {e}")
    return output_ref


def transcribe_audio(file_path):
    """Whisper с прогресс-баром"""
    device = "cuda" if torch.cuda.is_available() else "cpu"
    compute_type = "float16" if device == "cuda" else "int8"

    logger.info(f"🎙️ Запуск Whisper ({WHISPER_SIZE}) на {device}...")
    model = WhisperModel(WHISPER_SIZE, device=device, compute_type=compute_type)

    # 1. Получаем длительность аудио для прогресс-бара
    # Whisper возвращает info ДО начала перебора сегментов
    # Но нам нужно вызвать transcribe, чтобы получить info

    # ВАЖНО: model.transcribe возвращает генератор. Info доступен сразу.
    segments_generator, info = model.transcribe(
        file_path,
        beam_size=5,
        word_timestamps=True,
        initial_prompt="Hello. Welcome to technical analysis. We use Kotlin and Compose.",
    )

    total_duration = info.duration
    logger.info(f"   Длительность аудио: {total_duration:.2f} сек")

    sentence_segments = []
    current_sentence_words = []
    MAX_DURATION = 12.0
    MAX_WORDS = 30

    # Создаем прогресс-бар
    # unit="s" - секунды
    with tqdm(total=total_duration, unit="s", desc="Транскрипция") as pbar:
        last_pos = 0

        for segment in segments_generator:
            # Обновляем прогресс бар по времени конца сегмента
            if segment.end > last_pos:
                pbar.update(segment.end - last_pos)
                last_pos = segment.end

            if not segment.words:
                continue

            for word in segment.words:
                current_sentence_words.append(word)
                curr_word_text = word.word.strip()
                has_punct = curr_word_text.endswith((".", "?", "!"))

                if current_sentence_words:
                    current_duration = word.end - current_sentence_words[0].start
                    word_count = len(current_sentence_words)
                    is_too_long = (current_duration > MAX_DURATION) or (
                        word_count > MAX_WORDS
                    )
                else:
                    is_too_long = False

                if has_punct or is_too_long:
                    start_time = current_sentence_words[0].start
                    end_time = word.end
                    full_text = "".join(
                        [w.word for w in current_sentence_words]
                    ).strip()
                    sentence_segments.append(
                        {"start": start_time, "end": end_time, "text": full_text}
                    )
                    current_sentence_words = []

        # Добиваем прогресс до 100% в конце (иногда Whisper заканчивает чуть раньше метки длительности)
        pbar.update(total_duration - last_pos)

    # Остатки (хвост)
    if current_sentence_words:
        start_time = current_sentence_words[0].start
        end_time = current_sentence_words[-1].end
        full_text = "".join([w.word for w in current_sentence_words]).strip()
        sentence_segments.append(
            {"start": start_time, "end": end_time, "text": full_text}
        )

    logger.info(f"✅ Найдено {len(sentence_segments)} сегментов.")
    return sentence_segments


def merge_short_segments(segments):
    """
    Объединяет короткие сегменты-огрызки с соседними, чтобы не было фраз из одного слова.
    """
    logger.info("🔗 Слияние коротких сегментов...")

    if not segments:
        return []

    merged = []
    current_seg = segments[0]

    # Параметры слияния
    MIN_DURATION = 1.5  # Минимальная длина "нормальной" фразы (сек)
    MIN_WORDS = 3  # Минимальное количество слов
    MAX_MERGE_GAP = 0.8  # Максимальная пауза между фразами для слияния (сек)

    for i in range(1, len(segments)):
        next_seg = segments[i]

        # Анализируем текущий (накопительный) сегмент
        curr_duration = current_seg["end"] - current_seg["start"]
        curr_word_count = len(current_seg["text"].split())

        # Пауза между текущим и следующим
        gap = next_seg["start"] - current_seg["end"]

        # УСЛОВИЕ СЛИЯНИЯ:
        # 1. Текущий сегмент слишком короткий (огрызок) И пауза небольшая
        # 2. ИЛИ следующий сегмент - это огрызок, и он близко к текущему
        is_curr_short = (curr_duration < MIN_DURATION) or (curr_word_count < MIN_WORDS)
        is_next_short = (next_seg["end"] - next_seg["start"]) < MIN_DURATION
        is_gap_small = gap < MAX_MERGE_GAP

        if (is_curr_short or is_next_short) and is_gap_small:
            # === СЛИВАЕМ ===
            # Обновляем текст (через пробел)
            current_seg["text"] += " " + next_seg["text"]
            # Продлеваем время конца
            current_seg["end"] = next_seg["end"]
            # (start остается от первого)

            # logger.info(f"   [Merge] + '{next_seg['text']}'")
        else:
            # === НЕ СЛИВАЕМ ===
            # Сохраняем текущий и берем следующий в работу
            merged.append(current_seg)
            current_seg = next_seg

    # Добавляем последний хвост
    merged.append(current_seg)

    logger.info(f"✅ Было {len(segments)} -> Стало {len(merged)} сегментов.")
    return merged


def translate_text(text_segments, model_name, server_url=LLM_SERVER_URL):
    """Контекстный перевод через OpenAI-compatible llama.cpp/LM Studio server."""
    logger.info(f"🧠 Запуск перевода через {model_name}...")

    # Формируем входной JSON для контекста
    input_data = []
    for i, seg in enumerate(text_segments):
        input_data.append(
            {
                "id": i,
                "text": seg["text"],
                "duration": round(seg["end"] - seg["start"], 1),
            }
        )

    json_input = json.dumps(input_data, ensure_ascii=False, indent=None)

    # === УНИВЕРСАЛЬНЫЙ ПРОМПТ (Оптимизирован для YandexGPT/Instruct) ===
    system_prompt = (
        "Ты — профессиональный переводчик для дубляжа видео.\n"
        "Твоя задача: перевести текст на русский язык, адаптируя его под длительность (lip-sync).\n\n"
        "ПРИНЦИПЫ:\n"
        "1. Смысл важнее буквальности. Передавай суть, а не слова.\n"
        "2. КРАТКОСТЬ. Русский текст длиннее английского. Сокращай воду, используй короткие синонимы, чтобы уложиться в тайминг.\n"
        "3. ТЕРМИНЫ. Не переводи IT-сленг: 'Compose', 'Kotlin', 'App', 'Layout', 'Backend' — оставляй как есть или пиши кириллицей, если это принято.\n"
        "4. ФОРМАТ. Верни ТОЛЬКО JSON список.\n"
    )

    user_prompt = (
        f"Переведи этот транскрипт:\n{json_input}\n\n"
        'Ответ должен быть строгим JSON: [{"id": 0, "text_ru": "..."}, ...]'
    )

    logger.info("   📤 Отправка запроса к LLM...")
    try:
        logger.info("⏳ Генерация ответа...")
        payload = json.dumps(
            {
                "model": model_name,
                "temperature": 0.1,
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            },
            ensure_ascii=False,
        ).encode("utf-8")
        endpoint = server_url.rstrip("/") + "/chat/completions"
        http_request = request.Request(
            endpoint,
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        with request.urlopen(http_request, timeout=600) as response:
            response_json = json.loads(response.read().decode("utf-8"))
        content = response_json["choices"][0]["message"]["content"]
        print(f"🤖 LLM: {content}", flush=True)

        if not content:
            logger.exception("   ❌ LLM вернул пустой content")
            return text_segments

        logger.info(f"   📝 Длина ответа: {len(content)} символов")
        logger.info(f"   📝 Полный ответ:\n{content}")

        # Удаляем блок <think>...</think> (может быть многострочным)
        content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL)

        # === БЕЗОПАСНАЯ ОЧИСТКА ОТ MARKDOWN ===
        # Создаем строку из трех обратных кавычек математически,
        # чтобы не ломать разметку при копировании кода
        code_fence = "`" * 3

        if code_fence in content:
            # Разбиваем по грависам
            parts = content.split(code_fence)

            # Обычно JSON внутри блока, то есть во второй части (индекс 1)
            if len(parts) >= 2:
                content = parts[1].strip()

                # Если LLM добавила слово json после кавычек, убираем его
                if content.lower().startswith("json"):
                    content = content[4:].strip()
        # ======================================
        # Парсим JSON
        try:
            translated_json = json.loads(content)
        except json.JSONDecodeError as e:
            logger.exception(f"   ❌ Ошибка парсинга JSON: {e}")
            logger.info(f"   📄 Полный ответ LLM:\n{content}")
            return text_segments

        trans_map = {item["id"]: item["text_ru"] for item in translated_json}

        final_segments = []
        for i, seg in enumerate(text_segments):
            # Получаем перевод или используем оригинал как fallback
            trans_text = trans_map.get(i, seg["text"])

            # === ЗАЩИТА ОТ ПУСТЫХ СТРОК ===
            if not trans_text or not trans_text.strip():
                logger.warning(f"   ⚠️ Сегмент {i} пустой, используем оригинал")
                trans_text = seg["text"]
            # ===============================

            trans_text = str(trans_text).replace('"', "").replace("\n", " ").strip()

            final_segments.append(
                {
                    "start": seg["start"],
                    "end": seg["end"],
                    "original": seg["text"],
                    "text": trans_text,
                }
            )

            # Сокращенный вывод для лога
            orig_short = seg["text"]
            trans_short = trans_text
            logger.info(f"   [{i}] {orig_short} -> {trans_short}")
        return final_segments
    except Exception as e:
        logger.exception(f"❌ Ошибка LLM: {type(e).__name__}: {e}")
        import traceback

        logger.info("   📋 Полный стектрейс:")
        traceback.print_exc()
        logger.info("   Возвращаем оригинальный текст для безопасности.")
        return text_segments


def save_srt(segments, path, translate=False):
    def format_time(t):
        hours = int(t // 3600)
        minutes = int((t % 3600) // 60)
        seconds = int(t % 60)
        millis = int((t - int(t)) * 1000)
        return f"{hours:02}:{minutes:02}:{seconds:02},{millis:03}"

    with open(path, "w", encoding="utf-8") as f:
        for i, seg in enumerate(segments, 1):
            start = format_time(seg["start"])
            end = format_time(seg["end"])
            text = seg["text"]
            if translate:
                text = f"[translate:{text}]"
            f.write(f"{i}\n{start} --> {end}\n{text}\n\n")
    logger.info(f"💾 Субтитры сохранены в {path}")


def cleanup_translation(segments):
    """
    Использует LLM для удаления повторов и "заиканий" между сегментами.
    """
    logger.info("🧹 Очистка перевода с помощью LLM...")

    # Собираем текст в один блок с разделителями, чтобы LLM видела "швы"
    text_to_clean = ""
    for i, seg in enumerate(segments):
        text_to_clean += f"[ФРАЗА {i}] {seg['text']} [КОНЕЦ {i}]\n"

    # Промпт для очистки
    system_prompt = (
        "Ты — редактор текста для дубляжа. Твоя задача — найти и удалить повторы слов на стыках фраз.\n"
        "ПРИМЕР ПРОБЛЕМЫ:\n"
        "Фраза 1: 'Привет, как дела сегодня'\n"
        "Фраза 2: 'сегодня мы пойдем гулять'\n"
        "ИСПРАВЛЕНИЕ:\n"
        "Фраза 1: 'Привет, как дела сегодня'\n"
        "Фраза 2: 'мы пойдем гулять' (убрали 'сегодня')\n\n"
        "Верни JSON список объектов с полями 'id' и 'text_clean'. Не меняй смысл, только удаляй дубли."
    )
    user_prompt = f"Вот текст с возможными повторами:\n\n{text_to_clean}\n\nОтредактируй его и верни ТОЛЬКО исправленный текст, сохраняя формат [ФРАЗА ID] ... [КОНЕЦ ID]."
    logger.info("   📤 Отправка запроса к LLM...")
    print(f"⏳ Загрузка модели: {LLM_MODEL_NAME}...")
    model = lms.llm(LLM_MODEL_NAME)
    print(f"✅ Модель {model} готова")

    # 2. Формируем чат (аналог messages в OpenAI)
    chat = lms.Chat(system_prompt)  # System prompt идет в конструктор
    chat.add_user_message(user_prompt)

    try:
        logger.info("⏳ Генерация ответа...")
        full_content = ""
        stream = model.respond_stream(
            chat, config=(lms.LlmPredictionConfig(temperature=Temperature(0.1)))
        )

        # 3. Читаем поток
        print("🤖 LLM: ", end="", flush=True)  # Маркер начала

        for chunk in stream:
            # В зависимости от версии SDK, текст может быть в chunk.content или просто chunk
            # Обычно в lmstudio sdk это chunk.content
            text_part = chunk.content

            if text_part:
                # Выводим в консоль без переноса строки
                print(text_part, end="", flush=True)

                # Собираем в общую строку
                full_content += text_part

        print()

        cleaned_text = full_content

        # Парсим ответ обратно в сегменты
        # Ищем все блоки [ФРАЗА id]...[КОНЕЦ id]
        import re

        # Используем re.findall для поиска всех совпадений
        cleaned_phrases = re.findall(
            r"\[ФРАЗА (\d+)\](.*?)\[КОНЕЦ \1\]", cleaned_text, re.DOTALL
        )

        # Создаем словарь для быстрого доступа
        cleaned_map = {int(id): text.strip() for id, text in cleaned_phrases}

        # Обновляем наши сегменты
        final_segments = []
        for i, seg in enumerate(segments):
            # Берем очищенный текст или оставляем старый, если что-то пошло не так
            new_text = cleaned_map.get(i, seg["text"])

            new_seg = seg.copy()
            new_seg["text"] = new_text
            final_segments.append(new_seg)

            if new_text != seg["text"]:
                logger.info(f"   [Clean] Сегмент {i}: '{seg['text']}' -> '{new_text}'")
        return final_segments
    except Exception as e:
        logger.exception(f"❌ Ошибка очистки LLM: {e}")
        return segments  # Возвращаем старый вариант при ошибке
    finally:
        model.unload()  # Освобождаем


def generate_and_sync_audio(segments, ref_wav):
    """
    Генерация XTTS v2 с параметром скорости и мягкой синхронизацией.
    """
    logger.info("🗣️ Генерация голоса (XTTS v2) [SPEED x1.3] с мягким таймингом...")

    # 1. Инициализация (используем CUDA)
    device = "cuda" if torch.cuda.is_available() else "cpu"

    # Загружаем модель
    # Важно: TTS("...") загружает модель. Если она уже загружена глобально, можно передать её аргументом.
    # Но для простоты создадим тут.
    tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to(device)

    final_audio = AudioSegment.empty()
    temp_dir = "temp_segments"
    os.makedirs(temp_dir, exist_ok=True)

    current_audio_head = 0.0

    for i, seg in enumerate(segments):
        # Очистка текста перед TTS (важно для XTTS)
        text = str(seg["text"]).replace('"', "").replace("\n", " ").strip()

        original_start_ms = seg["start"] * 1000
        original_end_ms = seg["end"] * 1000

        raw_path = f"{temp_dir}/seg_{i}.wav"

        # 2. Генерация
        if len(text) > 1:
            # === ГЛАВНОЕ: speed=1.3 ===
            # Это заставляет XTTS говорить на 30% быстрее сразу при генерации
            tts.tts_to_file(
                text=text,
                file_path=raw_path,
                speaker_wav=ref_wav,
                language="ru",
                split_sentences=False,
                speed=1.3,
            )

            # Загружаем сгенерированный файл
            if os.path.exists(raw_path):
                segment_audio = AudioSegment.from_wav(raw_path)
            else:
                segment_audio = AudioSegment.silent(duration=100)
        else:
            segment_audio = AudioSegment.silent(duration=100)
            # Создаем пустой файл, чтобы ffmpeg не ругался
            segment_audio.export(raw_path, format="wav")

        seg_len_ms = len(segment_audio)

        # 3. Синхронизация (Floating Head)
        # Начинаем не раньше, чем закончилась предыдущая, и не раньше оригинального старта
        start_pos_ms = max(original_start_ms, current_audio_head)

        # Заполняем дырку тишиной
        silence_gap = start_pos_ms - current_audio_head
        if silence_gap > 0:
            final_audio += AudioSegment.silent(duration=silence_gap)
            current_audio_head += silence_gap

        # 4. Проверка на Дрифт (Нужно ли доп. сжатие?)
        projected_end_ms = start_pos_ms + seg_len_ms
        drift_ms = projected_end_ms - original_end_ms

        final_segment_to_add = segment_audio

        # Если мы все равно отстаем больше чем на 1.5 секунды (даже с ускорением 1.3x)
        if drift_ms > 1500:
            # Считаем, сколько времени у нас реально есть (с небольшим запасом)
            available_duration_ms = (original_end_ms + 500) - start_pos_ms
            if available_duration_ms < 100:
                available_duration_ms = 100

            ffmpeg_speed_factor = seg_len_ms / available_duration_ms

            # Ограничиваем ffmpeg сжатие (чтобы не было "писка")
            ffmpeg_speed_factor = max(1.0, min(ffmpeg_speed_factor, 1.3))

            if ffmpeg_speed_factor > 1.05:
                logger.info(
                    f"   ⚠️ Сегмент {i}: доп. сжатие x{ffmpeg_speed_factor:.2f} (drift {int(drift_ms)}ms)"
                )
                processed_path = f"{temp_dir}/seg_{i}_proc.wav"

                cmd = [
                    ffmpeg_path,
                    "-y",
                    "-i",
                    raw_path,
                    "-filter:a",
                    f"atempo={ffmpeg_speed_factor}",
                    processed_path,
                ]
                subprocess.run(
                    cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
                )

                if os.path.exists(processed_path):
                    final_segment_to_add = AudioSegment.from_wav(processed_path)

        final_audio += final_segment_to_add
        current_audio_head += len(final_segment_to_add)

    return final_audio


def record_microphone_audio(
    output_path: str,
    duration_sec: int = 60,
    sample_rate: int = 44100,
    device_name: str = None,
) -> bool:
    """
    Запись аудио с микрофона с использованием FFmpeg.

    Args:
        output_path: Путь для сохранения записи.
        duration_sec: Максимальная длительность записи в секундах.
        sample_rate: Частота дискретизации.
        device_name: Имя устройства (опционально).

    Returns:
        True если запись успешна, False в случае ошибки.
    """
    logger.info(f"🎤 Запись аудио с микрофона: {output_path}")

    try:
        from audio_recorder import AudioRecorder, get_ffmpeg_path, AudioRecorderError
        ffmpeg_exec = get_ffmpeg_path()
        recorder = AudioRecorder(ffmpeg_exec)

        devices = recorder.list_devices()
        if devices and not device_name:
            logger.info(f"   Найдено устройств: {len(devices)}")
            for i, dev in enumerate(devices[:5]):
                logger.info(f"   [{i}] {dev.name}")
            device_name = devices[0].name if devices else None

        if device_name:
            logger.info(f"   Используемое устройство: {device_name}")

        success, duration = recorder.record_and_save(
            output_path=output_path,
            duration_sec=duration_sec,
            device_name=device_name,
            sample_rate=sample_rate,
        )

        if success:
            logger.info(f"✅ Запись завершена: {output_path} ({duration:.1f}s)")
            return True
        else:
            logger.error("❌ Ошибка записи")
            return False

    except Exception as e:
        logger.exception(f"❌ Ошибка recorder: {e}")
        return False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="audio-pipeline",
        description="Транскрипция → Перевод → TTS. "
        "Поясняет, какие параметры принимает скрипт.",
    )

    parser.add_argument(
        "file_path",
        nargs="?",
        default=DEFAULT_INPUT,
        help=f"Путь к входному аудиофайлу (по умолчанию: {DEFAULT_INPUT}).",
    )

    parser.add_argument(
        "-l",
        "--llm",
        dest="model_name",
        default=DEFAULT_MODEL,
        metavar="LLM",
        help=(
            "Имя модели LLM, которую будет использовать перевод "
            "(можно переопределить переменной окружения $LLM_MODEL_NAME)."
        ),
    )
    parser.add_argument(
        "--llm-url",
        default=LLM_SERVER_URL,
        help="OpenAI-compatible endpoint llama.cpp/LM Studio (по умолчанию http://127.0.0.1:8080/v1).",
    )

    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        dest="verbose",
        help="Выводить подробный вывод (DEBUG‑уровень).",
    )

    # NEW: путь к ffmpeg, чтобы пользователь мог указать его явно
    parser.add_argument(
        "-f",
        "--ffmpeg",
        dest="ffmpeg_path",
        metavar="PATH",
        help="Путь к исполняемому файлу ffmpeg (если он не в PATH).",
    )

    # === Режим записи аудио ===
    record_group = parser.add_argument_group("Запись аудио с микрофона")
    record_group.add_argument(
        "--record",
        metavar="OUTPUT",
        help="Записать аудио с микрофона и сохранить в файл (режим записи).",
    )
    record_group.add_argument(
        "--list-devices",
        action="store_true",
        help="Показать список доступных аудиоустройств.",
    )
    record_group.add_argument(
        "--device",
        metavar="NAME",
        help="Имя аудиоустройства для записи (если не указано, используется первое).",
    )
    record_group.add_argument(
        "--duration",
        type=int,
        default=60,
        metavar="SECONDS",
        help="Длительность записи в секундах (по умолчанию: 60).",
    )

    return parser.parse_args()


def main():
    args = parse_args()

    if args.verbose:
        from audio_recorder import set_verbose

        set_verbose(True)
        logging.getLogger().setLevel(logging.DEBUG)
    else:
        logging.getLogger().setLevel(logging.INFO)

    # ──────── FFmpeg ────────
    ffmpeg_path = args.ffmpeg_path or os.getenv("FFMPEG_PATH")

    # === РЕЖИМ ЗАПИСИ АУДИО ===
    if args.list_devices:
        try:
            from audio_recorder import AudioRecorder, get_ffmpeg_path

            if not ffmpeg_path:
                ffmpeg_path = get_ffmpeg_path()
            recorder = AudioRecorder(ffmpeg_path)
            devices = recorder.list_devices()
            print("\n🎤 Доступные аудиоустройства:")
            for i, dev in enumerate(devices):
                print(f"   [{i}] {dev.name}")
            print()
            return
        except Exception as e:
            logger.error(f"Ошибка получения списка устройств: {e}")
            return

    if args.record:
        output_path = args.record
        logger.info(f"🎤 Режим записи: {output_path}")

        success = record_microphone_audio(
            output_path=output_path, duration_sec=args.duration, device_name=args.device
        )

        if success:
            logger.info("✅ Запись завершена успешно")
        else:
            logger.error("❌ Ошибка записи")
        return
    # ========================================

    if not ffmpeg_path:
        logger.error(
            "Не найден путь к ffmpeg. Укажите переменную окружения FFMPEG_PATH "
            "или передайте аргумент --ffmpeg /full/path/to/ffmpeg"
        )
        sys.exit(1)

    # Переопределяем путь для pydub
    AudioSegment.converter = ffmpeg_path
    AudioSegment.ffmpeg = ffmpeg_path

    # Добавляем его в системный PATH, чтобы subprocess и другие инструменты тоже видели
    os.environ["PATH"] += os.pathsep + os.path.dirname(ffmpeg_path)

    # Путь к файлу (expanduser для ~)
    input_path = Path(args.file_path).expanduser()
    model_name = args.model_name
    logger.info(f"Начинаю обработку: {input_path}")

    if not input_path.exists():
        logger.error("❌ Файл не найден: %s", input_path)
        sys.exit(1)

    # 1. Создаём референс
    extract_reference_audio(str(input_path), SPEAKER_WAV, duration=12)

    # 2. Транскрипция
    raw_segments = transcribe_audio(str(input_path))

    # 3. Слияние огрызков
    merged_segments = merge_short_segments(raw_segments)

    # 4. Перевод
    translated_segments = translate_text(merged_segments, model_name, args.llm_url)

    # 5. Очистка дублей
    final_segments = cleanup_translation(translated_segments)

    # 6. Создаём субтитры
    save_srt(final_segments, input_path.parent / "final_subs.srt")

    # 7. Генерация и синхронизация аудио
    final_dub = generate_and_sync_audio(final_segments, SPEAKER_WAV)
    output_file = input_path.parent / OUTPUT_FILE_NAME

    logger.info("💾 Сохраняю финальный файл в %s", output_file)
    final_dub.export(str(output_file))

    cleanup_temp_files()
    logger.info("🎉 Всё готово!")


if __name__ == "__main__":
    main()
