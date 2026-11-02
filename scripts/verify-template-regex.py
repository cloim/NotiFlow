"""Check the actual Kotlin template pattern with Windows ICU, Android's regex engine family.

Run on Windows: uv run python scripts/verify-template-regex.py
The JVM accepts unescaped closing braces that ICU rejects, so Gradle unit tests alone
cannot catch that incompatibility. No app data or network requests are used.
"""

import ctypes
import json
from pathlib import Path
import re
import sys


class ParseError(ctypes.Structure):
    _fields_ = [
        ("line", ctypes.c_int32),
        ("offset", ctypes.c_int32),
        ("pre_context", ctypes.c_uint16 * 16),
        ("post_context", ctypes.c_uint16 * 16),
    ]


def utf16(text):
    raw = text.encode("utf-16-le")
    return (ctypes.c_uint16 * (len(raw) // 2 + 1)).from_buffer_copy(raw + b"\0\0"), len(raw) // 2


def main():
    if sys.platform != "win32":
        raise SystemExit("Windows ICU 검사는 Windows에서 실행해 주세요")
    root = Path(__file__).resolve().parent.parent
    source = (root / "app/src/main/java/com/vibe/notiflow/domain/action/WebhookPayload.kt").read_text(encoding="utf-8")
    match = re.search(r'Regex\("((?:\\.|[^"\\])*)"\)\.replace\(template\)', source)
    if not match:
        raise SystemExit("WebhookPayload의 템플릿 정규식 선언을 확인해 주세요")
    pattern = json.loads('"' + match.group(1) + '"')
    icu = ctypes.WinDLL("icu.dll")
    pointer = ctypes.POINTER(ctypes.c_uint16)
    status_pointer = ctypes.POINTER(ctypes.c_int32)
    icu.uregex_open.argtypes = [pointer, ctypes.c_int32, ctypes.c_uint32, ctypes.POINTER(ParseError), status_pointer]
    icu.uregex_open.restype = ctypes.c_void_p
    icu.uregex_close.argtypes = [ctypes.c_void_p]
    icu.uregex_setText.argtypes = [ctypes.c_void_p, pointer, ctypes.c_int32, status_pointer]
    icu.uregex_findNext.argtypes = [ctypes.c_void_p, status_pointer]
    icu.uregex_findNext.restype = ctypes.c_int8
    icu.uregex_group.argtypes = [ctypes.c_void_p, ctypes.c_int32, pointer, ctypes.c_int32, status_pointer]
    icu.uregex_group.restype = ctypes.c_int32

    def compile_regex(expression):
        text, length = utf16(expression)
        status, error = ctypes.c_int32(0), ParseError()
        compiled = icu.uregex_open(text, length, 0, ctypes.byref(error), ctypes.byref(status))
        return compiled, status.value

    previous, previous_status = compile_regex(r"\{\{([^}]+)}}")
    if previous:
        icu.uregex_close(previous)
    assert previous_status > 0, "ICU에서 기존 오류가 재현되지 않았습니다"
    compiled, status = compile_regex(pattern)
    assert status <= 0 and compiled, f"ICU 정규식 컴파일 실패: {status}"
    try:
        text, length = utf16('{"url":{{url}},"source":{{source}},"text":{{text}}}')
        error = ctypes.c_int32(0)
        icu.uregex_setText(compiled, text, length, ctypes.byref(error))
        groups = []
        while icu.uregex_findNext(compiled, ctypes.byref(error)):
            buffer = (ctypes.c_uint16 * 64)()
            length = icu.uregex_group(compiled, 1, buffer, 64, ctypes.byref(error))
            assert error.value <= 0
            groups.append(bytes(buffer)[:length * 2].decode("utf-16-le"))
        assert error.value <= 0 and groups == ["url", "source", "text"], groups
        print(json.dumps({"previous_pattern_reproduced": True, "kotlin_pattern_icu_compatible": True, "variables_matched": groups}, ensure_ascii=False))
    finally:
        icu.uregex_close(compiled)


if __name__ == "__main__":
    main()
