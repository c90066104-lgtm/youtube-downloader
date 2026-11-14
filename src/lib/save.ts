/** Triggers a browser download or streams completed bytes into Android MediaStore. */
export function saveBlob(data: Uint8Array, ext: string, isVideo: boolean, filename: string): void {
  const type = isVideo ? `video/${ext === "mkv" ? "x-matroska" : ext}` : `audio/${ext === "mp3" ? "mpeg" : ext}`;
  const native = window.AndroidDownloader;
  if (native) {
    const id = native.beginSave(filename, type);
    if (!id) throw new Error("O Android não conseguiu iniciar o salvamento do arquivo.");
    try {
      const chunkSize = 48 * 1024;
      for (let offset = 0; offset < data.length; offset += chunkSize) {
        const end = Math.min(offset + chunkSize, data.length);
        let binary = "";
        for (let i = offset; i < end; i++) binary += String.fromCharCode(data[i]);
        native.appendSaveChunk(id, btoa(binary));
      }
      if (!native.finishSave(id)) throw new Error("O Android não confirmou que o arquivo foi salvo.");
      return;
    } catch (error) {
      native.abortSave(id);
      throw error;
    }
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([data as BlobPart], { type }));
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
}
