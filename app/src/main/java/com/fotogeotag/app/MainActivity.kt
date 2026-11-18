package com.fotogeotag.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.fotogeotag.app.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val idLocale = Locale("id", "ID")

    private var original: Bitmap? = null   // resolusi penuh (untuk disimpan)
    private var preview: Bitmap? = null    // versi kecil (untuk pratinjau)
    private var mapImage: Bitmap? = null
    private val cal: Calendar = Calendar.getInstance()
    private var cameraUri: Uri? = null

    private val handler = Handler(Looper.getMainLooper())
    private var renderJob: Job? = null
    private var mapJob: Job? = null
    private val previewRunnable = Runnable { renderPreview() }
    private val mapRunnable = Runnable { refreshFromCoords(updateAddress = false, quiet = true) }
    private var programmaticEdit = false

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) loadImage(uri)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri)
    }
    private val locationPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.any { it }) fetchCurrentLocation() else toast(getString(R.string.location_denied))
    }
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) save() else toast(getString(R.string.storage_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        cameraUri = savedInstanceState?.getString("cameraUri")?.let(Uri::parse)

        b.btnCamera.setOnClickListener { openCamera() }
        b.btnGallery.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.btnGps.setOnClickListener { requestLocation() }
        b.btnRefresh.setOnClickListener { refreshFromCoords(updateAddress = true, quiet = false) }
        b.inputDate.setOnClickListener { pickDate() }
        b.inputTime.setOnClickListener { pickTime() }
        b.btnNow.setOnClickListener {
            cal.timeInMillis = System.currentTimeMillis()
            b.inputTz.setText(defaultTzLabel())
            updateDateFields()
        }
        b.switchMap.setOnCheckedChangeListener { _, _ -> schedulePreview() }
        b.btnSave.setOnClickListener { save() }
        b.btnShare.setOnClickListener { share() }

        listOf(b.inputTitle, b.inputAddress, b.inputTz).forEach { it.doAfterTextChanged { schedulePreview() } }
        listOf(b.inputLat, b.inputLng).forEach {
            it.doAfterTextChanged {
                schedulePreview()
                if (!programmaticEdit) {
                    handler.removeCallbacks(mapRunnable)
                    handler.postDelayed(mapRunnable, 1200)
                }
            }
        }

        b.inputTz.setText(defaultTzLabel())
        updateDateFields()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraUri?.let { outState.putString("cameraUri", it.toString()) }
    }

    // ---------- Foto ----------

    private fun openCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "foto_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_camera))
        }
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            b.progress.isVisible = true
            val loaded = withContext(Dispatchers.IO) { ImageLoader.load(this@MainActivity, uri) }
            b.progress.isVisible = false
            if (loaded == null) {
                toast(getString(R.string.load_failed))
                return@launch
            }
            original = loaded.bitmap
            preview = withContext(Dispatchers.Default) { ImageLoader.scaleDown(loaded.bitmap, 1400) }
            b.placeholder.isVisible = false

            cal.timeInMillis = loaded.dateMillis ?: System.currentTimeMillis()
            b.inputTz.setText(defaultTzLabel())
            updateDateFields()

            val ll = loaded.latLng
            if (ll != null) {
                setCoords(ll.first, ll.second)
                refreshFromCoords(updateAddress = true, quiet = false)
                b.locStatus.text = getString(R.string.loc_from_photo)
            } else {
                requestLocation()
            }
            schedulePreview()
        }
    }

    // ---------- Lokasi ----------

    private fun requestLocation() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            fetchCurrentLocation()
        } else {
            locationPerm.launch(perms)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            b.locStatus.text = getString(R.string.gps_off)
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (providers.isEmpty()) {
            b.locStatus.text = getString(R.string.gps_off)
            return
        }

        b.locStatus.text = getString(R.string.finding_location)
        var done = false
        val listeners = mutableListOf<LocationListener>()
        fun complete(loc: Location?) {
            if (done) return
            done = true
            listeners.forEach { runCatching { lm.removeUpdates(it) } }
            val result = loc ?: last
            if (result == null) {
                b.locStatus.text = getString(R.string.location_not_found)
            } else {
                setCoords(result.latitude, result.longitude)
                refreshFromCoords(updateAddress = true, quiet = false)
                b.locStatus.text = getString(R.string.location_accuracy, result.accuracy.toInt())
            }
        }

        for (p in providers) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lm.getCurrentLocation(p, null, mainExecutor) { loc -> if (loc != null) complete(loc) }
                } else {
                    val l = object : LocationListener {
                        override fun onLocationChanged(location: Location) = complete(location)
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    listeners += l
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, l, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                // provider tidak tersedia, abaikan
            }
        }
        handler.postDelayed({ complete(null) }, 20_000)
    }

    private fun setCoords(lat: Double, lng: Double) {
        programmaticEdit = true
        b.inputLat.setText(String.format(Locale.US, "%.6f", lat))
        b.inputLng.setText(String.format(Locale.US, "%.6f", lng))
        programmaticEdit = false
    }

    private fun readCoords(): Pair<Double, Double>? {
        val lat = b.inputLat.text.toString().trim().replace(',', '.').toDoubleOrNull()
        val lng = b.inputLng.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun refreshFromCoords(updateAddress: Boolean, quiet: Boolean) {
        val (lat, lng) = readCoords() ?: run {
            if (!quiet) toast(getString(R.string.invalid_coords))
            return
        }
        mapJob?.cancel()
        mapJob = lifecycleScope.launch {
            val img = withContext(Dispatchers.IO) { Geo.mapImage(lat, lng) }
            mapImage = img
            if (img == null && !quiet) b.locStatus.text = getString(R.string.map_failed)
            schedulePreview()
        }
        if (updateAddress) {
            lifecycleScope.launch {
                b.locStatus.text = getString(R.string.finding_address)
                val place = withContext(Dispatchers.IO) { Geo.reverse(this@MainActivity, lat, lng) }
                if (place == null) {
                    b.locStatus.text = getString(R.string.address_failed)
                } else {
                    b.inputTitle.setText(place.title)
                    b.inputAddress.setText(place.fullAddress)
                    b.locStatus.text = ""
                }
            }
        }
    }

    // ---------- Tanggal ----------

    private fun pickDate() {
        DatePickerDialog(
            this,
            { _, y, m, d -> cal.set(y, m, d); updateDateFields() },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime() {
        TimePickerDialog(
            this,
            { _, h, min -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); updateDateFields() },
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
        ).show()
    }

    private fun updateDateFields() {
        b.inputDate.setText(SimpleDateFormat("EEEE, dd/MM/yyyy", idLocale).format(cal.time))
        b.inputTime.setText(SimpleDateFormat("HH:mm", Locale.US).format(cal.time))
        schedulePreview()
    }

    private fun defaultTzLabel(): String {
        val offMin = TimeZone.getDefault().getOffset(cal.timeInMillis) / 60_000
        val sign = if (offMin < 0) "-" else "+"
        return String.format(Locale.US, "GMT %s%02d:%02d", sign, abs(offMin) / 60, abs(offMin) % 60)
    }

    private fun dateLine(): String {
        val day = SimpleDateFormat("EEEE", idLocale).format(cal.time)
        val rest = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.US).format(cal.time)
        return "$day, $rest ${b.inputTz.text.toString().trim()}".trim()
    }

    // ---------- Stempel ----------

    private fun currentData(): StampData {
        val lat = b.inputLat.text.toString().trim()
        val lng = b.inputLng.text.toString().trim()
        val coords = if (lat.isEmpty() && lng.isEmpty()) "" else "Lat $lat° Long $lng°"
        return StampData(
            title = b.inputTitle.text.toString(),
            address = b.inputAddress.text.toString(),
            coords = coords,
            dateLine = dateLine(),
            showMap = b.switchMap.isChecked,
        )
    }

    private fun schedulePreview() {
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, 250)
    }

    private fun renderPreview() {
        val src = preview ?: return
        val data = currentData()
        val map = mapImage
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) { StampRenderer.render(src, data, map) }
            b.preview.setImageBitmap(out)
        }
    }

    private fun save() {
        val src = original ?: run { toast(getString(R.string.no_photo)); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val data = currentData()
        val map = mapImage
        val name = "Geotag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        lifecycleScope.launch {
            b.progress.isVisible = true
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = StampRenderer.render(src, data, map)
                    Saver.saveToGallery(this@MainActivity, out, name).also { out.recycle() }
                } catch (e: Throwable) {
                    false
                }
            }
            b.progress.isVisible = false
            Snackbar.make(
                b.root,
                if (ok) getString(R.string.saved, Saver.FOLDER) else getString(R.string.save_failed),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun share() {
        val src = original ?: run { toast(getString(R.string.no_photo)); return }
        val data = currentData()
        val map = mapImage
        lifecycleScope.launch {
            b.progress.isVisible = true
            val intent = withContext(Dispatchers.IO) {
                try {
                    val out = StampRenderer.render(src, data, map)
                    Saver.shareIntent(this@MainActivity, out).also { out.recycle() }
                } catch (e: Throwable) {
                    null
                }
            }
            b.progress.isVisible = false
            if (intent != null) startActivity(intent) else toast(getString(R.string.save_failed))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
