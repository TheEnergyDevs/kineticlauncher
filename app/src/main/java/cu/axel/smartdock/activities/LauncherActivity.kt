package cu.axel.smartdock.activities

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import cu.axel.smartdock.R
import cu.axel.smartdock.adapters.AppActionsAdapter
import cu.axel.smartdock.adapters.AppAdapter
import cu.axel.smartdock.adapters.AppAdapter.OnAppClickListener
import cu.axel.smartdock.adapters.AppAdapter.OnAppCombineListener
import cu.axel.smartdock.adapters.AppShortcutAdapter
import cu.axel.smartdock.models.Action
import cu.axel.smartdock.models.App
import cu.axel.smartdock.models.FolderApp
import cu.axel.smartdock.services.ACTION_LAUNCH_APP
import cu.axel.smartdock.services.DESKTOP_APP_PINNED
import cu.axel.smartdock.services.DOCK_SERVICE_ACTION
import cu.axel.smartdock.services.DOCK_SERVICE_CONNECTED
import cu.axel.smartdock.utils.AppUtils
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.DeepShortcutManager
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.IconPackUtils
import cu.axel.smartdock.utils.Utils
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

const val LAUNCHER_ACTION = "launcher_action"
const val LAUNCHER_RESUMED = "launcher_resumed"

