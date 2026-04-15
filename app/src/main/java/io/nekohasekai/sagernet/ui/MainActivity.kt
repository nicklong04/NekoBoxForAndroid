package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.logs
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.utils.NGUtil

class MainActivity : ThemedActivity(), SagerConnection.Callback, PreferenceDataStore.OnPreferenceChangeInternalListener {

    private val connection = SagerConnection(true)
    lateinit var binding: LayoutMainBinding

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) SagerNet.startService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ЭТАП 1.1: Настройка Toolbar и кнопки [ i ]
        setSupportActionBar(binding.appbarLayout.toolbar)
        
        binding.appbarLayout.toolbar.setNavigationOnClickListener {
            // ЭТАП 2.3: Окно "О приложении"
            MaterialAlertDialogBuilder(this)
                .setTitle("Legal Eight")
                .setMessage("Legal Eight. Fork NekoBox 1.4.2\n\nРазработано специально для семьи. Основано на открытом исходном коде NekoBox. Мы благодарим разработчиков оригинала за их огромный вклад.")
                .setPositiveButton("ОК", null)
                .show()
        }

        // ЭТАП 1.4: Логика Жучка (Копирование логов при ошибке)
        binding.bugIcon.setOnClickListener {
            try {
                val lastLogs = app.logs.takeLast(20).joinToString("\n")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("L8 Logs", lastLogs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Логи ошибок скопированы в буфер!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                snackbar("Не удалось скопировать логи").show()
            }
        }

        // ЭТАП 2.1: Сразу открываем экран конфигураций (Бургер мертв)
        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }

        // Выход из приложения при нажатии "Назад"
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }

        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(POST_NOTIFICATIONS), 0)
            }
        }
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        // Упрощено для L8: FAB удален, статусбар всегда доступен
        binding.stats.allowShow = true
        
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }
            else -> return false
        }
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state

        // Обновление только текстового статуса в футере
        binding.stats.changeState(state)
        
        // ЭТАП 1.4: Показываем жучка только при сбое соединения
        if (state == BaseService.State.STOPPED && msg != null) {
            binding.bugIcon.visibility = View.VISIBLE
            snackbar(getString(R.string.vpn_error, msg)).show()
        } else if (state == BaseService.State.CONNECTED) {
            binding.bugIcon.visibility = View.GONE
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        try {
            changeState(BaseService.State.values()[service.getState()])
        } catch (e: RemoteException) {
            NGUtil.logError(e)
        }
    }

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun cbStateChange(state: Int, msg: String?) {
        changeState(BaseService.State.values()[state], msg, true)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        // Статистика отключена в XML
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        // Обновление цифр скорости tx/rx удалено для L8
    }

    override fun onPreferenceChangeInternal(key: String) {
        if (key == Key.SHOW_BOTTOM_BAR) {
            SagerNet.reloadService()
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true
        
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }
}
