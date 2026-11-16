package ir.elambar.mobl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val NEARBY_URL = "https://sckcxtceybwtiecyxqsy.supabase.co"
private const val NEARBY_KEY = "sb_publishable_rcJe7tZqCkHPgnAXZffzUg_f9ixVbwg"
private data class NearbyLoad(val id: String, val destination: String, val cargo: String, val vehicle: String, val loadingType: String, val fare: String, val description: String, val distanceKm: Double)

@Composable
internal fun NearbyLoadsScreen(accessToken: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var radiusKm by remember { mutableIntStateOf(50) }
    var items by remember { mutableStateOf<List<NearbyLoad>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("برای دیدن بارهای نزدیک، موقعیت گوشی را فعال کنید.") }
    var locationLabel by remember { mutableStateOf("") }

    fun locateAndSearch() {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { status = "برای محاسبه فاصله بارها، اجازه موقعیت مکانی لازم است."; return }
        busy = true
        status = "در حال دریافت موقعیت گوشی..."
        requestNearbyLocation(context) { location ->
            if (location == null) {
                busy = false
                status = "موقعیت فعلی دریافت نشد. GPS یا مکان‌یابی شبکه را روشن کنید و دوباره تلاش کنید."
            } else {
                locationLabel = String.format(Locale.US, "%.4f, %.4f", location.latitude, location.longitude)
                scope.launch {
                    val result = withContext(Dispatchers.IO) { fetchNearbyLoads(accessToken, location, radiusKm) }
                    items = result.second
                    status = result.first
                    busy = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locateAndSearch()
        else status = "اجازه موقعیت داده نشد؛ برای جست‌وجوی بار نزدیک باید دسترسی مکان را فعال کنید."
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("بارهای اطراف من", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("برگشت") }
                }
                Text("فاصله بر اساس مختصات مقصد بار محاسبه می‌شود. موقعیت گوشی فقط روی دستگاه استفاده می‌شود و به سرور ارسال نمی‌شود.", style = MaterialTheme.typography.bodySmall)
                Text("شعاع جست‌وجو")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(25, 50, 100, 250).forEach { value ->
                        FilterChip(selected = radiusKm == value, onClick = { radiusKm = value }, label = { Text(value.toString() + " کیلومتر") })
                    }
                }
                Button(onClick = {
                    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (fine || coarse) locateAndSearch()
                    else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "در حال جست‌وجو..." else "دریافت موقعیت و جست‌وجوی بار نزدیک")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (locationLabel.isNotBlank()) Text("موقعیت دریافت‌شده: " + locationLabel, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (items.isEmpty()) Text("هنوز بار نزدیکِ دارای مختصات مقصد پیدا نشده است.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    items.forEach { load ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("تهران ← " + load.destination, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Text(String.format(Locale.US, "%.1f km", load.distanceKm), color = MaterialTheme.colorScheme.primary)
                                }
                                Text(load.cargo + " | " + load.vehicle + " | " + load.loadingType, style = MaterialTheme.typography.bodySmall)
                                Text(if (load.fare == "0") "کرایه: توافقی" else "کرایه: " + load.fare + " تومان")
                                if (load.description.isNotBlank()) Text(load.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun requestNearbyLocation(context: Context, onResult: (Location?) -> Unit) {
    try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true)
        val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time < 10 * 60 * 1000L) { onResult(cached); return }
        val provider = when {
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) { onResult(null); return }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { runCatching { manager.removeUpdates(this) }; onResult(location) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { runCatching { manager.removeUpdates(this) }; onResult(null) }
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    } catch (_: SecurityException) { onResult(null) }
    catch (_: Exception) { onResult(null) }
}

private fun fetchNearbyLoads(token: String, location: Location, radiusKm: Int): Pair<String, List<NearbyLoad>> {
    if (token.isBlank()) return "نشست ورود معتبر نیست." to emptyList()
    var connection: HttpURLConnection? = null
    return try {
        val url = "$NEARBY_URL/rest/v1/loads?select=id,destination,cargo_type,vehicle_type,loading_type,freight_amount,description,destination_latitude,destination_longitude&status=eq.published&destination_latitude=not.is.null&destination_longitude=not.is.null&order=created_at.desc&limit=300"
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("apikey", NEARBY_KEY); setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return "دریافت بارهای نزدیک ممکن نشد (کد " + code + ")." to emptyList()
        val rows = JSONArray(raw)
        val found = mutableListOf<NearbyLoad>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val lat = row.optDouble("destination_latitude", Double.NaN)
            val lon = row.optDouble("destination_longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            val result = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, lat, lon, result)
            val km = result[0] / 1000.0
            if (km <= radiusKm) found.add(NearbyLoad(row.optString("id"), row.optString("destination"), row.optString("cargo_type"), row.optString("vehicle_type"), row.optString("loading_type"), row.optString("freight_amount"), row.optString("description"), km))
        }
        val sorted = found.sortedBy { it.distanceKm }
        (if (sorted.isEmpty()) "در شعاع " + radiusKm + " کیلومتر باری پیدا نشد." else sorted.size.toString() + " بار نزدیک پیدا شد.") to sorted
    } catch (_: Exception) { "ارتباط با فهرست بارها برقرار نشد." to emptyList() }
    finally { connection?.disconnect() }
}
