import type { PyodideAPI } from "pyodide";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { loadTestPyodide, pyJson } from "../test/python";
import bootstrapPy from "./ytdlp_bootstrap.py?raw";

/** Records what reaches the browser's real XMLHttpRequest after the bootstrap patches it. */
class FakeXHR {
  opened: unknown[] = [];
  headers: [string, string][] = [];
  status = 206;
  statusText = "Partial Content";
  response = new Uint8Array([1, 2, 3, 4]);
  private listeners = new Map<string, (event: { currentTarget: FakeXHR; type: string }) => void>();
  open(...args: unknown[]) {
    this.opened = args;
  }
  setRequestHeader(name: string, value: string) {
    this.headers.push([name, value]);
  }
  addEventListener(name: string, listener: unknown) {
    this.listeners.set(name, listener as (event: { currentTarget: FakeXHR; type: string }) => void);
  }
  getResponseHeader(name: string) {
    return name.toLowerCase() === "content-length" ? "4" : name.toLowerCase() === "content-range" ? "bytes 0-3/4" : null;
  }
  finish(type = "load") {
    this.listeners.get(type)?.({ currentTarget: this, type });
  }
}

declare global {
  // eslint-disable-next-line no-var
  var __ejs_location: unknown;
}

let py: PyodideAPI;
let proxyAfterBootstrap: unknown;
const DEFAULT_PROXY = "https://cf-proxy.eidoriantan.com/";
const RUNTIME_PROXY = "http://127.0.0.1:54321/";
const CONFIG_START = bootstrapPy.indexOf("_DEFAULT_CORS_PROXY = ");
const CONFIG_END = bootstrapPy.indexOf("\n\n_FORBIDDEN_HEADERS", CONFIG_START);
const proxyConfig = bootstrapPy.slice(CONFIG_START, CONFIG_END);

// The bootstrap patches the global XMLHttpRequest, so it runs once per file, like in the worker.
beforeAll(async () => {
  (globalThis as Record<string, unknown>).XMLHttpRequest = FakeXHR;
  py = await loadTestPyodide();
  (globalThis as Record<string, unknown>).YTDLP_CORS_PROXY = RUNTIME_PROXY;
  py.globals.set("CORS_PROXY", "http://set-before-bootstrap");
  await py.runPythonAsync(bootstrapPy);
  proxyAfterBootstrap = py.globals.get("CORS_PROXY");
});

beforeEach(() => {
  py.globals.set("CORS_PROXY", DEFAULT_PROXY);
});

function open(...args: unknown[]) {
  const xhr = new FakeXHR();
  (xhr.open as (...a: unknown[]) => void)(...args);
  return xhr.opened;
}

