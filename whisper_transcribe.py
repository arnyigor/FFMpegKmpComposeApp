#!/usr/bin/env python3
"""Minimal faster-whisper CLI with JSON-lines progress for the desktop app."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from faster_whisper import WhisperModel


def emit(event: str, **payload: object) -> None:
    print(json.dumps({"event": event, **payload}, ensure_ascii=False), flush=True)


def timestamp_srt(seconds: float) -> str:
    millis = max(0, round(seconds * 1000))
    hours, millis = divmod(millis, 3_600_000)
    minutes, millis = divmod(millis, 60_000)
    secs, millis = divmod(millis, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


def timestamp_vtt(seconds: float) -> str:
    return timestamp_srt(seconds).replace(",", ".")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Offline faster-whisper transcription")
    parser.add_argument("input", type=Path)
    parser.add_argument("--output-base", required=True, type=Path)
    parser.add_argument("--model", default="small")
    parser.add_argument("--language", default="auto")
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--formats", default="txt,srt,vtt,json")
    parser.add_argument("--word-timestamps", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.input.is_file():
        emit("error", message=f"Input file not found: {args.input}")
        return 2

    device = args.device
    if device == "auto":
        try:
            import ctranslate2
            device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
        except Exception:
            device = "cpu"
    compute_type = "float16" if device == "cuda" else "int8"

    emit("phase", phase="loading_model", message=f"Loading {args.model} on {device}")
    try:
        model = WhisperModel(args.model, device=device, compute_type=compute_type)
    except Exception as error:
        if args.device == "auto" and device == "cuda":
            emit("phase", phase="loading_model", message=f"CUDA unavailable, fallback to CPU: {error}")
            device = "cpu"
            model = WhisperModel(args.model, device="cpu", compute_type="int8")
        else:
            raise
    emit("phase", phase="transcribing", message="Transcribing audio")

    language = None if args.language in ("", "auto") else args.language
    segments_iter, info = model.transcribe(
        str(args.input),
        language=language,
        beam_size=5,
        vad_filter=True,
        word_timestamps=args.word_timestamps,
    )
    duration = max(float(info.duration or 0.0), 0.001)
    segments: list[dict[str, object]] = []
    for segment in segments_iter:
        item = {
            "start": float(segment.start),
            "end": float(segment.end),
            "text": segment.text.strip(),
        }
        if args.word_timestamps and segment.words:
            item["words"] = [
                {"start": word.start, "end": word.end, "text": word.word}
                for word in segment.words
            ]
        segments.append(item)
        emit(
            "progress",
            processed_seconds=min(float(segment.end), duration),
            total_seconds=duration,
            text=item["text"],
        )

    args.output_base.parent.mkdir(parents=True, exist_ok=True)
    formats = {value.strip().lower() for value in args.formats.split(",") if value.strip()}
    outputs: list[str] = []
    if "txt" in formats:
        path = args.output_base.with_suffix(".txt")
        path.write_text("\n".join(str(s["text"]) for s in segments), encoding="utf-8")
        outputs.append(str(path.resolve()))
    if "srt" in formats:
        path = args.output_base.with_suffix(".srt")
        content = "\n\n".join(
            f"{index}\n{timestamp_srt(float(s['start']))} --> {timestamp_srt(float(s['end']))}\n{s['text']}"
            for index, s in enumerate(segments, 1)
        )
        path.write_text(content + ("\n" if content else ""), encoding="utf-8")
        outputs.append(str(path.resolve()))
    if "vtt" in formats:
        path = args.output_base.with_suffix(".vtt")
        cues = "\n\n".join(
            f"{timestamp_vtt(float(s['start']))} --> {timestamp_vtt(float(s['end']))}\n{s['text']}"
            for s in segments
        )
        path.write_text("WEBVTT\n\n" + cues + ("\n" if cues else ""), encoding="utf-8")
        outputs.append(str(path.resolve()))
    if "json" in formats:
        path = args.output_base.with_suffix(".json")
        path.write_text(
            json.dumps(
                {"language": info.language, "duration": duration, "segments": segments},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        outputs.append(str(path.resolve()))

    emit("completed", outputs=outputs, language=info.language, duration_seconds=duration)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        emit("error", message=str(error), type=type(error).__name__)
        raise
