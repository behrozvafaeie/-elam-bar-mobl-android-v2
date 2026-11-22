Deno.serve(async (_req: Request) => {
  const manifestUrl = "https://sckcxtceybwtiecyxqsy.supabase.co/storage/v1/object/public/app-releases/manifest.json";
  const emptyManifest = { versionCode: 0, versionName: "", apkUrl: "", archiveSha256: "", apkSha256: "" };
  try {
    const response = await fetch(manifestUrl, { headers: { "Cache-Control": "no-cache" } });
    if (!response.ok) {
      return new Response(JSON.stringify(emptyManifest), {
        status: 200,
        headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
      });
    }
    const data = await response.json();
    const versionCode = Number(data.versionCode ?? 0);
    const apkUrl = String(data.apkUrl ?? "");
    const archiveSha256 = String(data.archiveSha256 ?? "");
    const apkSha256 = String(data.apkSha256 ?? "");
    if (
      !Number.isSafeInteger(versionCode) ||
      versionCode < 1 ||
      !apkUrl.startsWith("https://") ||
      !/^[a-f0-9]{64}$/i.test(archiveSha256) ||
      !/^[a-f0-9]{64}$/i.test(apkSha256)
    ) throw new Error("Invalid release manifest");
    return new Response(JSON.stringify(data), {
      status: 200,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  } catch (_error) {
    return new Response(JSON.stringify(emptyManifest), {
      status: 200,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
});