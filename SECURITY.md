# Security baseline — Elaan Bar Mobl

## Version 1.0.10 / build 11 (work branch 228)

This file is an implementation and release gate, not a claim that the backend has already passed a security audit.

### Android client controls
- HTTPS cleartext traffic is disabled in the manifest and network security configuration.
- Android backup is disabled for app data.
- The app must contain only the Supabase publishable/anon client key. Never embed service-role keys, database passwords, SMS provider secrets, signing passwords, or private keys.
- Authentication tokens must remain protected by Android Keystore-backed encryption. Never log tokens, passwords, national IDs, or document URLs.
- Use short-lived signed URLs for private driver documents; never make the document bucket public.
- Validate all user input and bound request sizes/timeouts. Treat every API response as untrusted.

### Mandatory Supabase/server controls before real user data
- Enable and test RLS on every exposed table and Storage bucket.
- Deny access by default. Policies must verify the authenticated user, account status, role, and row ownership.
- Do not rely on client-side role checks, hidden buttons, JWT payload decoding, or UI state for authorization.
- Only server-side trusted functions may grant/revoke roles, approve/reject accounts, suspend users, or perform privileged administration.
- Never ship a service-role key to the Android app. Keep provider credentials in server-side secrets.
- Verify RPC functions use a safe search_path, explicit authorization checks, and least-privilege grants.
- Verify private storage policies and signed URL ownership/expiry.
- Rate-limit login, registration, password recovery, and any SMS/voice endpoints. Use generic auth errors to reduce account enumeration.
- Audit sensitive admin actions and test blocked/pending/approved/rejected states.

### Release gates
1. Build succeeds and APK package/version/signature are verified.
2. Install and launch on a real Android device; exercise driver, company, and super-admin accounts.
3. Test negative authorization cases (cross-user reads/writes, unapproved company publishing, driver admin actions, private document access).
4. Confirm current Supabase schema, RPCs, Edge Functions, RLS and Storage policies match the app. Do not drop or recreate existing tables.
5. Test session refresh, logout/revocation, password reset, offline/network failure, and Persian date/time display.
6. Only after these checks may the build be considered for public release.

### Deferred per product decision
SMS/mobile-number verification and provider activation are intentionally left for the owner's later release phase. The app must not pretend a phone number is verified until the server actually verifies it.
