import { createHmac, timingSafeEqual } from "node:crypto";
import { isIP } from "node:net";

/**
 * Client IPs cannot be trusted from arbitrary caller headers on a public Neon URL.
 * Optionally accept a short-lived HMAC from an independently trusted ingress which
 * derives the real IP itself. All unsigned/spoofed requests retain the shared budget.
 */
function authenticatedClientIp(request, url, secret, now = Date.now()) {
  if (!secret) return null;
  if (secret.length < 32) throw new Error("RATE_LIMIT_IP_SIGNATURE_KEY must be at least 32 characters");
  const ip = request.headers.get("x-roadconquest-client-ip");
  const time = request.headers.get("x-roadconquest-client-timestamp");
  const signature = request.headers.get("x-roadconquest-client-signature");
  if (!ip || !isIP(ip) || !/^\d{13}$/u.test(time || "") ||
      Math.abs(now - Number(time)) > 60_000 || !/^[a-f0-9]{64}$/u.test(signature || "")) return null;
  const expected = createHmac("sha256", secret)
    .update(`${request.method}\n${url.pathname}${url.search}\n${ip}\n${time}`, "utf8")
    .digest();
  return timingSafeEqual(expected, Buffer.from(signature, "hex")) ? ip : null;
}

/** Adapt the account API to a platform Fetch handler without trusting client proxy headers. */
export function createFetchHandler(serve, { rateLimitIpSignatureKey = process.env.RATE_LIMIT_IP_SIGNATURE_KEY } = {}) {
  return async function fetch(request) {
    const url = new URL(request.url);
    if (url.protocol !== "https:") {
      return new Response(JSON.stringify({ error: "HTTPS required" }), {
        status: 400,
        headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
      });
    }
    const headers = Object.fromEntries(request.headers);
    // Ordinary forwarded headers are caller-controlled. Only an HMAC verified
    // above can supply a separate rate-limit key for this public Function.
    delete headers["x-forwarded-for"];
    delete headers["x-real-ip"];
    delete headers["x-roadconquest-client-ip"];
    delete headers["x-roadconquest-client-timestamp"];
    delete headers["x-roadconquest-client-signature"];
    headers["x-forwarded-proto"] = "https";
    const req = {
      method: request.method,
      url: url.pathname + url.search,
      headers,
      // Without a trusted ingress, anonymous requests deliberately share a
      // bounded budget instead of trusting any caller-supplied IP.
      socket: { remoteAddress: authenticatedClientIp(request, url, rateLimitIpSignatureKey) || "function-anonymous" },
      async *[Symbol.asyncIterator]() {
        if (request.body) yield* request.body;
      }
    };
    return new Promise((resolve, reject) => {
      const res = {
        headersSent: false,
        writeHead(status, responseHeaders) {
          this.status = status;
          this.headers = responseHeaders;
          this.headersSent = true;
        },
        end(body) { resolve(new Response(body, { status: this.status, headers: this.headers })); },
        destroy() { reject(new Error("Account response failed.")); }
      };
      try { Promise.resolve(serve(req, res)).catch(reject); } catch (error) { reject(error); }
    });
  };
}
