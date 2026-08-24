interface Env { API: Fetcher; ASSETS: Fetcher; }

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/v1/") || url.pathname === "/health") {
      const incomingOrigin = request.headers.get("Origin");
      if (incomingOrigin !== null && incomingOrigin !== url.origin) {
        return new Response(JSON.stringify({ error: { code: "FORBIDDEN", message: "请求来源不允许", retryable: false } }), {
          status: 403,
          headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
        });
      }
      const headers = new Headers(request.headers);
      headers.set("Origin", url.origin);
      headers.set("X-Forwarded-Host", url.host);
      return env.API.fetch(new Request(request, { headers }));
    }
    const response = await env.ASSETS.fetch(request);
    const headers = new Headers(response.headers);
    headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
  },
} satisfies ExportedHandler<Env>;
