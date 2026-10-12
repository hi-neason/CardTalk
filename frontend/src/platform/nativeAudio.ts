// #ifdef APP-HARMONY
// @ts-expect-error UTS modules are resolved by the native compiler.
import { getTemporaryDirectory } from '@/uni_modules/cardtalk-tts';
// #endif
/** Ephemeral native audio files. Dispose even when cancelled while preparation was pending. */
export interface NativeAudioFile { src: string; dispose(): void }
let nextFile = 0;
export async function prepareNativeAudio(bytes: ArrayBuffer): Promise<NativeAudioFile> {
  const name = `cardtalk-speech-${Date.now()}-${++nextFile}.mp3`;
  // #ifdef APP-PLUS
  return new Promise((resolve, reject) => {
    if (typeof plus === 'undefined') { reject(new Error('原生文件服务尚未就绪')); return; }
    const expected = uni.arrayBufferToBase64(bytes);
    let settled = false;
    let delivered = false;
    let removeFile = () => {};
    const fail = () => { if (delivered) return; removeFile(); if (settled) return; settled = true; clearTimeout(timer); reject(new Error('设备无法安全保存语音文件，请使用文字展示')); };
    const timer = setTimeout(fail, 15000);
    plus.io.requestFileSystem(plus.io.PRIVATE_DOC, fs => {
      if (settled) return;
      if (!fs.root) { fail(); return; }
      fs.root.getFile(name, { create: true, exclusive: true }, entry => {
        removeFile = () => entry.remove(() => {}, () => {});
        if (settled) { if (!delivered) removeFile(); return; }
        entry.createWriter(writer => {
          if (settled) { if (!delivered) removeFile(); return; }
          if (typeof writer.writeAsBinary !== 'function') { fail(); return; }
          writer.onerror = fail;
          writer.onwrite = () => {
            if (settled) { if (!delivered) removeFile(); return; }
            entry.file(file => {
              if (settled) { if (!delivered) removeFile(); return; }
              // Some runtimes expose this method but write Base64 text. Verify actual bytes before playing.
              if (file.size !== bytes.byteLength) { fail(); return; }
              const reader = new plus.io.FileReader();
              reader.onerror = fail;
              reader.onloadend = () => {
                if (settled) { if (!delivered) removeFile(); return; }
                if (typeof reader.result !== 'string' || reader.result.split(',')[1] !== expected) { fail(); return; }
                settled = true; delivered = true; clearTimeout(timer);
                resolve({ src: entry.toLocalURL(), dispose: removeFile });
              };
              try { reader.readAsDataURL(file); } catch { fail(); }
            }, fail);
          };
          try { writer.writeAsBinary(expected); } catch { fail(); }
        }, fail);
      }, fail);
    }, fail);
  });
  // #endif
  // #ifdef APP-HARMONY
  const fs = uni.getFileSystemManager();
  const root = getTemporaryDirectory();
  if (!root) throw new Error('设备私有文件目录不可用');
  const src = `${root}/${name}`;
  try { fs.writeFileSync(src, bytes); }
  catch { try { fs.unlinkSync(src); } catch { /* No file was committed. */ } throw new Error('无法保存临时语音文件'); }
  return { src, dispose: () => { try { fs.unlinkSync(src); } catch { /* Already removed. */ } } };
  // #endif
  throw new Error('当前平台不使用原生语音文件');
}
