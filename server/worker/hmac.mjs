/** Cache only non-extractable signing keys, never request data or signatures. */
export function createHmacSigner(capacity = 1, subtle = crypto.subtle) {
  const keys = new Map();
  const encoder = new TextEncoder();
  return async (secret, message) => {
    let pending = keys.get(secret);
    if (!pending) {
      pending = Promise.resolve().then(() => subtle.importKey('raw', encoder.encode(secret),
        { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']));
      keys.set(secret, pending);
      if (keys.size > capacity) keys.delete(keys.keys().next().value);
      pending.catch(() => { if (keys.get(secret) === pending) keys.delete(secret); });
    }
    return new Uint8Array(await subtle.sign('HMAC', await pending, encoder.encode(message)));
  };
}
