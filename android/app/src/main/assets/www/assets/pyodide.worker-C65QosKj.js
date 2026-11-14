(function(){let e=`/mnt/dl`,t=``,n=null,r=null,i=Promise.resolve(),a=(e,t=[])=>postMessage(e,{transfer:t}),o=(e,t)=>a({type:`step`,step:e,state:t}),s=e=>e instanceof Error?e.message:String(e);function c(e){globalThis.YTDLP_CORS_PROXY=t||void 0,e?.globals.set(`CORS_PROXY`,t)}function l(){return r??=(async()=>{let e=`runtime`,t=t=>{e=t,o(t,`active`)};try{t(`runtime`);let{loadPyodide:e}=await import(
/* @vite-ignore */
`https://cdn.jsdelivr.net/pyodide/v314.0.7/full/pyodide.mjs`),r=await e();return o(`runtime`,`done`),t(`packages`),await r.loadPackage([`micropip`,`pyodide-http`]),o(`packages`,`done`),t(`ytdlp`),await r.pyimport(`micropip`).install([`yt-dlp`,`yt-dlp-ejs`]),c(r),await r.runPythonAsync(`# Runs yt-dlp in Pyodide with a custom JS challenge provider that uses the browser's JS engine.
from urllib.parse import urlencode

import js
from js import XMLHttpRequest

_original_xhr_open = XMLHttpRequest.prototype.open
_original_xhr_set_header = XMLHttpRequest.prototype.setRequestHeader
CORS_PROXY = "https://cf-proxy.eidoriantan.com/"

_FORBIDDEN_HEADERS = {
    "accept-charset", "accept-encoding", "access-control-request-headers",
    "access-control-request-method", "connection", "content-length", "cookie",
    "cookie2", "date", "dnt", "expect", "host", "keep-alive", "origin", "referer",
    "set-cookie", "te", "trailer", "transfer-encoding", "upgrade", "via",
}
_FORBIDDEN_PREFIXES = ("proxy-", "sec-")


def _is_unsafe_header(name):
    lowered = name.lower()
    return lowered in _FORBIDDEN_HEADERS or lowered.startswith(_FORBIDDEN_PREFIXES)


def proxy_open_handler(xhr_instance, method, url, async_val=True, username=None, password=None):
    if url.startswith("http://") or url.startswith("https://"):
        params = urlencode({"url": url})
        url = f"{CORS_PROXY}?{params}"

    return _original_xhr_open.call(xhr_instance, method, url, async_val, username, password)


def proxy_set_header_handler(xhr_instance, name, value):
    if _is_unsafe_header(name):
        name = f"X-Proxy-{name}"

    return _original_xhr_set_header.call(xhr_instance, name, value)


js.globalThis.python_xhr_proxy_open_handler = proxy_open_handler
js.globalThis.python_xhr_proxy_set_header_handler = proxy_set_header_handler

XMLHttpRequest.prototype.open = js.Function("""
    return function(method, url, async_val, username, password) {
        return globalThis.python_xhr_proxy_open_handler(this, method, url, async_val, username, password);
    };
""")()

XMLHttpRequest.prototype.setRequestHeader = js.Function("""
    return function(name, value) {
        return globalThis.python_xhr_proxy_set_header_handler(this, name, value);
    };
""")()

import pyodide_http
pyodide_http.patch_all()

import json
import yt_dlp

from yt_dlp.extractor.youtube.jsc.provider import (
    JsChallengeProvider,
    JsChallengeProviderError,
    JsChallengeProviderResponse,
    JsChallengeResponse,
    JsChallengeType,
    NChallengeOutput,
    SigChallengeOutput,
    register_preference,
    register_provider,
)

import yt_dlp_ejs.yt.solver as _ejs_solver


def _eval_in_worker_scope(code: str) -> str:
    # This runs inside a dedicated Web Worker, which has no DOM to host a
    # sandbox iframe. Indirect eval runs the solver in the worker's global
    # scope, which nothing else shares.
    return js.eval(code)


@register_provider
class PyodideJCP(JsChallengeProvider):
    """Solves YouTube's n/sig JS challenges using the real browser JS engine."""

    PROVIDER_VERSION = "0.1"
    PROVIDER_NAME = "pyodide"
    BUG_REPORT_LOCATION = "https://github.com/yt-dlp/yt-dlp/issues"

    _SUPPORTED_TYPES = [JsChallengeType.N, JsChallengeType.SIG]

    def is_available(self) -> bool:
        return True

    def _real_bulk_solve(self, requests):
        grouped = {}
        for request in requests:
            grouped.setdefault(request.input.player_url, []).append(request)

        lib_code = _ejs_solver.lib()
        core_code = _ejs_solver.core()

        core_code = core_code.replace(
            "globalThis.location =", "globalThis.__ejs_location =",
        )

        for player_url, grouped_requests in grouped.items():
            video_id = next((r.video_id for r in grouped_requests), None)
            player_js = self._get_player(video_id, player_url)

            self.logger.info("Solving JS challenges using the browser JS engine (Pyodide)")

            data = {
                "type": "player",
                "player": player_js,
                "requests": [
                    {"type": r.type.value, "challenges": r.input.challenges}
                    for r in grouped_requests
                ],
                "output_preprocessed": True,
            }

            program = f"""
            {lib_code}
            Object.assign(globalThis, lib);
            {core_code}
            JSON.stringify(jsc({json.dumps(data)}));
            """

            try:
                stdout = _eval_in_worker_scope(program)
            except Exception as e:
                raise JsChallengeProviderError(f"Error evaluating challenge solver JS: {e}") from e

            output = json.loads(stdout)
            if output["type"] == "error":
                raise JsChallengeProviderError(output["error"])

            for request, response_data in zip(grouped_requests, output["responses"], strict=True):
                if response_data["type"] == "error":
                    yield JsChallengeProviderResponse(request, None, response_data["error"])
                else:
                    yield JsChallengeProviderResponse(
                        request,
                        JsChallengeResponse(
                            request.type,
                            NChallengeOutput(response_data["data"])
                            if request.type is JsChallengeType.N
                            else SigChallengeOutput(response_data["data"]),
                        ),
                    )


@register_preference(PyodideJCP)
def _pyodide_jcp_preference(provider, requests):
    return 1000
`),n=r,o(`ytdlp`,`done`),r}catch(t){throw o(e,`error`),t}})(),r}async function u(e){let t=await l();return t.globals.set(`TARGET_URL`,e),await t.runPythonAsync(`import json, yt_dlp

with yt_dlp.YoutubeDL({"quiet": True, "skip_download": True, "noprogress": True}) as ydl:
    _info = ydl.extract_info(TARGET_URL, download=False)

# Keep yt-dlp's resolved URLs and exact format table in the worker. The download
# step can then select from this same extraction instead of refetching the page.
_CACHED_INFO_URL = TARGET_URL
_CACHED_INFO = _info

_keys = ["format_id", "ext", "resolution", "height", "fps", "vcodec", "acodec",
         "filesize", "filesize_approx", "tbr", "abr", "format_note", "protocol"]
_fmts = [{k: f.get(k) for k in _keys} for f in _info.get("formats", [])
         if f.get("ext") != "mhtml" and (f.get("vcodec") != "none" or f.get("acodec") != "none")]
json.dumps({"id": _info.get("id"), "title": _info.get("title"), "formats": _fmts})
`)}async function d(t,n,r){let i=await l(),o=0;return i.globals.set(`TARGET_URL`,n),i.globals.set(`FORMAT_IDS`,i.toPy(r)),i.globals.set(`REPORT_PROGRESS`,(e,n,r,i)=>{let s=performance.now();!i&&s-o<100||(o=s,a({type:`progress`,id:t,index:e,downloaded:n,total:r}))}),await i.runPythonAsync(`import copy, os, shutil, yt_dlp

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
            if not any(str(f.get("format_id")) == str(_fid) for f in _cached_info.get("formats", [])):
                raise RuntimeError(f"Format { _fid } is no longer in the listed formats. Fetch formats again and retry.")
            # Reuse the extraction that supplied the IDs to the picker. A second
            # YouTube extraction can return a different format table or client set.
            ydl.process_ie_result(copy.deepcopy(_cached_info), download=True)
        else:
            # Compatibility fallback for callers that did not run list_formats.py.
            ydl.download([TARGET_URL])
`),i.FS.readdir(e).filter(e=>e!==`.`&&e!==`..`).map(t=>{let n=i.FS.readFile(`${e}/${t}`);return i.FS.unlink(`${e}/${t}`),{name:t,data:n}})}async function f(e){try{if(e.type===`listFormats`)a({type:`result`,id:e.id,value:await u(e.url)});else{let t=await d(e.id,e.url,e.formatIds);a({type:`result`,id:e.id,value:t},t.map(e=>e.data.buffer))}}catch(t){a({type:`error`,id:e.id,message:s(t)})}}onmessage=e=>{let r=e.data;switch(r.type){case`proxy`:t=r.proxy,c(n);break;case`init`:l().then(()=>a({type:`ready`}),e=>a({type:`initError`,message:s(e)}));break;default:i=i.then(()=>f(r))}}})();