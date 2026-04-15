package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(), BaseService.Interface {

    companion object {
        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
    }

    var conn: ParcelFileDescriptor? = null
    private var metered = false

    override fun onCreate() {
        super.onCreate()
        DataStore.vpnService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action
        if (action == SagerNet.ACTION_SERVICE_STOP) {
            SagerNet.stopService()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setup(): Int {
        val builder = Builder()

        // ЭТАП 3.1: Хардкод MTU (Консервативный и пуленепробиваемый)
        builder.setMtu(1400)

        // ЭТАП 3.2: Только IPv4 (стабильная работа мобильных сетей)
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        builder.addRoute("0.0.0.0", 0)

        // ЭТАП 3.3: DNS в туннеле
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        builder.setSession("Legal Eight")

        // ЭТАП 3.4: Жесткая маршрутизация "Белой 8-ки" (Whitelist)
        val l8Apps = listOf(
            "com.google.android.youtube",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.whatsapp",
            "com.twitter.android",
            "com.openai.chatgpt",
            "com.android.chrome",
            "com.android.vending"
        )

        for (app in l8Apps) {
            try {
                packageManager.getPackageInfo(app, 0)
                builder.addAllowedApplication(app)
                Logs.d("Legal Eight Whitelist: Пакет $app направлен в туннель.")
            } catch (e: PackageManager.NameNotFoundException) {
                Logs.w("Legal Eight Whitelist: Пакет $app не установлен на устройстве.")
            }
        }

        updateUnderlyingNetwork(builder)

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(DataStore.meteredNetwork)
        }

        conn = builder.establish() ?: throw NullConnectionException()
        return conn!!.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SagerNet.underlyingNetwork?.let {
                builder?.setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
                    ?: setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
            }
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        conn?.close()
    }
}
