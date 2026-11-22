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

    const body = await req.json();
    const action = String(body.action ?? "");
    const versionCode = Number(body.versionCode ?? 0);
    const versionName = String(body.versionName ?? "");
    if (!Number.isSafeInteger(versionCode) || versionCode < 1 || !/^\d+\.\d+\.\d+$/.test(versionName)) {
      return json({ error: "Invalid app version" }, 400);
    }

    const releasePath = "elam-bar-mobl-latest.zip";
    if (action === "prepare") {
      const { data, error } = await admin.storage.from("app-releases").createSignedUploadUrl(releasePath, { upsert: true });
      if (error || !data?.signedUrl || !data?.token) return json({ error: "Could not create signed APK upload URL" }, 500);
      return json({ signedUrl: data.signedUrl, path: releasePath, expiresIn: 7200 });
    }

    if (action === "finalize") {
      const archiveSha256 = String(body.archiveSha256 ?? "").toLowerCase();
      const apkSha256 = String(body.apkSha256 ?? "").toLowerCase();
      if (!/^[a-f0-9]{64}$/.test(archiveSha256) || !/^[a-f0-9]{64}$/.test(apkSha256)) {
        return json({ error: "Invalid release checksum" }, 400);
      }
      const { data: objects, error: listError } = await admin.storage.from("app-releases").list("", { search: releasePath, limit: 10 });
      if (listError || !objects?.some((item) => item.name === releasePath)) return json({ error: "Uploaded release archive was not found" }, 409);

      const manifest = {
        versionCode,
        versionName,
        apkUrl: projectUrl + "/storage/v1/object/public/app-releases/" + releasePath,
        archiveSha256,
        apkSha256,
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
      return json({ ok: true, versionCode, versionName, archiveSha256, apkSha256 });
    }

    return json({ error: "Unknown action" }, 400);
  } catch (_error) {
    return json({ error: "Release upload was rejected or could not be completed" }, 401);
  }
});