package com.roadconquest.app.progression

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.roadconquest.app.billing.VipEntitlement
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.util.Prefs

enum class CarStyle(val id: String, val label: String) {
    CLASSIC("classic", "Classic"),
    SPORT("sport", "Sport"),
    SUV("suv", "SUV"),
    RACER("racer", "Racer"),
    VAN("van", "Van"),
    TRUCK("truck", "Pickup")
}

enum class CarColor(val id: String, val label: String, val argb: Int) {
    BLUE("blue", "Blue", Color.rgb(37, 99, 235)),
    RED("red", "Red", Color.rgb(220, 38, 38)),
    GREEN("green", "Emerald", Color.rgb(5, 150, 105)),
    PURPLE("purple", "Purple", Color.rgb(124, 58, 237)),
    BLACK("black", "Black", Color.rgb(31, 41, 55)),
    TEAL("teal", "Teal", Color.rgb(13, 148, 136)),
    PINK("pink", "Rose", Color.rgb(219, 39, 119)),
    SILVER("silver", "Silver", Color.rgb(148, 163, 184)),
    GOLD("gold", "Gold", Color.rgb(217, 164, 6))
}

enum class RoadColor(val id: String, val label: String, val argb: Int) {
    BLUE("blue", "Blue", Color.rgb(37, 99, 235)),
    RED("red", "Red", Color.rgb(220, 38, 38)),
    GREEN("green", "Emerald", Color.rgb(5, 150, 105)),
    PURPLE("purple", "Purple", Color.rgb(124, 58, 237)),
    ORANGE("orange", "Orange", Color.rgb(234, 88, 12)),
    CYAN("cyan", "Cyan", Color.rgb(8, 145, 178)),
    PINK("pink", "Rose", Color.rgb(219, 39, 119)),
    CHARCOAL("charcoal", "Charcoal", Color.rgb(71, 85, 105)),
    GOLD("gold", "Gold", Color.rgb(217, 164, 6))
}

enum class CosmeticType { CAR_STYLE, CAR_COLOR, ROAD_COLOR, APP_THEME }

data class ShopItem(
    val id: String,
    val name: String,
    val description: String,
    val cost: Long,
    val type: CosmeticType,
    val value: String
)

