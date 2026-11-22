import { createClient } from "npm:@supabase/supabase-js@2";
import { createRemoteJWKSet, jwtVerify } from "npm:jose@5.9.6";

const projectUrl = Deno.env.get("SUPABASE_URL") ?? "";
const secretMap = Deno.env.get("SUPABASE_SECRET_KEYS");
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? (secretMap ? JSON.parse(secretMap).default ?? "" : "");
if (!projectUrl || !serviceKey) throw new Error("Server configuration unavailable");

const admin = createClient(projectUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
const githubJwks = createRemoteJWKSet(new URL("https://token.actions.githubusercontent.com/.well-known/jwks"));
const expectedRepository = "behrozvafaeie/-elam-bar-mobl-android-v2";
const expectedRef = "refs/heads/fix/load-status-edit-delete-logout-v1";
const expectedWorkflow = expectedRepository + "/.github/workflows/android-apk.yml@" + expectedRef;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const authorization = req.headers.get("authorization") ?? "";
    const token = authorization.replace(/^Bearer\s+/i, "").trim();
    if (!token) return json({ error: "GitHub Actions identity required" }, 401);

    const { payload } = await jwtVerify(token, githubJwks, {
      issuer: "https://token.actions.githubusercontent.com",
      audience: "elambar-release-upload",
    });
    if (
      payload.repository !== expectedRepository ||
      payload.repository_owner !== "behrozvafaeie" ||
      payload.ref !== expectedRef ||
      payload.workflow_ref !== expectedWorkflow ||
      !["push", "workflow_dispatch"].includes(String(payload.event_name ?? ""))
    ) return json({ error: "Workflow identity is not authorized" }, 403);

    const url = new URL(req.url);
    const versionCode = Number(url.searchParams.get("versionCode") ?? 0);
    const versionName = String(url.searchParams.get("versionName") ?? "");
    if (!Number.isSafeInteger(versionCode) || versionCode < 1 || !/^\d+\\.\d+\\.\d+$/.test(versionName)) {
      return json({ error: "Invalid app version" }, 400);
    }
    if (!req.headers.get("content-type")?.toLowerCase().includes("application/vnd.android.package-archive")) {
      return json({ error: "APK content type required" }, 415);
    }

    const bytes = new Uint8Array(await req.arrayBuffer());
    if (bytes.length < 1024 || bytes.length > 100 * 1024 * 1024) return json({ error: "APK size is outside allowed range" }, 413);
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    const sha256 = Array.from(new Uint8Array(digest)).map((v) => v.toString(16).padStart(2, "0")).join("");

    const apkPath = "elam-bar-mobl-latest.apk";
    const apkBlob = new Blob([bytes], { type: "application/vnd.android.package-archive" });
    const { error: apkError } = await admin.storage.from("app-releases").upload(apkPath, apkBlob, {
      contentType: "application/vnd.android.package-archive",
      cacheControl: "300",
      upsert: true,
    });
    if (apkError) return json({ error: "APK upload failed" }, 500);

    const manifest = {
      versionCode,
      versionName,
      apkUrl: projectUrl + "/storage/v1/object/public/app-releases/" + apkPath,
      sha256,
      mandatory: false,
      releaseNotes: "به‌روزرسانی برنامه اعلام بار مبل",
    };
    const manifestBlob = new Blob([JSON.stringify(manifest)], { type: "application/json" });
    const { error: manifestError } = await admin.storage.from("app-releases").upload(
      "manifest.json",
      manifestBlob,
      { contentType: "application/json; charset=utf-8", cacheControl: "0", upsert: true },
    );
    if (manifestError) return json({ error: "Manifest publication failed" }, 500);
    return json({ ok: true, versionCode, versionName, sha256 });
  } catch (_error) {
    return json({ error: "Release upload was rejected or could not be completed" }, 401);
  }
});