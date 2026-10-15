/**
 * Road Conquest's optional Cloudflare Workers ingress for its Neon account API.
 *
 * Cloudflare supplies CF-Connecting-IP at the public edge. NEVER accept an IP
 * from X-Forwarded-For, X-Real-IP or caller-supplied X-RoadConquest-* headers.
 * The Neon Fetch adapter verifies the short-lived HMAC before trusting the IP.
 *
 * Configure API_ORIGIN as the exact https://...neon.tech Function URL, and add
 * RATE_LIMIT_IP_SIGNATURE_KEY as a Worker secret matching the Neon Function.
 * This is opt-in deployment code, not used by Android until the endpoint works.
 */

function configuredOrigin(value) {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || !url.hostname || url.username || url.password ||
        url.search || url.hash || (url.pathname !== "/" && url.pathname !== "")) return null;
    return url.origin;
  } catch {
    return null;
  }
}

function trustedEdgeIp(request) {
  // Only the Cloudflare-injected edge header is eligible. The backend performs
  // the final IPv4/IPv6 parsing and signature verification.
  const ip = request.headers.get("CF-Connecting-IP");
  return ip && ip.length <= 45 && /^[0-9a-fA-F:.]+$/.test(ip) &&
    (ip.includes(":") || ip.includes(".")) ? ip : null;
}

async function signedClientIdentity(method, pathAndQuery, ip, timestamp, key) {
  const encoder = new TextEncoder();
  const cryptoKey = await crypto.subtle.importKey(
    "raw", encoder.encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const payload = encoder.encode([method, pathAndQuery, ip, String(timestamp)].join("\n"));
  const digest = new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, payload));
  return Array.from(digest, byte => byte.toString(16).padStart(2, "0")).join("");
}

function unavailable() {
  return new Response(JSON.stringify({ error: "Account service is unavailable." }), {
    status: 503,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

/** Dependency injection permits exact request/signature tests without any live service. */
export function createAccountIngress(upstreamFetch = fetch, now = () => Date.now()) {
  return async function handle(request, env) {
    const origin = configuredOrigin(env?.API_ORIGIN);
    const key = env?.RATE_LIMIT_IP_SIGNATURE_KEY;
    if (!origin || typeof key !== "string" || key.length < 32) return unavailable();
    const incoming = new URL(request.url);
    if (incoming.protocol !== "https:") {
      return new Response(JSON.stringify({ error: "HTTPS required" }), {
        status: 400, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
      });
    }
    // Never fetch a caller-supplied host: the upstream origin is fixed by env.
    const target = origin + incoming.pathname + incoming.search;
    const headers = new Headers(request.headers);
    for (const name of [
      "host", "cf-connecting-ip", "cf-connecting-ipv6", "x-forwarded-for",
      "x-real-ip", "x-roadconquest-client-ip", "x-roadconquest-client-timestamp",
      "x-roadconquest-client-signature"
    ]) headers.delete(name);
    const ip = trustedEdgeIp(request);
    if (ip) {
      const timestamp = String(now());
      const signature = await signedClientIdentity(
        request.method, incoming.pathname + incoming.search, ip, timestamp, key
      );
      headers.set("X-RoadConquest-Client-IP", ip);
      headers.set("X-RoadConquest-Client-Timestamp", timestamp);
      headers.set("X-RoadConquest-Client-Signature", signature);
    }
    // Do not rewrite or buffer request bodies, credentials, methods or query
    // parameters. Disable redirect-following to prevent credential leakage.
    const forwarded = new Request(target, request);
    for (const [name, value] of headers) forwarded.headers.set(name, value);
    for (const name of ["cf-connecting-ip", "cf-connecting-ipv6", "x-forwarded-for",
      "x-real-ip", "host"]) forwarded.headers.delete(name);
    const upstream = await upstreamFetch(new Request(forwarded, { redirect: "manual" }));
    const responseHeaders = new Headers(upstream.headers);
    responseHeaders.set("Cache-Control", "no-store");
    return new Response(upstream.body, {
      status: upstream.status, statusText: upstream.statusText, headers: responseHeaders
    });
  };
}

export default { fetch: createAccountIngress() };