object ShopCatalog {
    val items: List<ShopItem> = listOf(
        ShopItem("car_style_classic", "Classic car", "The original Road Conquest car.", 0, CosmeticType.CAR_STYLE, CarStyle.CLASSIC.id),
        ShopItem("car_style_sport", "Sport car", "A lower, sleeker map marker.", 650, CosmeticType.CAR_STYLE, CarStyle.SPORT.id),
        ShopItem("car_style_suv", "SUV", "A taller, boxier map marker.", 950, CosmeticType.CAR_STYLE, CarStyle.SUV.id),
        ShopItem("car_style_racer", "Racer", "A sharp performance-style marker.", 1_600, CosmeticType.CAR_STYLE, CarStyle.RACER.id),
        ShopItem("car_style_van", "Van", "A roomy van marker for frequent explorers.", 1_900, CosmeticType.CAR_STYLE, CarStyle.VAN.id),
        ShopItem("car_style_truck", "Pickup", "A rugged pickup truck marker.", 2_600, CosmeticType.CAR_STYLE, CarStyle.TRUCK.id),

        ShopItem("car_color_blue", "Blue car", "Road Conquest blue.", 0, CosmeticType.CAR_COLOR, CarColor.BLUE.id),
        ShopItem("car_color_red", "Red car", "A bright red car marker.", 300, CosmeticType.CAR_COLOR, CarColor.RED.id),
        ShopItem("car_color_green", "Emerald car", "An emerald green car marker.", 300, CosmeticType.CAR_COLOR, CarColor.GREEN.id),
        ShopItem("car_color_purple", "Purple car", "A vivid purple car marker.", 450, CosmeticType.CAR_COLOR, CarColor.PURPLE.id),
        ShopItem("car_color_black", "Black car", "A dark graphite car marker.", 450, CosmeticType.CAR_COLOR, CarColor.BLACK.id),
        ShopItem("car_color_teal", "Teal car", "A cool teal car marker.", 650, CosmeticType.CAR_COLOR, CarColor.TEAL.id),
        ShopItem("car_color_pink", "Rose car", "A vibrant rose-colored car.", 950, CosmeticType.CAR_COLOR, CarColor.PINK.id),
        ShopItem("car_color_silver", "Silver car", "A polished silver car marker.", 1_300, CosmeticType.CAR_COLOR, CarColor.SILVER.id),
        ShopItem("car_color_gold", "Gold car", "A metallic gold car marker.", 1_800, CosmeticType.CAR_COLOR, CarColor.GOLD.id),

        ShopItem("road_color_blue", "Blue roads", "The original traveled-road color.", 0, CosmeticType.ROAD_COLOR, RoadColor.BLUE.id),
        ShopItem("road_color_red", "Red roads", "Draw traveled roads in red.", 450, CosmeticType.ROAD_COLOR, RoadColor.RED.id),
        ShopItem("road_color_green", "Emerald roads", "Draw traveled roads in emerald.", 450, CosmeticType.ROAD_COLOR, RoadColor.GREEN.id),
        ShopItem("road_color_purple", "Purple roads", "Draw traveled roads in purple.", 600, CosmeticType.ROAD_COLOR, RoadColor.PURPLE.id),
        ShopItem("road_color_orange", "Orange roads", "Draw traveled roads in orange.", 600, CosmeticType.ROAD_COLOR, RoadColor.ORANGE.id),
        ShopItem("road_color_cyan", "Cyan roads", "Draw traveled roads in cyan.", 900, CosmeticType.ROAD_COLOR, RoadColor.CYAN.id),
        ShopItem("road_color_pink", "Rose roads", "Draw traveled roads in rose.", 1_400, CosmeticType.ROAD_COLOR, RoadColor.PINK.id),
        ShopItem("road_color_charcoal", "Charcoal roads", "Draw traveled roads in charcoal.", 1_800, CosmeticType.ROAD_COLOR, RoadColor.CHARCOAL.id),
        ShopItem("road_color_gold", "Gold roads", "Draw traveled roads in gold.", 2_200, CosmeticType.ROAD_COLOR, RoadColor.GOLD.id),

        ShopItem("ui_standard", "Standard UI", "Use the normal Road Conquest palette and launcher icon.", 0, CosmeticType.APP_THEME, "standard"),
        ShopItem("ui_gold", "Golden Road Conquest", "Gold/cream app UI plus a golden launcher icon.", 6_500, CosmeticType.APP_THEME, "gold")
    )
}

object Cosmetics {
    fun carStyle(context: Context): CarStyle =
        CarStyle.entries.firstOrNull { it.id == Prefs.carStyle(context) } ?: CarStyle.CLASSIC

    fun carColor(context: Context): CarColor =
        CarColor.entries.firstOrNull { it.id == Prefs.carColor(context) } ?: CarColor.BLUE

    fun roadColor(context: Context): RoadColor =
        RoadColor.entries.firstOrNull { it.id == Prefs.roadColor(context) } ?: RoadColor.BLUE

    fun isEquipped(context: Context, item: ShopItem): Boolean = when (item.type) {
        CosmeticType.CAR_STYLE -> Prefs.carStyle(context) == item.value
        CosmeticType.CAR_COLOR -> Prefs.carColor(context) == item.value
        CosmeticType.ROAD_COLOR -> Prefs.roadColor(context) == item.value
        CosmeticType.APP_THEME -> Prefs.isGoldUiEnabled(context) == (item.value == "gold")
    }

    fun equip(context: Context, item: ShopItem): Boolean {
        if (item.cost > 0 &&
            !(VipEntitlement.isActive(context) && VipEntitlement.isGoldIncluded(item.id)) &&
            !ProgressionRepository(context).isPurchased(item.id)
        ) return false
        when (item.type) {
            CosmeticType.CAR_STYLE -> Prefs.setCarStyle(context, item.value)
            CosmeticType.CAR_COLOR -> Prefs.setCarColor(context, item.value)
            CosmeticType.ROAD_COLOR -> Prefs.setRoadColor(context, item.value)
            CosmeticType.APP_THEME -> {
                val gold = item.value == "gold"
                Prefs.setGoldUiEnabled(context, gold)
                LauncherIcon.apply(context, gold)
            }
        }
        return true
    }

