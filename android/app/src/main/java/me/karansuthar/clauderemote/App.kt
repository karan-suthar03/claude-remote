package me.karansuthar.clauderemote

import android.app.Application
import me.karansuthar.clauderemote.bridge.BridgeClient
import me.karansuthar.clauderemote.data.Device
import me.karansuthar.clauderemote.data.DeviceStore
import me.karansuthar.clauderemote.data.Settings

class App : Application() {
    val settings: Settings by lazy { Settings(this) }
    val devices: DeviceStore by lazy { DeviceStore(this) }

    private val clients = HashMap<String, BridgeClient>()

    /** The connection to [device]; created on first use and reused (recreated if its address changed). */
    @Synchronized
    fun client(device: Device): BridgeClient {
        clients[device.id]?.takeIf { it.url == device.url }?.let { return it }
        clients.remove(device.id)?.shutdown()
        return BridgeClient(device.url).also { clients[device.id] = it }
    }

    /** Closes and forgets a removed device's connection. */
    @Synchronized
    fun dropClient(deviceId: String) {
        clients.remove(deviceId)?.shutdown()
    }
}
