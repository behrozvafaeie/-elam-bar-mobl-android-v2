package ir.elambar.mobl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar

private val jalaliMonths = listOf("فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند")
private val persianWeekDays = listOf("ش","ی","د","س","چ","پ","ج")

private data class JalaliParts(val year: Int, val month: Int, val day: Int)

private fun toPersianDigits(value: String): String = value.map { ch ->
    if (ch in '0'..'9') ('۰'.code + ch.code - '0'.code).toChar() else ch
}.joinToString("")

private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): JalaliParts {
    val monthDays = intArrayOf(0,31,59,90,120,151,181,212,243,273,304,334)
    val gy2 = if (gm > 2) gy + 1 else gy
    var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + monthDays[gm - 1]
    var jy = -1595 + 33 * (days / 12053)
    days %= 12053
    jy += 4 * (days / 1461)
    days %= 1461
    if (days > 365) {
        jy += (days - 1) / 365
        days = (days - 1) % 365
    }
    val jm: Int
    val jd: Int
    if (days < 186) {
        jm = 1 + days / 31
        jd = 1 + days % 31
    } else {
        jm = 7 + (days - 186) / 30
        jd = 1 + (days - 186) % 30
    }
    return JalaliParts(jy, jm, jd)
}

private fun jalaliToGregorian(jyInput: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
    val jy = jyInput + 1595
    var days = -355668 + 365 * jy + (jy / 33) * 8 + ((jy % 33 + 3) / 4) + jd
    days += if (jm < 7) (jm - 1) * 31 else 186 + (jm - 7) * 30
    var gy = 400 * (days / 146097)
    days %= 146097
    if (days > 36524) {
        gy += 100 * ((days - 1) / 36524)
        days = (days - 1) % 36524
        if (days >= 365) days++
    }
    gy += 4 * (days / 1461)
    days %= 1461
    if (days > 365) {
        gy += (days - 1) / 365
        days = (days - 1) % 365
    }
    var gd = days + 1
    val leap = gy % 4 == 0 && (gy % 100 != 0 || gy % 400 == 0)
    val monthLengths = intArrayOf(0,31,if (leap) 29 else 28,31,30,31,30,31,31,30,31,30,31)
    var gm = 1
    while (gm <= 12 && gd > monthLengths[gm]) {
        gd -= monthLengths[gm]
        gm++
    }
    return Triple(gy, gm, gd)
}

private fun jalaliIsoDate(year: Int, month: Int, day: Int): String {
    val (gy, gm, gd) = jalaliToGregorian(year, month, day)
    return gy.toString().padStart(4, '0') + "-" + gm.toString().padStart(2, '0') + "-" + gd.toString().padStart(2, '0')
}

internal fun formatJalaliDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "تاریخ ثبت نشده"
    return runCatching {
        val parts = iso.take(10).split("-").map { it.toInt() }
        val j = gregorianToJalali(parts[0], parts[1], parts[2])
        "${toPersianDigits(j.day.toString().padStart(2, '0'))} ${jalaliMonths[j.month - 1]} ${toPersianDigits(j.year.toString())}"
    }.getOrDefault(iso)
}

private fun jalaliMonthLength(year: Int, month: Int): Int {
    if (month <= 6) return 31
    if (month <= 11) return 30
    val thisYear = jalaliToGregorian(year, 1, 1)
    val nextYear = jalaliToGregorian(year + 1, 1, 1)
    val a = Calendar.getInstance().apply { clear(); set(thisYear.first, thisYear.second - 1, thisYear.third) }.timeInMillis
    val b = Calendar.getInstance().apply { clear(); set(nextYear.first, nextYear.second - 1, nextYear.third) }.timeInMillis
    return if ((b - a) / 86400000L == 366L) 30 else 29
}

@Composable
internal fun JalaliDatePickerField(
    selectedIsoDate: String,
    onDateSelected: (String) -> Unit,
    label: String = "تاریخ بار",
    allowClear: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    val today = remember {
        val c = Calendar.getInstance()
        gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.month) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(if (selectedIsoDate.isBlank()) "▦  $label: انتخاب نشده" else "▦  $label: ${formatJalaliDate(selectedIsoDate)}")
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("$label (تقویم شمسی)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = {
                            if (month == 1) { month = 12; year-- } else month--
                        }) { Text("ماه قبل") }
                        Text("${jalaliMonths[month - 1]} ${toPersianDigits(year.toString())}", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            if (month == 12) { month = 1; year++ } else month++
                        }) { Text("ماه بعد") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        persianWeekDays.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
                    }
                    val first = jalaliToGregorian(year, month, 1)
                    val firstDay = Calendar.getInstance().apply { clear(); set(first.first, first.second - 1, first.third) }.get(Calendar.DAY_OF_WEEK) % 7
                    val total = jalaliMonthLength(year, month)
                    val cells = List(firstDay) { 0 } + (1..total).toList()
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            week.forEach { day ->
                                if (day == 0) {
                                    Text(" ", modifier = Modifier.weight(1f).padding(7.dp))
                                } else {
                                    val iso = jalaliIsoDate(year, month, day)
                                    TextButton(onClick = {
                                        onDateSelected(iso)
                                        open = false
                                    }, modifier = Modifier.weight(1f).size(38.dp)) {
                                        Text(toPersianDigits(day.toString()), color = if (iso == selectedIsoDate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            repeat(7 - week.size) { Text(" ", modifier = Modifier.weight(1f).padding(7.dp)) }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (allowClear && selectedIsoDate.isNotBlank()) TextButton(onClick = { onDateSelected(""); open = false }) { Text("پاک کردن") }
                    TextButton(onClick = { open = false }) { Text("بستن") }
                }
            }
        )
    }
}