    /** The preview uses the exact same renderer as the live map car marker. */
    fun renderCarIcon(context: Context, style: CarStyle = carStyle(context), tint: CarColor = carColor(context)): Bitmap {
        val scale = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val size = (42f * scale).toInt().coerceAtLeast(42)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 48f, size / 48f)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint.argb }
        val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 24, 39) }

        when (style) {
            CarStyle.CLASSIC -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 29f)
                    lineTo(14f, 19f)
                    cubicTo(14.6f, 16f, 17.2f, 14f, 20.2f, 14f)
                    lineTo(27.8f, 14f)
                    cubicTo(30.8f, 14f, 33.4f, 16f, 34f, 19f)
                    lineTo(36f, 29f)
                    lineTo(39f, 31f)
                    lineTo(39f, 39f)
                    lineTo(34f, 39f)
                    lineTo(34f, 36f)
                    lineTo(14f, 36f)
                    lineTo(14f, 39f)
                    lineTo(9f, 39f)
                    lineTo(9f, 31f)
                    close()
                }, body)
                canvas.drawPath(Path().apply {
                    moveTo(17f, 20f)
                    lineTo(31f, 20f)
                    lineTo(32.5f, 27f)
                    lineTo(15.5f, 27f)
                    close()
                }, glass)
                canvas.drawCircle(15.5f, 33.5f, 2.5f, dark)
                canvas.drawCircle(32.5f, 33.5f, 2.5f, dark)
            }
            CarStyle.SPORT -> {
                canvas.drawPath(Path().apply {
                    moveTo(7f, 30f); lineTo(12f, 22f); lineTo(18f, 15f); lineTo(31f, 15f)
                    lineTo(38f, 23f); lineTo(41f, 34f); lineTo(8f, 34f); close()
                }, body)
                canvas.drawPath(Path().apply {
                    moveTo(19f, 18f); lineTo(30f, 18f); lineTo(34f, 23f); lineTo(15f, 23f); close()
                }, glass)
            }
            CarStyle.VAN -> {
                canvas.drawRoundRect(RectF(8f, 15f, 40f, 38f), 5f, 5f, body)
                canvas.drawRoundRect(RectF(12f, 18f, 36f, 26f), 2f, 2f, glass)
                canvas.drawCircle(15f, 36f, 3f, dark)
                canvas.drawCircle(33f, 36f, 3f, dark)
            }
            CarStyle.TRUCK -> {
                canvas.drawRoundRect(RectF(8f, 22f, 40f, 36f), 4f, 4f, body)
                canvas.drawRect(13f, 13f, 30f, 28f, body)
                canvas.drawRect(16f, 16f, 27f, 24f, glass)
                canvas.drawCircle(15f, 36f, 3f, dark)
                canvas.drawCircle(34f, 36f, 3f, dark)
            }
            CarStyle.SUV -> {
                canvas.drawRoundRect(RectF(8f, 15f, 40f, 38f), 4f, 4f, body)
                canvas.drawRect(13f, 9f, 35f, 22f, body)
                canvas.drawRect(16f, 12f, 32f, 20f, glass)
            }
            CarStyle.RACER -> {
                canvas.drawPath(Path().apply {
                    moveTo(24f, 5f); lineTo(37f, 18f); lineTo(40f, 36f); lineTo(29f, 31f)
                    lineTo(24f, 42f); lineTo(19f, 31f); lineTo(8f, 36f); lineTo(11f, 18f); close()
                }, body)
                canvas.drawPath(Path().apply {
                    moveTo(24f, 11f); lineTo(31f, 21f); lineTo(17f, 21f); close()
                }, glass)
            }
        }
        if (style == CarStyle.SPORT || style == CarStyle.SUV) {
            canvas.drawCircle(14f, 36f, 3f, dark)
            canvas.drawCircle(34f, 36f, 3f, dark)
        }
        return bitmap
    }
}

object LauncherIcon {
    fun apply(context: Context, gold: Boolean) {
        val app = context.applicationContext
        val manager = app.packageManager
        val defaultIcon = ComponentName(app, app.packageName + ".DefaultLauncher")
        val goldIcon = ComponentName(app, app.packageName + ".GoldLauncher")
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val flags = PackageManager.DONT_KILL_APP
        runCatching {
            if (gold) {
                manager.setComponentEnabledSetting(goldIcon, enabled, flags)
                manager.setComponentEnabledSetting(defaultIcon, disabled, flags)
            } else {
                manager.setComponentEnabledSetting(defaultIcon, enabled, flags)
                manager.setComponentEnabledSetting(goldIcon, disabled, flags)
            }
        }
    }
}
