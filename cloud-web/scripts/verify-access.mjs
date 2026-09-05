const host = process.argv[2];
if (!["test.rochelimit.me", "staging.rochelimit.me"].includes(host)) throw new Error("expected an approved management hostname");

// Do not follow redirects: a final 200 may be a login page or accidentally public application.
for (const path of ["/", "/manifest.webmanifest", "/v1/bootstrap"]) {
  let passed = false;
  for (let attempt = 0; attempt < 12; attempt++) {
    try {
      const response = await fetch(`https://${host}${path}`, {
        redirect: "manual", signal: AbortSignal.timeout(15000),
        headers: { "Cf-Access-Authenticated-User-Email": "forged@example.invalid" },
      });
      const location = new URL(response.headers.get("Location") ?? "", `https://${host}`);
      passed = [302, 303].includes(response.status)
        && location.protocol === "https:"
        && /^[a-z0-9-]+\.cloudflareaccess\.com$/.test(location.hostname)
        && location.pathname.startsWith("/cdn-cgi/access/login/");
      await response.body?.cancel();
      if (passed) break;
    } catch { /* bounded retry for DNS/certificate propagation */ }
    if (attempt < 11) await new Promise((resolve) => setTimeout(resolve, 5000));
  }
  if (!passed) throw new Error(`Access did not intercept ${host}${path}`);
}
console.log(`Cloudflare Access intercepts pages, assets and web API on ${host}`);
console.log("This unauthenticated check does not verify a successful administrator login; record that separately.");
