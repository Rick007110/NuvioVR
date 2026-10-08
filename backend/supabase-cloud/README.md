# Nuvio account backend on a free Supabase project

For development and testing of NuvioVR without paying for a server. Official builds use
the official Nuvio backend instead and need none of this.

The schema comes from the official [Nuvio self-host](https://github.com/NuvioMedia/self-host)
project (Apache-2.0), slightly adapted for Supabase cloud (see the header of `setup.sql`).

## 1. Database

Supabase dashboard → **SQL Editor** → **New query** → paste the whole `setup.sql` → **Run**.

## 2. Discovery function

Supabase dashboard → **Edge Functions** → **Deploy a new function** → **Via Editor**:

1. Name it `nuvio`.
2. Replace the code with `nuvio-discovery/index.ts`, and paste your publishable key into
   `PUBLISHABLE_KEY`.
3. Deploy, then open the function's **Details** and turn **Verify JWT** off.

Check: `https://<project-ref>.supabase.co/functions/v1/nuvio/.well-known/nuvio` shows a
JSON document with your project URL and key.

## 3. Account

Supabase dashboard → **Authentication** → **Users** → **Add user** → **Create new user**,
with **Auto Confirm User** checked.

## 4. In the app

Settings → Account (or the sign-in screen on first launch) → **⋮** (top left) →
**Connect to custom server** → enter `https://<project-ref>.supabase.co/functions/v1/nuvio`
→ **Check server** → **I trust this server**. The app restarts; sign in with the email and
password from step 3.

## Notes

- Free projects pause after a week without activity; resume them from the dashboard.
- Profile avatars are not uploaded (the self-host installer seeds them into Storage);
  profiles still work, just without the built-in avatar images.
