Deno.serve(async (_req: Request) => {
  const manifestUrl = "https://sckcxtceybwtiecyxqsy.supabase.co/storage/v1/object/public/app-releases/manifest.json";
  try {
    const response = await fetch(manifestUrl, { headers: { "Cache-Control": "no-cache" } });
    if (!response.ok) {
      return new Response(JSON.stringify({ versionCode: 0, versionName: "", apkUrl: "", sha256: "" }), {
        status: 200,
        headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
      });
    }
    const data = await response.json();
    const versionCode = Number(data.versionCode ?? 0);
    const apkUrl = String(data.apkUrl ?? "");
    const sha256 = String(data.sha256 ?? "");
    if (!Number.isSafeInteger(versionCode) || versionCode < 1 || !apkUrl.startsWith("https://") || !/^[a-f0-9]{64}$/i.test(sha256)) {
      throw new Error("Invalid release manifest");
    }
    return new Response(JSON.stringify(data), {
      status: 200,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  } catch (_error) {
    return new Response(JSON.stringify({ versionCode: 0, versionName: "", apkUrl: "", sha256: "" }), {
      status: 200,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
});