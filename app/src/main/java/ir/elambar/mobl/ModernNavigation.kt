package ir.elambar.mobl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Role-aware menu model. Labels are intentionally Persian and consistent across screens. */
internal enum class ModernAccountRole(val title: String) {
    DRIVER("راننده"),
    COMPANY("شرکت باربری"),
    SUPER_ADMIN("مدیر ارشد اصلی")
}

internal data class ModernMenuEntry(
    val title: String,
    val subtitle: String,
    val symbol: String,
    val section: String
)

internal fun modernMenuFor(role: ModernAccountRole): List<ModernMenuEntry> {
    val common = listOf(
        ModernMenuEntry("خانه", "خلاصه وضعیت و میانبرها", "⌂", "اصلی"),
        ModernMenuEntry("بارهای من", "پیگیری بارها و وضعیت آن‌ها", "▤", "عملیات"),
        ModernMenuEntry("مخاطبین", "افراد و حساب‌های مجاز", "♙", "ارتباطات"),
        ModernMenuEntry("پیام‌ها و گفتگوها", "گفتگوهای مجاز حساب شما", "✉", "ارتباطات"),
        ModernMenuEntry("اعلان‌ها", "رویدادها و اطلاع‌رسانی‌ها", "♢", "ارتباطات"),
        ModernMenuEntry("پروفایل من", "اطلاعات شخصی و مدارک", "◎", "حساب"),
        ModernMenuEntry("تنظیمات", "ظاهر، اعلان و امنیت", "⚙", "حساب")
    )
    return when (role) {
        ModernAccountRole.DRIVER -> listOf(
            common[0],
            ModernMenuEntry("بارهای متناسب", "بارهای منطبق با خودرو و مقصد", "▣", "عملیات"),
            common[1], common[2], common[3], common[4], common[5], common[6]
        )
        ModernAccountRole.COMPANY -> listOf(
            common[0],
            ModernMenuEntry("ثبت اعلام بار", "ثبت تکی یا گروهی با مبدأ ثابت تهران", "+", "عملیات"),
            common[1],
            ModernMenuEntry("جست‌وجوی راننده", "فیلتر بر اساس خودرو و مقصد", "⌕", "عملیات"),
            common[2], common[3], common[4], common[5], common[6]
        )
        ModernAccountRole.SUPER_ADMIN -> listOf(
            common[0],
            ModernMenuEntry("مدیریت رانندگان", "بررسی حساب‌ها و مدارک", "♙", "مدیریت"),
            ModernMenuEntry("مدیریت شرکت‌ها", "تأیید و مدیریت شرکت‌ها", "▦", "مدیریت"),
            ModernMenuEntry("مدیریت بارها", "نظارت بر بارهای سامانه", "▤", "مدیریت"),
            ModernMenuEntry("مدیریت مدیران و مجوزها", "کنترل نقش‌ها و دسترسی‌ها", "♧", "مدیریت"),
            ModernMenuEntry("گفتگوها و اتاق عمومی", "نظارت بر ارتباطات طبق مجوز", "✉", "مدیریت"),
            ModernMenuEntry("گزارش‌ها و امنیت", "رویدادها و گزارش‌های مدیریتی", "▥", "مدیریت"),
            common[4], common[5], common[6]
        )
    }
}

/** Compact account header intended for the navigation drawer. */
@Composable
internal fun ModernAccountHeader(
    displayName: String,
    role: ModernAccountRole,
    approvalStatus: String,
    mobile: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text("◎", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(displayName.ifBlank { "کاربر سامانه" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(role.title, style = MaterialTheme.typography.bodyMedium)
                Text(approvalStatus.ifBlank { "وضعیت حساب نامشخص" }, style = MaterialTheme.typography.labelMedium)
                if (mobile.isNotBlank()) Text(mobile, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Reusable profile summary; sensitive fields remain caller-controlled. */
@Composable
internal fun ModernProfileSummary(
    displayName: String,
    role: ModernAccountRole,
    approvalStatus: String,
    mobile: String,
    companyOrVehicle: String,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(modifier = Modifier.size(62.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text("◎", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(displayName.ifBlank { "نام کاربر ثبت نشده" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(role.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AssistChip(onClick = {}, label = { Text(approvalStatus.ifBlank { "وضعیت نامشخص" }) })
            if (mobile.isNotBlank()) Text("شماره همراه: $mobile", style = MaterialTheme.typography.bodyMedium)
            if (companyOrVehicle.isNotBlank()) Text(companyOrVehicle, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) { Text("ویرایش اطلاعات") }
        }
    }
}
