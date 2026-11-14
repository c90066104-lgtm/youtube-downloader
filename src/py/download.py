import copy, os, shutil, yt_dlp

# yt-dlp only fetches raw streams here; it never merges (merging is done in the
# browser with ffmpeg.wasm). Each format ID is downloaded in its own call so the
# output is deterministic: /mnt/dl/<video_id>.f<format_id>.<ext>
# REPORT_PROGRESS(index, downloaded_bytes, total_bytes, finished) is a JS callback.
shutil.rmtree("/mnt/dl", ignore_errors=True)
os.makedirs("/mnt/dl")


def _progress_hook(index):
    def hook(d):
        if d["status"] in ("downloading", "finished"):
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            REPORT_PROGRESS(index, d.get("downloaded_bytes") or 0, int(total), d["status"] == "finished")
    return hook


for _i, _fid in enumerate(FORMAT_IDS):
    with yt_dlp.YoutubeDL({
        "outtmpl": "/mnt/dl/%(id)s.f%(format_id)s.%(ext)s",
        "format": _fid,
        "quiet": True,
        "noprogress": True,
        "postprocessors": [],  # no merger / post-processing
        "progress_hooks": [_progress_hook(_i)],
    }) as ydl:
        if globals().get("_CACHED_INFO_URL") == TARGET_URL and isinstance(globals().get("_CACHED_INFO"), dict):
            _cached_info = globals()["_CACHED_INFO"]
            _selected_format = next((f for f in _cached_info.get("formats", [])
                                     if str(f.get("format_id")) == str(_fid)), None)
            if _selected_format is None:
                raise RuntimeError(f"Format {_fid} is no longer in the listed formats. Fetch formats again and retry.")
            # The listing already ran yt-dlp's video processing and format-ID
            # normalization. Do not re-run its selector on that processed table:
            # merge the exact selected stream into the video metadata and download
            # it directly, avoiding a second "Requested format is not available".
            _download_info = copy.deepcopy(_cached_info)
            _download_info.update(copy.deepcopy(_selected_format))
            for _stale_key in ("requested_formats", "requested_downloads", "__files_to_merge"):
                _download_info.pop(_stale_key, None)
            _download_info["format_id"] = str(_fid)
            _download_info["format"] = _selected_format.get("format") or str(_fid)
            ydl.process_info(_download_info)
        else:
            # Compatibility fallback for callers that did not run list_formats.py.
            ydl.download([TARGET_URL])
