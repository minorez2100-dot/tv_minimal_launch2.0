package com.custom.tvlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var bgImageView: ImageView
    private lateinit var weatherText: TextView
    private lateinit var tilesContainer: LinearLayout
    private val prefs by lazy { getSharedPreferences("tv_launcher_prefs", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val pickWallpaperLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            prefs.edit().putString("wallpaper_uri", it.toString()).apply()
            bgImageView.setImageURI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        loadSavedWallpaper()
        renderAppTiles()
        requestLocationAndWeather()
    }

    private fun setupUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0D0F14"))
        }

        // 1. Фоновые обои
        bgImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(bgImageView)

        // Легкий градиент снизу и сверху для читаемости в стиле Apple TV
        val vignette = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#88000000"),
                    Color.parseColor("#22000000"),
                    Color.parseColor("#CC000000")
                )
            )
        }
        root.addView(vignette)

        // Основной контейнер
        val mainLayout = RelativeLayout(this).apply {
            setPadding(dp(48), dp(36), dp(48), dp(42))
        }

        // 2. Верхний левый блок: Часы и Дата
        val clockBox = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
        }

        val textClock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 54f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }

        val dateClock = TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"
            format12Hour = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#CCFFFFFF"))
        }

        clockBox.addView(textClock)
        clockBox.addView(dateClock)
        mainLayout.addView(clockBox, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_START)
        })

        // 3. Верхний правый блок: Погода + Обои + Настройки ТВ
        val topRightBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        weatherText = TextView(this).apply {
            text = "Погода..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(24), 0)
        }
        topRightBar.addView(weatherText)

        // Кнопка смены обоев
        val wallpaperBtn = createGlassButton("Обои") {
            pickWallpaperLauncher.launch(arrayOf("image/*"))
        }
        topRightBar.addView(wallpaperBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)
        ).apply { marginEnd = dp(12) })

        // Кнопка настроек телевизора/проектора
        val settingsBtn = createGlassButton("⚙ Настройки") {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Настройки недоступны", Toast.LENGTH_SHORT).show()
            }
        }
        topRightBar.addView(settingsBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)
        ))

        mainLayout.addView(topRightBar, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_END)
        })

        // 4. Нижний док: 6 крупных плиток приложений
        tilesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }

        mainLayout.addView(tilesContainer, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(150)
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
        })

        root.addView(mainLayout)
        setContentView(root)
    }

    private fun createGlassButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = makeTileBackground(false)

            setOnFocusChangeListener { v, hasFocus ->
                v.background = makeTileBackground(hasFocus)
                v.animate().scaleX(if (hasFocus) 1.06f else 1.0f)
                    .scaleY(if (hasFocus) 1.06f else 1.0f).setDuration(150).start()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun renderAppTiles() {
        tilesContainer.removeAllViews()
        val installedApps = getInstalledApps()

        // Автозаполнение при первом запуске первыми 6 приложениями
        if (!prefs.getBoolean("initialized", false)) {
            installedApps.take(6).forEachIndexed { idx, app ->
                prefs.edit().putString("slot_$idx", app.packageName).apply()
            }
            prefs.edit().putBoolean("initialized", true).apply()
        }

        for (slot in 0 until 6) {
            val savedPkg = prefs.getString("slot_$slot", null)
            val appInfo = installedApps.find { it.packageName == savedPkg }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                background = makeTileBackground(false)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }

            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                if (appInfo != null) {
                    setImageDrawable(appInfo.icon)
                } else {
                    setImageResource(android.R.drawable.ic_input_add)
                    setColorFilter(Color.WHITE)
                }
            }

            val titleView = TextView(this).apply {
                text = appInfo?.label ?: "Выбрать"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(10), 0, 0)
            }

            card.addView(iconView)
            card.addView(titleView)

            // Анимация фокуса как в Apple TV
            card.setOnFocusChangeListener { v, hasFocus ->
                v.background = makeTileBackground(hasFocus)
                v.animate()
                    .scaleX(if (hasFocus) 1.12f else 1.0f)
                    .scaleY(if (hasFocus) 1.12f else 1.0f)
                    .translationZ(if (hasFocus) 12f else 0f)
                    .setDuration(160)
                    .start()
            }

            // Короткое нажатие ОК -> запуск приложения
            card.setOnClickListener {
                if (appInfo != null) {
                    val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(appInfo.packageName)
                        ?: packageManager.getLaunchIntentForPackage(appInfo.packageName)
                    if (launchIntent != null) startActivity(launchIntent)
                } else {
                    showAppPickerDialog(slot, installedApps)
                }
            }

            // Долгое нажатие ОК -> замена приложения в плитке
            card.setOnLongClickListener {
                showAppPickerDialog(slot, installedApps)
                true
            }

            val params = LinearLayout.LayoutParams(0, dp(136), 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
            tilesContainer.addView(card, params)

            if (slot == 0) card.requestFocus()
        }
    }

    private fun makeTileBackground(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            if (focused) {
                setColor(Color.parseColor("#55FFFFFF"))
                setStroke(dp(2), Color.WHITE)
            } else {
                setColor(Color.parseColor("#2AFFFFFF"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
    }

    data class AppItem(val label: String, val packageName: String, val icon: android.graphics.drawable.Drawable)

    private fun getInstalledApps(): List<AppItem> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val tvIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val combined = (packageManager.queryIntentActivities(mainIntent, 0) +
                packageManager.queryIntentActivities(tvIntent, 0))
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }

        return combined.map {
            AppItem(
                it.loadLabel(packageManager).toString(),
                it.activityInfo.packageName,
                it.loadIcon(packageManager)
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun showAppPickerDialog(slot: Int, apps: List<AppItem>) {
        val names = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите приложение для плитки #${slot + 1}")
            .setItems(names) { _, which ->
                prefs.edit().putString("slot_$slot", apps[which].packageName).apply()
                renderAppTiles()
            }
            .show()
    }

    private fun loadSavedWallpaper() {
        val uriStr = prefs.getString("wallpaper_uri", null)
        if (uriStr != null) {
            try {
                bgImageView.setImageURI(Uri.parse(uriStr))
            } catch (_: Exception) {}
        }
    }

    private fun requestLocationAndWeather() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 101)
        } else {
            fetchWeather()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) fetchWeather()
    }

    @SuppressLint("MissingPermission")
    private fun fetchWeather() {
        scope.launch(Dispatchers.IO) {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)

                // Координаты из GPS/сети, либо резервные (Екатеринбург/Урал), если на ТВ-боксе отключена геолокация
                val lat = loc?.latitude ?: 56.84
                val lon = loc?.longitude ?: 60.65

                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
                val json = JSONObject(URL(url).readText())
                val current = json.getJSONObject("current_weather")
                val temp = current.getDouble("temperature").toInt()
                val code = current.getInt("weathercode")
                val icon = when (code) {
                    0 -> "☀️"
                    in 1..3 -> "⛅"
                    in 45..48 -> "🌫️"
                    in 51..67, in 80..82 -> "🌧️"
                    in 71..77, in 85..86 -> "❄️"
                    in 95..99 -> "⛈️"
                    else -> "🌡️"
                }
                val sign = if (temp > 0) "+" else ""
                withContext(Dispatchers.Main) {
                    weatherText.text = "$icon $sign$temp°C"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    weatherText.text = "Нет сети"
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Блокируем кнопку "Назад", чтобы случайно не вылететь из лаунчера
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
