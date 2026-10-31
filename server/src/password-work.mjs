// Each unchanged scrypt derivation needs about 128 MiB of native memory.
// Keep both active allocations and waiting requests bounded per instance,
// independently of the shared libuv pool size used by other Node operations.
export function createPasswordWork(derive) {
  let active = 0;
  const waiting = [];
  return async (...args) => {
    if (active >= 2) {
      if (waiting.length >= 8) {
        throw Object.assign(new Error('Password service is busy. Try again shortly.'), { status: 503 });
      }
      await new Promise(resolve => waiting.push(resolve));
    } else active++;
    try { return await derive(...args); }
    finally {
      const next = waiting.shift();
      // Transfer the occupied slot directly to the oldest waiter. A newly
      // arriving request cannot take it before the waiter resumes.
      if (next) next();
      else active--;
    }
  };
}