describe("proxying requests", () => {
  it("routes absolute http(s) URLs through the proxy with the target URL-encoded", () => {
    const target = "https://www.youtube.com/watch?v=jNQXAC9IVRw&hl=en";
    expect(open("GET", target, false)[1]).toBe(`${DEFAULT_PROXY}?url=${encodeURIComponent(target)}`);
    expect(open("POST", "http://example.com/a b", true)[1]).toBe(`${DEFAULT_PROXY}?url=http%3A%2F%2Fexample.com%2Fa+b`);
  });

  it("records response status and byte counts without retaining signed query data", () => {
    const globals = globalThis as Record<string, unknown>;
    globals.__YTDLP_XHR_DIAG = [];
    const xhr = new FakeXHR();
    (xhr.open as (...args: unknown[]) => void)("GET", "https://manifest.googlevideo.com/api/manifest/hls_playlist?signature=secret", true);
    xhr.setRequestHeader("Range", "bytes=0-3");
    xhr.finish();
    const entries = globals.__YTDLP_XHR_DIAG as Record<string, unknown>[];
    expect(entries.at(-1)).toEqual({
      method: "GET", event: "load", host: "manifest.googlevideo.com", kind: "manifest", status: 206,
      status_text: "Partial Content", range: "bytes=0-3", bytes: 4,
      ready_state: 0,
      content_length: "4", content_range: "bytes 0-3/4",
    });
    expect(JSON.stringify(entries)).not.toContain("signature=secret");
  });

  it("records failed XHR terminal events without retaining signed query data", () => {
    const globals = globalThis as Record<string, unknown>;
    globals.__YTDLP_XHR_DIAG = [];
    const xhr = new FakeXHR();
    xhr.status = 0;
    xhr.statusText = "";
    (xhr.open as (...args: unknown[]) => void)("GET", "https://rr6.googlevideo.com/videoplayback?signature=secret", true);
    xhr.finish("error");
    const entries = globals.__YTDLP_XHR_DIAG as Record<string, unknown>[];
    expect(entries.at(-1)).toMatchObject({
      method: "GET", event: "error", host: "rr6.googlevideo.com", kind: "media", status: 0,
    });
    expect(JSON.stringify(entries)).not.toContain("signature=secret");
  });

  it("keeps the method and async flag", () => {
    expect(open("POST", "https://x.test/", false).slice(0, 3)[0]).toBe("POST");
    expect(open("GET", "https://x.test/", false)[2]).toBe(false);
    expect(open("GET", "https://x.test/", true)[2]).toBe(true);
  });

  it("leaves relative, blob and data URLs alone", () => {
    expect(open("GET", "/local/file", true)[1]).toBe("/local/file");
    expect(open("GET", "blob:https://app/123", true)[1]).toBe("blob:https://app/123");
    expect(open("GET", "data:text/plain,hi", true)[1]).toBe("data:text/plain,hi");
  });

  it("uses the proxy the worker publishes after the bootstrap has run", () => {
    // pyodide.worker.ts sets this global whenever the user changes the Proxy URL field.
    py.globals.set("CORS_PROXY", "http://localhost:8787");
    expect(open("GET", "https://www.youtube.com/", true)[1]).toBe(
      "http://localhost:8787?url=https%3A%2F%2Fwww.youtube.com%2F",
    );
  });

  it("prefers the runtime proxy published by the Android worker before bootstrap", () => {
    expect(proxyAfterBootstrap).toBe(RUNTIME_PROXY);
  });

  it("falls back to the shared cf-proxy when no runtime proxy is configured", async () => {
    const globals = globalThis as Record<string, unknown>;
    try {
      globals.YTDLP_CORS_PROXY = "";
      py.globals.set("CORS_PROXY", "http://stale-value");
      await py.runPythonAsync(proxyConfig);
      expect(py.globals.get("CORS_PROXY")).toBe(DEFAULT_PROXY);
    } finally {
      globals.YTDLP_CORS_PROXY = RUNTIME_PROXY;
      py.globals.set("CORS_PROXY", RUNTIME_PROXY);
    }
  });
});

describe("request headers", () => {
  function send(name: string, value = "v") {
    const xhr = new FakeXHR();
    xhr.setRequestHeader(name, value);
    return xhr.headers[0];
  }

  it.each([
    "Cookie", "cookie", "Origin", "Referer", "Host", "Accept-Encoding",
    "Content-Length", "Connection", "Sec-Fetch-Mode", "sec-ch-ua", "Proxy-Authorization",
  ])("renames the browser-forbidden header %s to X-Proxy-*", (name) => {
    expect(send(name, "abc")).toEqual([`X-Proxy-${name}`, "abc"]);
  });

  it.each(["User-Agent", "Accept", "Accept-Language", "Content-Type", "X-Youtube-Client-Name", "Authorization"])(
    "passes %s through unchanged",
    (name) => {
      expect(send(name)).toEqual([name, "v"]);
    },
  );
});

describe("setup", () => {
  it("patches requests and urllib via pyodide-http", async () => {
    expect(await pyJson(py, "import pyodide_http\npyodide_http.PATCHED")).toBe(true);
  });

  it("registers the Pyodide JS challenge provider with top preference", async () => {
    expect(await pyJson(py, `
from yt_dlp.extractor.youtube.jsc import provider as p
[[c.__name__ for c in p.PROVIDERS], [[c.__name__ for c in ps] + [fn(None, [])] for ps, fn in p.PREFERENCES]]`))
      .toEqual([["PyodideJCP"], [["PyodideJCP", 1000]]]);
    expect(await pyJson(py, "p = PyodideJCP()\n[p.is_available(), p.PROVIDER_NAME, [t.value for t in p._SUPPORTED_TYPES]]"))
      .toEqual([true, "pyodide", ["n", "sig"]]);
  });
});

