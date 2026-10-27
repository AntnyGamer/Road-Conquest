import { createHash } from 'node:crypto';
import { GifReader } from 'omggif';
import { PNG } from 'pngjs';
import { boundedJson, EvidenceError } from './scoring.mjs';

export const FORUM_RULES_VERSION = 1;
export const GIF_MAX_BYTES = 2 * 1024 * 1024;
const GIF_HOSTS = new Set(['media.giphy.com', 'i.giphy.com', 'media.tenor.com']);
const REMOVAL_CATEGORIES = ['harassment', 'harassment/threatening', 'hate', 'hate/threatening',
  'sexual', 'sexual/minors', 'violence/graphic', 'illicit/violent', 'self-harm/instructions'];

export function forumGifUrl(value) {
  if (value == null || value === '') return null;
  if (typeof value !== 'string' || value.length > 1000) throw new EvidenceError('Use a direct Giphy or Tenor GIF link.', 400);
  let url;
  try { url = new URL(value); } catch { throw new EvidenceError('Use a direct Giphy or Tenor GIF link.', 400); }
  if (url.href.length > 1000 || url.protocol !== 'https:' || !GIF_HOSTS.has(url.hostname) || url.username || url.password ||
      (url.port && url.port !== '443') || !/\.gif$/i.test(url.pathname) || url.hash || url.href.endsWith('#')) {
    throw new EvidenceError('Use a direct HTTPS GIF link from Giphy or Tenor.', 400);
  }
  return url.href;
}

/** Examine every animation frame in a bounded contact sheet; retain no media in the database. */
export function gifContactSheet(bytes) {
  if (bytes.length > GIF_MAX_BYTES) throw new EvidenceError('GIFs must be 2 MB or smaller.', 400);
  let gif;
  try { gif = new GifReader(bytes); } catch { throw new EvidenceError('This GIF could not be read.', 400); }
  const frames = gif.numFrames();
  const { width, height } = gif;
  if (!frames || frames > 60 || width > 512 || height > 512 || width < 1 || height < 1 ||
      width * height * frames > 8_000_000) {
    throw new EvidenceError('Choose a GIF up to 512 × 512 pixels and 60 frames.', 400);
  }
  const columns = Math.ceil(Math.sqrt(frames));
  const thumb = 128;
  const scale = Math.min(thumb / width, thumb / height);
  const w = Math.max(1, Math.floor(width * scale)), h = Math.max(1, Math.floor(height * scale));
  const sheet = new PNG({ width: columns * thumb, height: Math.ceil(frames / columns) * thumb });
  sheet.data.fill(255);
  const pixels = Buffer.alloc(width * height * 4);
  let previous, restore;
  try {
    for (let index = 0; index < frames; index++) {
      if (previous?.disposal === 2) {
        for (let y = previous.y; y < previous.y + previous.height; y++) {
          pixels.fill(0, (y * width + previous.x) * 4, (y * width + previous.x + previous.width) * 4);
        }
      } else if (previous?.disposal === 3 && restore) restore.copy(pixels);
      const frame = gif.frameInfo(index);
      if (frame.x < 0 || frame.y < 0 || frame.x + frame.width > width || frame.y + frame.height > height) {
        throw new Error('Frame outside image');
      }
      restore = frame.disposal === 3 ? Buffer.from(pixels) : null;
      gif.decodeAndBlitFrameRGBA(index, pixels);
      const left = index % columns * thumb + Math.floor((thumb - w) / 2);
      const top = Math.floor(index / columns) * thumb + Math.floor((thumb - h) / 2);
      for (let y = 0; y < h; y++) {
        const sourceRow = Math.floor(y / scale) * width;
        let target = ((top + y) * sheet.width + left) * 4;
        for (let x = 0; x < w; x++, target += 4) {
          const source = (sourceRow + Math.floor(x / scale)) * 4;
          // GIF pixels are opaque or transparent. The decoder writes alpha 255;
          // transparent samples retain the sheet's original opaque white.
          if (pixels[source + 3]) {
            sheet.data[target] = pixels[source];
            sheet.data[target + 1] = pixels[source + 1];
            sheet.data[target + 2] = pixels[source + 2];
          }
        }
      }
      previous = frame;
    }
  } catch { throw new EvidenceError('This GIF could not be decoded safely.', 400); }
  return PNG.sync.write(sheet, { deflateLevel: 3 });
}

export function createForumModerator(key, request = fetch) {
  if (!key) return null;
  return async ({ text, gifUrl }) => {
    const input = [{ type: 'text', text: text || 'An image shared in the Road Conquest community.' }];
    let gifHash = null;
    if (gifUrl) {
      const url = forumGifUrl(gifUrl);
      let response;
      try {
        response = await request(url, { redirect: 'manual', signal: AbortSignal.timeout(8000) });
      } catch {
        throw new EvidenceError('The GIF provider is temporarily unavailable. Please try again.', 503);
      }
      if (!response.ok || !response.body || !(response.headers.get('Content-Type') || '').toLowerCase().includes('image/gif')) {
        void response.body?.cancel().catch(() => {});
        if (response.status === 408 || response.status === 429 || response.status >= 500) {
          throw new EvidenceError('The GIF provider is temporarily unavailable. Please try again.', 503);
        }
        throw new EvidenceError('The GIF link is unavailable. Choose another GIF.', 400);
      }
      const length = response.headers.get('Content-Length');
      const encoding = response.headers.get('Content-Encoding');
      if ((!encoding || encoding.toLowerCase() === 'identity') && length && /^\d+$/.test(length) && Number(length) > GIF_MAX_BYTES) {
        void response.body.cancel().catch(() => {});
        throw new EvidenceError('GIFs must be 2 MB or smaller.', 400);
      }
      const chunks = []; let size = 0;
      try {
        for await (const chunk of response.body) {
          size += chunk.length;
          if (size > GIF_MAX_BYTES) throw new EvidenceError('GIFs must be 2 MB or smaller.', 400);
          chunks.push(chunk);
        }
      } catch (error) {
        if (error instanceof EvidenceError) throw error;
        throw new EvidenceError('The GIF download was interrupted. Please try again.', 503);
      }
      const bytes = chunks.length === 1
        ? Buffer.from(chunks[0].buffer, chunks[0].byteOffset, chunks[0].byteLength)
        : Buffer.concat(chunks);
      const preview = gifContactSheet(bytes);
      gifHash = createHash('sha256').update(bytes).digest('hex');
      input.push({ type: 'image_url', image_url: { url: 'data:image/png;base64,' + preview.toString('base64') } });
    }
    let result;
    try {
      result = await boundedJson(await request('https://api.openai.com/v1/moderations', {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(12_000),
        headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: 'omni-moderation-latest', input })
      }), 65536);
    } catch { throw new EvidenceError('Moderation is temporarily unavailable. Please try again.', 503); }
    const verdict = result?.results?.[0];
    if (typeof verdict?.flagged !== 'boolean' || !verdict.categories || typeof result.model !== 'string' ||
        REMOVAL_CATEGORIES.some(k => typeof verdict.categories[k] !== 'boolean')) {
      throw new EvidenceError('Moderation is temporarily unavailable. Please try again.', 503);
    }
    const categories = REMOVAL_CATEGORIES.filter(k => verdict.categories[k] === true);
    if (categories.length && !verdict.flagged) {
      throw new EvidenceError('Moderation is temporarily unavailable. Please try again.', 503);
    }
    // Crash/travel discussions and expressions of distress alone are not rule violations.
    return { flagged: categories.length > 0, categories,
      model: result.model, gifHash };
  };
}
