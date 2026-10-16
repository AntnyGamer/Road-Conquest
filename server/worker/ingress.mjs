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
  // Same-zone Worker subrequests can derive CF-Connecting-IP from a mutable
  // x-real-ip header. Do not turn an upstream Worker's claim into our signature.
  // Cloudflare adds CF-Worker to fetch subrequests; an extra caller-supplied
  // marker can only opt down to Neon's anonymous budget, never gain trust.
  if (request.headers.has("CF-Worker")) return null;
  // Only the Cloudflare-injected edge header is eligible. The backend performs
  // the final IPv4/IPv6 parsing and signature verification.
  const ip = request.headers.get("CF-Connecting-IP");
  // Cross-zone Worker subrequests use this shared placeholder, not a real
  // external client's address. Keep those on the anonymous fallback as well.
  if (ip?.toLowerCase() === "2a06:98c0:3600::103") return null;
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
    // A misconfigured Worker must never proxy into itself and loop until
    // Cloudflare exhausts subrequest limits. The real Neon origin differs.
    if (new URL(origin).host === incoming.host) return unavailable();
    if (incoming.protocol !== "https:") {
      return new Response(JSON.stringify({ error: "HTTPS required" }), {
        status: 400, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
      });
    }
    // Never fetch a caller-supplied host: the upstream origin is fixed by env.
    const target = origin + incoming.pathname + incoming.search;
    // Rebuilding a Request copies the caller's ORIGINAL headers. Sanitize
    // that actual forwarded request exactly once before adding signed identity.
    // Preserve the method, body, credentials and query parameters unchanged.
    const forwarded = new Request(target, request);
    for (const name of [...forwarded.headers.keys()]) {
      if (name === "host" || name === "forwarded" || name === "x-real-ip" ||
          name === "true-client-ip" || name === "cf-connecting-ip" ||
          name === "cf-connecting-ipv6" || name === "cf-pseudo-ipv4" ||
          name === "cf-worker" || name.startsWith("x-forwarded-") ||
          name.startsWith("x-roadconquest-client-")) forwarded.headers.delete(name);
    }
    const ip = trustedEdgeIp(request);
    if (ip) {
      const timestamp = String(now());
      const signature = await signedClientIdentity(
        request.method, incoming.pathname + incoming.search, ip, timestamp, key
      );
      forwarded.headers.set("X-RoadConquest-Client-IP", ip);
      forwarded.headers.set("X-RoadConquest-Client-Timestamp", timestamp);
      forwarded.headers.set("X-RoadConquest-Client-Signature", signature);
    }
    // Convert upstream network failures to the same private, predictable JSON
    // response as a missing configuration. Do not surface Worker stack traces
    // or transient CDN error pages to the Android JSON API client.
    let upstream;
    try {
      upstream = await upstreamFetch(new Request(forwarded, { redirect: "manual" }));
    } catch {
      return unavailable();
    }
    const responseHeaders = new Headers(upstream.headers);
    responseHeaders.set("Cache-Control", "no-store");
    return new Response(upstream.body, {
      status: upstream.status, statusText: upstream.statusText, headers: responseHeaders
    });
  };
}

export default { fetch: createAccountIngress() };
