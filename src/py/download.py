import copy, json, os, re, shutil, sys, yt_dlp
from urllib.parse import urlsplit

# yt-dlp only fetches raw streams here; it never merges (merging is done in the
# browser with ffmpeg.wasm). Each format ID is downloaded in its own call so the
# output is deterministic: /mnt/dl/<video_id>.f<format_id>.<ext>
# REPORT_PROGRESS(index, downloaded_bytes, total_bytes, finished) is a JS callback.
shutil.rmtree("/mnt/dl", ignore_errors=True)
os.makedirs("/mnt/dl")
try:
    import js as _js
    _js.eval("globalThis.__YTDLP_XHR_DIAG = []")
except Exception:
    _js = None


def _number(value):
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError, OverflowError):
        return None


def _endpoint(value):
    if not value:
        return "unset"
    try:
        parsed = urlsplit(str(value))
        if not parsed.scheme or not parsed.hostname:
            return "configured"
        port = f":{parsed.port}" if parsed.port else ""
        return f"{parsed.scheme}://{parsed.hostname}{port}"
    except Exception:
        return "invalid"


def _safe_error(error):
    text = re.sub(r"\s+", " ", str(error)).strip()
    # Do not expose signed media URLs or query tokens in a report the user may share.
    text = re.sub(r"https?://[^\s\"'<>]+", lambda match: _endpoint(match.group()) + "/[redacted]", text)
    text = re.sub(r"(?i)\b(cookie|authorization)\s*[:=]\s*[^,; ]+", r"\1=[redacted]", text)
    return text[:500]


def _nested_errors(error):
    """Expose yt-dlp RequestDirector's hidden unexpected errors, safely and briefly."""
    pending = []
    for attribute in ("unexpected_errors", "unsupported_errors"):
        values = getattr(error, attribute, None)
        if isinstance(values, (list, tuple)):
            pending.extend(values[:8])
    if not pending:
        for attribute in ("cause", "__cause__", "__context__"):
            cause = getattr(error, attribute, None)
            if cause:
                pending.append(cause)

    details = []
    seen = set()
    while pending and len(details) < 8:
        nested = pending.pop(0)
        if nested is None or id(nested) in seen:
            continue
        seen.add(id(nested))
        details.append({"type": type(nested).__name__, "message": _safe_error(nested)})
        for attribute in ("cause", "__cause__", "__context__"):
            cause = getattr(nested, attribute, None)
            if cause and id(cause) not in seen:
                pending.append(cause)
    return details


def _progress_hook(index, state):
    def hook(data):
        if data.get("status") not in ("downloading", "finished"):
            return
        downloaded = _number(data.get("downloaded_bytes")) or 0
        total = _number(data.get("total_bytes")) or _number(data.get("total_bytes_estimate")) or 0
        state["events"] += 1
        state["last_status"] = data.get("status")
        state["downloaded_bytes"] = downloaded
        state["total_bytes"] = total
        state["fragment_index"] = _number(data.get("fragment_index"))
        REPORT_PROGRESS(index, downloaded, total, data.get("status") == "finished")
    return hook


def _xhr_diagnostics():
    if _js is None:
        return []
    try:
        raw = _js.eval("JSON.stringify((globalThis.__YTDLP_XHR_DIAG || []).slice(-40))")
        return json.loads(str(raw))
    except Exception:
        return []


def _debug_report(error, index, format_id, cache_hit, cached_info, selected_format, progress):
    formats = cached_info.get("formats", []) if isinstance(cached_info, dict) else []
    if not isinstance(formats, list):
        formats = []
    try:
        files = [
            {"name": name, "bytes": os.path.getsize(os.path.join("/mnt/dl", name))}
            for name in sorted(os.listdir("/mnt/dl"))
        ]
    except Exception:
        files = []
    version = getattr(getattr(yt_dlp, "version", None), "__version__", "unknown")
    media_url = (selected_format or {}).get("url") or (selected_format or {}).get("manifest_url")
    report = {
        "yt_dlp": str(version),
        "python": sys.version.split()[0],
        "target_host": _endpoint(TARGET_URL),
        "proxy_used": _endpoint(globals().get("CORS_PROXY", "")),
        "cache_hit": bool(cache_hit),
        "video_id": cached_info.get("id") if isinstance(cached_info, dict) else None,
        "format_index": index,
        "selected_format_id": str(format_id),
        "cached_format_ids": [str(f.get("format_id")) for f in formats if isinstance(f, dict)][:60],
        "format": {
            "ext": (selected_format or {}).get("ext"),
            "protocol": (selected_format or {}).get("protocol"),
            "media_host": _endpoint(media_url),
            "filesize": _number((selected_format or {}).get("filesize")),
            "filesize_approx": _number((selected_format or {}).get("filesize_approx")),
            "has_drm": bool((selected_format or {}).get("has_drm", False)),
        } if selected_format else None,
        "transfer": dict(progress),
        "xhr_requests": _xhr_diagnostics(),
        "output_files": files,
        "exception_type": type(error).__name__,
        "exception": _safe_error(error),
        "nested_errors": _nested_errors(error),
    }
    return " | YTDLP_DEBUG=" + json.dumps(report, ensure_ascii=True, separators=(",", ":"))


for _i, _fid in enumerate(FORMAT_IDS):
    _cached_info = globals().get("_CACHED_INFO")
    _cache_hit = globals().get("_CACHED_INFO_URL") == TARGET_URL and isinstance(_cached_info, dict)
    _formats = _cached_info.get("formats", []) if _cache_hit else []
    _selected_format = next((f for f in _formats if isinstance(f, dict) and str(f.get("format_id")) == str(_fid)), None)
    _progress_state = {
        "events": 0,
        "last_status": "not_started",
        "downloaded_bytes": 0,
        "total_bytes": 0,
        "fragment_index": None,
    }
    try:
        with yt_dlp.YoutubeDL({
            "outtmpl": "/mnt/dl/%(id)s.f%(format_id)s.%(ext)s",
            "format": _fid,
            "quiet": True,
            "noprogress": True,
            "postprocessors": [],  # no merger / post-processing
            "progress_hooks": [_progress_hook(_i, _progress_state)],
        }) as ydl:
            if _cache_hit:
                if _selected_format is None:
                    raise RuntimeError(f"Format {_fid} is no longer in the listed formats. Fetch formats again and retry.")
                # The listing already ran yt-dlp's video processing and format-ID
                # normalization. Do not run its selector on that processed table again.
                _download_info = copy.deepcopy(_cached_info)
                _download_info.update(copy.deepcopy(_selected_format))
                for _stale_key in ("requested_formats", "requested_downloads", "__files_to_merge"):
                    _download_info.pop(_stale_key, None)
                _download_info["format_id"] = str(_fid)
                _download_info["format"] = _selected_format.get("format") or str(_fid)
                _progress_state["last_status"] = "process_info_started"
                ydl.process_info(_download_info)
                _progress_state["last_status"] = "process_info_returned"
            else:
                # Compatibility fallback for callers that did not run list_formats.py.
                _progress_state["last_status"] = "fresh_extract_started"
                ydl.download([TARGET_URL])
                _progress_state["last_status"] = "fresh_extract_returned"
    except Exception as _error:
        _message = _safe_error(_error) or type(_error).__name__
        _message += _debug_report(_error, _i, _fid, _cache_hit, _cached_info, _selected_format, _progress_state)
        raise RuntimeError(_message) from None
