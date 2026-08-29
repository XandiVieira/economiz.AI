// economizai stable-URL reverse proxy.
//
// Gives a PERMANENT public URL (https://economizai.<acct>.workers.dev) that
// always forwards to whatever the current Cloudflare quick-tunnel URL is.
//
// The live tunnel URL is stored in a KV namespace under the key "origin".
// start-tunnel.ps1 writes the new URL there on every restart, so this Worker
// always follows the tunnel without redeploying and the FE never sees a change.
//
// Binding (wrangler.toml): KV namespace bound as TUNNEL.

export default {
  async fetch(request, env) {
    const origin = (await env.TUNNEL.get("origin"))?.trim();
    if (!origin) {
      return new Response(
        "economizai: tunnel origin not set yet. The dev server may be starting up.",
        { status: 503, headers: { "content-type": "text/plain; charset=utf-8" } }
      );
    }

    // Rewrite the incoming URL onto the current tunnel origin, preserving
    // path + query. Forward method, headers, and body unchanged.
    const incoming = new URL(request.url);
    const target = new URL(origin);
    target.pathname = incoming.pathname;
    target.search = incoming.search;

    const proxied = new Request(target.toString(), {
      method: request.method,
      headers: request.headers,
      body: request.body,
      redirect: "manual",
    });
    // Let the origin see the real host it's being proxied for if it cares.
    proxied.headers.set("X-Forwarded-Host", incoming.host);

    return fetch(proxied);
  },
};
