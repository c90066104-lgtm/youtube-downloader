import { Check, CircleAlert, Copy } from "lucide-react";
import { useState } from "react";

const DEBUG_MARKER = " | YTDLP_DEBUG=";

export function ErrorAlert({ message }: { message: string }) {
  const [copied, setCopied] = useState(false);
  const marker = message.lastIndexOf(DEBUG_MARKER);
  const summary = marker >= 0 ? message.slice(0, marker) : message;
  const debug = marker >= 0 ? message.slice(marker + DEBUG_MARKER.length) : "";

  const copyDetails = async () => {
    let success = false;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(message);
        success = true;
      }
    } catch {
      // Use the legacy WebView clipboard fallback below.
    }
    if (!success) {
      const field = document.createElement("textarea");
      field.value = message;
      field.setAttribute("readonly", "");
      field.style.position = "fixed";
      field.style.opacity = "0";
      document.body.appendChild(field);
      field.select();
      success = document.execCommand("copy");
      field.remove();
    }
    setCopied(success);
    if (success) window.setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div role="alert" className="flex gap-2.5 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-200">
      <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" aria-hidden="true" />
      <div className="min-w-0 flex-1 space-y-2">
        <p className="whitespace-pre-wrap break-words">{summary}</p>
        {debug && (
          <details className="text-xs text-rose-200/80">
            <summary className="cursor-pointer">Technical diagnostic report</summary>
            <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-black/20 p-2">{debug}</pre>
          </details>
        )}
        <button
          type="button"
          onClick={() => void copyDetails()}
          className="inline-flex items-center gap-1.5 rounded-lg border border-rose-300/30 px-2.5 py-1.5 text-xs font-medium text-rose-100 hover:bg-rose-300/10"
        >
          {copied ? <Check className="h-3.5 w-3.5" aria-hidden="true" /> : <Copy className="h-3.5 w-3.5" aria-hidden="true" />}
          {copied ? "Copied" : "Copy error + diagnostics"}
        </button>
      </div>
    </div>
  );
}