open class LauncherActivity : AppCompatActivity(), OnAppClickListener, OnAppCombineListener,
    OnSharedPreferenceChangeListener {
    private var iconPackUtils: IconPackUtils? = null
    private lateinit var serviceBtn: MaterialButton
    private lateinit var appsGv: RecyclerView
    private lateinit var notesEt: EditText
    private lateinit var sharedPreferences: SharedPreferences
    private var x = 0f
    private var y = 0f

    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private val APPWIDGET_HOST_ID = 2025
    private val REQUEST_PICK_WIDGET = 101
    private val REQUEST_CREATE_WIDGET = 102

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, APPWIDGET_HOST_ID)

        val backgroundLayout = findViewById<RelativeLayout>(R.id.ll_background)
        serviceBtn = findViewById(R.id.service_btn)
        appsGv = findViewById(R.id.desktop_apps_gv)
        appsGv.layoutManager = GridLayoutManager(this, 5)
        notesEt = findViewById(R.id.notes_et)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        serviceBtn.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        val showDesktopContextMenu = {
            val view = LayoutInflater.from(this).inflate(R.layout.desktop_context_menu, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, this)
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            layoutParams.x = x.toInt().coerceAtLeast(16)
            layoutParams.y = y.toInt().coerceAtLeast(16)
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    try {
                        windowManager.removeView(view)
                    } catch (_: Exception) {}
                }
                false
            }

            view.findViewById<View>(R.id.menu_item_wallpaper)?.setOnClickListener {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
                try {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SET_WALLPAPER),
                            getString(R.string.wallpaper_and_style)
                        )
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                    } catch (_: Exception) {}
                }
            }

            view.findViewById<View>(R.id.menu_item_widgets)?.setOnClickListener {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
                pickAppWidget()
            }

            view.findViewById<View>(R.id.menu_item_settings)?.setOnClickListener {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
                startActivity(Intent(this, MainActivity::class.java))
            }

            // Popup Scale & Fade In Animation
            view.scaleX = 0.3f
            view.scaleY = 0.3f
            view.alpha = 0f
            view.pivotX = 0f
            view.pivotY = 0f
            windowManager.addView(view, layoutParams)
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                .start()
        }

        backgroundLayout.setOnLongClickListener {
            showDesktopContextMenu()
            true
        }
        backgroundLayout.setOnContextClickListener {
            showDesktopContextMenu()
            true
        }
        backgroundLayout.setOnTouchListener { _, event ->
            x = event.x
            y = event.y
            if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    showDesktopContextMenu()
                    return@setOnTouchListener true
                }
            }
            false
        }

        appsGv.setOnTouchListener { _, event ->
            x = event.x
            y = event.y
            if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    showDesktopContextMenu()
                    return@setOnTouchListener true
                }
            }
            false
        }

        ContextCompat.registerReceiver(
            this, object : BroadcastReceiver() {
                override fun onReceive(p1: Context, intent: Intent) {
                    when (intent.getStringExtra("action")) {
                        DOCK_SERVICE_CONNECTED -> serviceBtn.visibility = View.GONE
                        DESKTOP_APP_PINNED -> loadDesktopApps()
                    }
                }
            }, IntentFilter(DOCK_SERVICE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (sharedPreferences.getString("icon_pack", "")!!.isNotEmpty()) {
            iconPackUtils = IconPackUtils(this)
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        restoreSavedWidgets()
    }

    override fun onStart() {
        super.onStart()
        try {
            appWidgetHost.startListening()
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            appWidgetHost.stopListening()
        } catch (_: Exception) {}
    }

    private fun pickAppWidget() {
        try {
            val appWidgetId = appWidgetHost.allocateAppWidgetId()
            val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            startActivityForResult(pickIntent, REQUEST_PICK_WIDGET)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.something_wrong, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_PICK_WIDGET) {
                val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                if (appWidgetId != -1) configureWidget(appWidgetId)
            } else if (requestCode == REQUEST_CREATE_WIDGET) {
                val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                if (appWidgetId != -1) createWidgetView(appWidgetId)
            }
        } else if (resultCode == RESULT_CANCELED && data != null) {
            val appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (appWidgetId != -1) {
                try {
                    appWidgetHost.deleteAppWidgetId(appWidgetId)
                } catch (_: Exception) {}
            }
        }
    }

    private fun configureWidget(appWidgetId: Int) {
        val appWidgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return
        if (appWidgetInfo.configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = appWidgetInfo.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CREATE_WIDGET)
            } catch (_: Exception) {
                createWidgetView(appWidgetId)
            }
        } else {
            createWidgetView(appWidgetId)
        }
    }

    private fun createWidgetView(appWidgetId: Int) {
        val appWidgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return
        val hostView = appWidgetHost.createView(this, appWidgetId, appWidgetInfo)
        hostView.setAppWidget(appWidgetId, appWidgetInfo)
        val widgetContainer = findViewById<LinearLayout>(R.id.widget_container) ?: return
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(Utils.dpToPx(this@LauncherActivity, 8), Utils.dpToPx(this@LauncherActivity, 8), Utils.dpToPx(this@LauncherActivity, 8), Utils.dpToPx(this@LauncherActivity, 8))
        }
        widgetContainer.addView(hostView, lp)
        saveSavedWidgetId(appWidgetId)
    }

    private fun saveSavedWidgetId(id: Int) {
        val saved = sharedPreferences.getStringSet("desktop_widget_ids", mutableSetOf()) ?: mutableSetOf()
        val updated = HashSet(saved)
        updated.add(id.toString())
        sharedPreferences.edit().putStringSet("desktop_widget_ids", updated).apply()
    }

    private fun restoreSavedWidgets() {
        val widgetContainer = findViewById<LinearLayout>(R.id.widget_container) ?: return
        widgetContainer.removeAllViews()
        val saved = sharedPreferences.getStringSet("desktop_widget_ids", setOf()) ?: setOf()
        for (idStr in saved) {
            val appWidgetId = idStr.toIntOrNull() ?: continue
            val appWidgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: continue
            try {
                val hostView = appWidgetHost.createView(this, appWidgetId, appWidgetInfo)
                hostView.setAppWidget(appWidgetId, appWidgetInfo)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(Utils.dpToPx(this@LauncherActivity, 8), Utils.dpToPx(this@LauncherActivity, 8), Utils.dpToPx(this@LauncherActivity, 8), Utils.dpToPx(this@LauncherActivity, 8))
                }
                widgetContainer.addView(hostView, lp)
            } catch (_: Exception) {}
        }
    }

    private fun createNewFolder(name: String, initialApps: List<App>): FolderApp {
        val foldersFile = File(filesDir, "desktop_folders.txt")
        var folderName = name
        var counter = 1
        val existingLines = if (foldersFile.exists()) foldersFile.readLines() else emptyList()
        val existingNames = existingLines.mapNotNull { it.split("|").getOrNull(0) }.toSet()
        while (existingNames.contains(folderName)) {
            folderName = "$name $counter"
            counter++
        }
        val folderDrawable = ContextCompat.getDrawable(this, R.drawable.ic_folder)!!
        val folderApp = FolderApp(folderName, ArrayList(initialApps), folderDrawable)
        saveFolderApps(folderApp)
        loadDesktopApps()
        return folderApp
    }

    override fun onCombineApps(sourceApp: App, targetApp: App) {
        if (sourceApp is FolderApp) return
        if (targetApp is FolderApp) {
            if (!targetApp.apps.any { it.packageName == sourceApp.packageName }) {
                targetApp.apps.add(sourceApp)
                saveFolderApps(targetApp)
                loadDesktopApps()
                showFolderDialog(targetApp)
            }
        } else {
            val initial = listOf(targetApp, sourceApp)
            val newFolder = createNewFolder("Folder", initial)
            showFolderDialog(newFolder)
        }
    }

    private fun saveFolderApps(folderApp: FolderApp) {
        val foldersFile = File(filesDir, "desktop_folders.txt")
        val lines = if (foldersFile.exists()) foldersFile.readLines().toMutableList() else mutableListOf()
        var updated = false
        val newLines = lines.map { line ->
            val parts = line.split("|")
            if (parts.isNotEmpty() && parts[0] == folderApp.folderName) {
                updated = true
                "${folderApp.folderName}|${folderApp.apps.joinToString(",") { it.packageName }}"
            } else line
        }.toMutableList()
        if (!updated) {
            newLines.add("${folderApp.folderName}|${folderApp.apps.joinToString(",") { it.packageName }}")
        }
        foldersFile.writeText(newLines.joinToString("\n") + "\n")
    }

    private fun renameFolder(oldName: String, newName: String) {
        if (oldName == newName || newName.isBlank()) return
        val foldersFile = File(filesDir, "desktop_folders.txt")
        if (!foldersFile.exists()) return
        val lines = foldersFile.readLines()
        val newLines = lines.map { line ->
            val parts = line.split("|")
            if (parts.isNotEmpty() && parts[0] == oldName) {
                "$newName|${parts.getOrElse(1) { "" }}"
            } else line
        }
        foldersFile.writeText(newLines.joinToString("\n") + "\n")
    }

    private fun showFolderDialog(folderApp: FolderApp) {
        val view = LayoutInflater.from(this).inflate(R.layout.folder_overlay_layout, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, this)
        layoutParams.gravity = Gravity.CENTER
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        var currentFolderName = folderApp.folderName

        val folderNameEt = view.findViewById<EditText>(R.id.folder_name_et)
        folderNameEt.setText(currentFolderName)
        folderNameEt.addTextChangedListener { editable ->
            val updatedText = editable?.toString()?.trim() ?: ""
            if (updatedText.isNotEmpty() && updatedText != currentFolderName) {
                renameFolder(currentFolderName, updatedText)
                currentFolderName = updatedText
                loadDesktopApps()
            }
        }

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            false
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.folder_apps_rv)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = AppAdapter(this, folderApp.apps, object : OnAppClickListener {
            override fun onAppClicked(app: App, item: View) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
                launchApp(null, app.packageName)
            }

            override fun onAppLongClicked(app: App, item: View) {
                showAppContextMenu(app, item)
            }
        }, true, iconPackUtils)

        // Spread Expansion Animation
        view.scaleX = 0.2f
        view.scaleY = 0.2f
        view.alpha = 0f
        windowManager.addView(view, layoutParams)
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    fun loadDesktopApps() {
        val items = ArrayList<App>()
        val pinnedApps = AppUtils.getPinnedApps(this, AppUtils.DESKTOP_LIST)

        val folderDrawable = ContextCompat.getDrawable(this, R.drawable.ic_folder)!!
        val foldersFile = File(filesDir, "desktop_folders.txt")
        val folderMap = mutableMapOf<String, ArrayList<App>>()
        if (foldersFile.exists()) {
            for (line in foldersFile.readLines()) {
                if (line.isBlank()) continue
                val parts = line.split("|")
                val folderName = parts[0]
                val pkgList = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].split(",") else emptyList()
                val folderApps = ArrayList<App>()
                for (pkg in pkgList) {
                    val matching = pinnedApps.find { it.packageName == pkg } ?: AppUtils.getAppByPackageName(this, pkg)
                    if (matching != null) {
                        folderApps.add(matching)
                    }
                }
                folderMap[folderName] = folderApps
            }
        }

        for ((folderName, apps) in folderMap) {
            items.add(FolderApp(folderName, apps, folderDrawable))
        }

        val appsInFolders = folderMap.values.flatten().map { it.packageName }.toSet()
        for (app in pinnedApps) {
            if (!appsInFolders.contains(app.packageName)) {
                items.add(app)
            }
        }

        appsGv.adapter = AppAdapter(this, items, this, true, iconPackUtils)
    }

    override fun onResume() {
        super.onResume()
        sendBroadcast(
            Intent(LAUNCHER_ACTION)
                .setPackage(packageName)
                .putExtra("action", LAUNCHER_RESUMED)
        )

        serviceBtn.visibility =
            if (DeviceUtils.isAccessibilityServiceEnabled(this)) View.GONE else View.VISIBLE

        loadDesktopApps()

        if (sharedPreferences.getBoolean("show_notes", false)) {
            notesEt.visibility = View.VISIBLE
            loadNotes()
        } else
            notesEt.visibility = View.GONE

        appsGv.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        if (sharedPreferences.getBoolean("show_notes", false))
            saveNotes()
    }

    private fun loadNotes() {
        val notes = File(getExternalFilesDir(null), "notes.txt")
        try {
            val bufferedReader = BufferedReader(FileReader(notes))
            var line: String?
            val noteContent = StringBuilder()
            while (bufferedReader.readLine().also { line = it } != null) {
                noteContent.append(line).append("\n")
            }
            bufferedReader.close()
            notesEt.setText(noteContent.toString())
        } catch (_: IOException) {
        }
    }

    private fun saveNotes() {
        val noteContent = notesEt.text.toString()
        if (noteContent.isNotEmpty()) {
            val notes = File(getExternalFilesDir(null), "notes.txt")
            try {
                val fileWriter = FileWriter(notes)
                fileWriter.write(noteContent)
                fileWriter.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun launchApp(mode: String?, app: String) {
        sendBroadcast(
            Intent(LAUNCHER_ACTION)
                .setPackage(packageName)
                .putExtra("action", ACTION_LAUNCH_APP)
                .putExtra("mode", mode)
                .putExtra("app", app)
        )
    }

    private fun getAppActions(app: String): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (DeepShortcutManager.hasHostPermission(this)) {
            if (DeepShortcutManager.getShortcuts(app, this)!!
                    .isNotEmpty()
            ) actions.add(Action(R.drawable.ic_shortcuts, getString(R.string.shortcuts)))
        }
        actions.add(Action(R.drawable.ic_manage, getString(R.string.manage)))
        actions.add(Action(R.drawable.ic_launch_mode, getString(R.string.open_as)))
        actions.add(Action(R.drawable.ic_remove_from_desktop, getString(R.string.remove)))
        return actions
    }

    @SuppressLint("NewApi")
    private fun showAppContextMenu(app: App, anchor: View) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, this)
        ColorUtils.applyMainColor(this, sharedPreferences, view)
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        layoutParams.y = location[1] + Utils.dpToPx(this, anchor.measuredHeight / 2)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                windowManager.removeView(view)
            }
            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(this, getAppActions(app.packageName))
        actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
            if (adapterView.getItemAtPosition(position) is Action) {
                val action = adapterView.getItemAtPosition(position) as Action
                when (action.text) {
                    getString(R.string.manage) -> {
                        val actions = ArrayList<Action>()
                        actions.add(Action(R.drawable.ic_arrow_back, ""))
                        actions.add(Action(R.drawable.ic_info, getString(R.string.app_info)))
                        if (!AppUtils.isSystemApp(this, app.packageName)
                            || sharedPreferences.getBoolean("allow_sysapp_uninstall", false)
                        ) actions.add(
                            Action(
                                R.drawable.ic_uninstall,
                                getString(R.string.uninstall)
                            )
                        )
                        if (sharedPreferences.getBoolean("allow_app_freeze", false)) actions.add(
                            Action(R.drawable.ic_freeze, getString(R.string.freeze))
                        )
                        actionsLv.adapter = AppActionsAdapter(this, actions)
                    }

                    getString(R.string.shortcuts) -> {
                        actionsLv.adapter = AppShortcutAdapter(
                            this,
                            DeepShortcutManager.getShortcuts(app.packageName, this)!!
                        )
                    }

                    "" -> {
                        actionsLv.adapter = AppActionsAdapter(this, getAppActions(app.packageName))
                    }

                    getString(R.string.open_as) -> {
                        val actions = ArrayList<Action>()
                        actions.add(Action(R.drawable.ic_arrow_back, ""))
                        actions.add(Action(R.drawable.ic_standard, getString(R.string.standard)))
                        actions.add(Action(R.drawable.ic_maximized, getString(R.string.maximized)))
                        actions.add(Action(R.drawable.ic_portrait, getString(R.string.portrait)))
                        actions.add(
                            Action(
                                R.drawable.ic_fullscreen,
                                getString(R.string.fullscreen)
                            )
                        )
                        actionsLv.adapter = AppActionsAdapter(this, actions)
                    }

                    getString(R.string.app_info) -> {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData("package:${app.packageName}".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        windowManager.removeView(view)
                    }

                    getString(R.string.uninstall) -> {
                        @Suppress("DEPRECATION")
                        if (AppUtils.isSystemApp(
                                this,
                                app.packageName
                            )
                        ) DeviceUtils.runAsRoot("pm uninstall --user 0 ${app.packageName}") else startActivity(
                            Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:${app.packageName}".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        windowManager.removeView(view)
                    }

                    getString(R.string.freeze) -> {
                        val status = DeviceUtils.runAsRoot("pm disable ${app.packageName}")
                        if (status != "error")
                            Toast.makeText(this, R.string.app_frozen, Toast.LENGTH_SHORT).show()
                        else
                            Toast.makeText(this, R.string.something_wrong, Toast.LENGTH_SHORT)
                                .show()
                        windowManager.removeView(view)
                        loadDesktopApps()
                    }

                    getString(R.string.remove) -> {
                        AppUtils.unpinApp(this, app.packageName, AppUtils.DESKTOP_LIST)
                        windowManager.removeView(view)
                        loadDesktopApps()
                    }

                    getString(R.string.standard) -> {
                        windowManager.removeView(view)
                        launchApp("standard", app.packageName)
                    }

                    getString(R.string.maximized) -> {
                        windowManager.removeView(view)
                        launchApp("maximized", app.packageName)
                    }

                    getString(R.string.portrait) -> {
                        windowManager.removeView(view)
                        launchApp("portrait", app.packageName)
                    }

                    getString(R.string.fullscreen) -> {
                        windowManager.removeView(view)
                        launchApp("fullscreen", app.packageName)
                    }
                }
            } else if (adapterView.getItemAtPosition(position) is ShortcutInfo) {
                val shortcut = adapterView.getItemAtPosition(position) as ShortcutInfo
                windowManager.removeView(view)
                DeepShortcutManager.startShortcut(shortcut, this)
            }
        }
        windowManager.addView(view, layoutParams)
    }

    override fun onAppClicked(app: App, item: View) {
        if (app is FolderApp) {
            showFolderDialog(app)
        } else {
            launchApp(null, app.packageName)
        }
    }

    override fun onAppLongClicked(app: App, item: View) {
        showAppContextMenu(app, item)
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        preference: String?
    ) {
        if (preference == null)
            return
        if (preference == "icon_pack") {
            val iconPack = sharedPreferences.getString("icon_pack", "")!!
            iconPackUtils = if (iconPack.isNotEmpty()) {
                IconPackUtils(this)
            } else
                null
        }
    }
}