describe("PyodideJCP challenge solving", () => {
  /** A fake solver: answers each challenge with "<player>:<type>:<reversed challenge>". */
  const CORE = `
    globalThis.location = "core wrote this";
    var jsc = (d) => ({
      type: "result",
      responses: d.requests.map((r) => r.challenges.includes("bad")
        ? { type: "error", error: "cannot solve" }
        : { type: "result", data: Object.fromEntries(r.challenges.map((c) => [c, d.player + ":" + r.type + ":" + [...c].reverse().join("")])) }),
    });`;

  beforeEach(() => {
    py.globals.set("_CORE", CORE);
    py.runPython(`
import yt_dlp_ejs.yt.solver as s
from yt_dlp.extractor.youtube.jsc import provider as p
s.LIB = "var lib = { fromLib: 1 };"
s.CORE = _CORE
p.PLAYERS.clear(); p.PLAYER_CALLS.clear()
p.PLAYERS.update({"https://yt/p1.js": "P1", "https://yt/p2.js": "P2"})

from types import SimpleNamespace as NS
def req(t, player, *challenges, video_id="vid"):
    return NS(type=p.JsChallengeType(t), video_id=video_id, input=NS(player_url=player, challenges=list(challenges)))

def solve(*requests):
    out = []
    for r in PyodideJCP()._real_bulk_solve(list(requests)):
        out.append({"challenges": r.request.input.challenges, "error": r.error,
                    "kind": type(r.response.output).__name__ if r.response else None,
                    "results": r.response.output.results if r.response else None})
    return out
`);
  });

  it("solves n and sig challenges with the browser's JS engine", async () => {
    expect(await pyJson(py, `solve(req("n", "https://yt/p1.js", "abc", "xy"), req("sig", "https://yt/p1.js", "123"))`))
      .toEqual([
        { challenges: ["abc", "xy"], error: null, kind: "NChallengeOutput", results: { abc: "P1:n:cba", xy: "P1:n:yx" } },
        { challenges: ["123"], error: null, kind: "SigChallengeOutput", results: { 123: "P1:sig:321" } },
      ]);
  });

  it("fetches each player once and solves its requests together", async () => {
    const out = await pyJson<{ results: Record<string, string> }[]>(py, `
solve(req("n", "https://yt/p1.js", "a"), req("n", "https://yt/p2.js", "b", video_id="v2"), req("sig", "https://yt/p1.js", "c"))`);
    expect(out.map((o) => o.results)).toEqual([{ a: "P1:n:a" }, { c: "P1:sig:c" }, { b: "P2:n:b" }]);
    expect(await pyJson(py, "p.PLAYER_CALLS")).toEqual([["vid", "https://yt/p1.js"], ["v2", "https://yt/p2.js"]]);
  });

  it("exposes the solver lib globally and keeps it from overwriting globalThis.location", async () => {
    await pyJson(py, `solve(req("n", "https://yt/p1.js", "a"))`);
    expect((globalThis as Record<string, unknown>).fromLib).toBe(1);
    expect(globalThis.__ejs_location).toBe("core wrote this");
    expect("location" in globalThis).toBe(false);
  });

  it("returns per-request errors from the solver", async () => {
    expect(await pyJson(py, `solve(req("n", "https://yt/p1.js", "bad"), req("n", "https://yt/p1.js", "ok"))`)).toEqual([
      { challenges: ["bad"], error: "cannot solve", kind: null, results: null },
      { challenges: ["ok"], error: null, kind: "NChallengeOutput", results: { ok: "P1:n:ko" } },
    ]);
  });

  it("raises a provider error when the whole solve fails", async () => {
    py.runPython(`s.CORE = "var jsc = () => ({ type: 'error', error: 'player not supported' });"`);
    await expect(pyJson(py, `solve(req("n", "https://yt/p1.js", "a"))`)).rejects.toThrow(
      /JsChallengeProviderError: player not supported/,
    );
  });

  it("raises a provider error when the solver JS throws", async () => {
    py.runPython(`s.CORE = "var jsc = () => { throw new Error('syntax trouble'); };"`);
    await expect(pyJson(py, `solve(req("n", "https://yt/p1.js", "a"))`)).rejects.toThrow(
      /JsChallengeProviderError: Error evaluating challenge solver JS: .*syntax trouble/,
    );
  });
});
