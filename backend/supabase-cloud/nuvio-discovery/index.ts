// Nuvio server discovery for a Supabase cloud project.
//
// The app's "Connect to custom server" flow fetches `<server url>/.well-known/nuvio`.
// A self-hosted Nuvio backend serves that document from its gateway; on Supabase cloud
// this Edge Function serves it instead. Deploy it as `nuvio` with "Verify JWT" turned
// off, then enter `https://<project-ref>.supabase.co/functions/v1/nuvio` in the app.

// Your project's publishable key (Project Settings -> API Keys). It is meant to be
// public. Leave empty to read it from the NUVIO_PUBLISHABLE_KEY function secret.
const PUBLISHABLE_KEY = "";

Deno.serve(() => {
  const document = {
    version: 1,
    service: "nuvio",
    self_hosted: true,
    backend_url: Deno.env.get("SUPABASE_URL") ?? "",
    publishable_key: PUBLISHABLE_KEY || (Deno.env.get("NUVIO_PUBLISHABLE_KEY") ?? ""),
    // The device-code login needs the tv-login/link web pages of a full self-host
    // install, so sign in with email + password instead.
    capabilities: {
      email_password_auth: true,
      tv_login: false,
    },
  };
  return new Response(JSON.stringify(document), {
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
});
