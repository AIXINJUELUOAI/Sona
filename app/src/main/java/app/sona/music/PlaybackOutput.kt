package app.sona.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Shared by the in-process UI and service; only the service attaches a player and audio track. */
@UnstableApi
internal class PlaybackOutput(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val router = context.getSystemService(MediaRouter::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val epoch = AtomicInteger()
    private var player: ExoPlayer? = null
    private var track: AudioTrack? = null
    private var nativeDevices = emptyList<AudioDeviceInfo>()
    private var preferredId: Int? = null
    private var pendingSince = 0L
    private var systemAttempted = false
    private var visible = false
    var devices by mutableStateOf(emptyList<OutputDevice>())
        private set
    var selection by mutableStateOf(OutputSelection())
        private set
    val selectedId: Int?
        get() = selection.displayId(systemVolumeId ?: devices.singleOrNull()?.id)
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var volume by mutableIntStateOf(0)
        private set
    var volumeMax by mutableIntStateOf(0)
        private set
    var systemVolumeId by mutableStateOf<Int?>(null)
        private set
    var volumeFixed by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private val routingListener = AudioRouting.OnRoutingChangedListener { route ->
        if (route === track) refresh()
    }
    private val deviceListener = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    private val poll = object : Runnable {
        override fun run() = refresh()
    }

    fun audioOutputProvider(): AudioOutputProvider {
        val generation = epoch.incrementAndGet()
        return object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val output = super.getAudioOutput(config)
                val nativeTrack = (output as AudioTrackAudioOutput).audioTrack
                val alive = AtomicBoolean(true)
                handler.post {
                    if (epoch.get() == generation && alive.get()) adoptTrack(nativeTrack)
                }
                return object : ForwardingAudioOutput(output) {
                    override fun play() {
                        super.play()
                        handler.post { if (epoch.get() == generation && alive.get()) refresh() }
                    }
                    override fun release() {
                        alive.set(false)
                        handler.post { if (track === nativeTrack) adoptTrack(null) }
                        super.release()
                    }
                }
            }
        }
    }

    fun attach(value: ExoPlayer) {
        player = value; ready = true
        audio.registerAudioDeviceCallback(deviceListener, handler)
        refresh()
    }

    fun detach() {
        ready = false; epoch.incrementAndGet()
        handler.removeCallbacks(poll)
        audio.unregisterAudioDeviceCallback(deviceListener)
        player = null
        adoptTrack(null)
        player = null; preferredId = null; selection = OutputSelection()
        playing = false; pendingSince = 0; systemAttempted = false
    }

    fun setVisible(value: Boolean) {
        visible = value
        refresh()
        if (value) {
            val route = runCatching { track?.routedDevice }
            Log.i("SonaOutput", "Panel track=${track != null} playing=$playing route=${route.getOrNull()?.id} actual=${selection.actualId} system=$systemVolumeId selected=$selectedId target=${selection.targetId} error=${route.exceptionOrNull()?.javaClass?.simpleName}")
        }
    }

    private fun adoptTrack(value: AudioTrack?) {
        if (value === track) return
        track?.let { runCatching { it.removeOnRoutingChangedListener(routingListener) } }
        track = value
        value?.let {
            runCatching { it.addOnRoutingChangedListener(routingListener, handler) }
                .onFailure { Log.w("SonaOutput", "AudioTrack routing listener failed", it) }
        }
        refresh()
    }

    fun select(id: Int) {
        val currentPlayer = player ?: return
        refresh()
        val device = nativeDevices.firstOrNull { it.id == id } ?: return
        if (selection.targetId == id) return
        selection = if (playing && selection.actualId == id) selection.cancel() else selection.request(id)
        preferredId = id
        message = null; pendingSince = 0; systemAttempted = false
        Log.i("SonaOutput", "Request device id=$id type=${device.type}")
        runCatching { currentPlayer.setPreferredAudioDevice(device) }
            .onFailure { failSwitch("设备切换未完成，请重试") }
        refresh()
    }

    fun refresh() {
        val connected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isMediaDevice)
        nativeDevices = connected.filterNot { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE &&
            connected.any { candidate -> candidate.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } }
        devices = nativeDevices.map { OutputDevice(it.id, deviceName(it), deviceKind(it.type)) }
            .sortedBy { if (it.kind == OutputKind.Phone) 0 else 1 }
        val available = nativeDevices.map { it.id }.toSet()
        val oldTarget = selection.targetId
        selection = selection.retain(available)
        if (preferredId != null && preferredId !in available) {
            preferredId = null; player?.setPreferredAudioDevice(null)
            message = "设备已断开，正在跟随系统输出"
        }
        if (oldTarget != null && oldTarget !in available) { pendingSince = 0; systemAttempted = false }
        playing = player?.isPlaying == true
        // getRoutedDevice is meaningful only while the native AudioTrack is playing.
        val actual = runCatching { track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.routedDevice }.getOrNull()
        val actualId = actual?.let(::canonicalId)
        if (actual != null && actualId in available) {
            if (actualId != selection.actualId || actualId == selection.targetId) {
                Log.i("SonaOutput", "Observed device id=${actual.id} type=${actual.type}")
            }
            selection = selection.observe(actualId!!)
        }
        if (preferredId != null && playing && !systemAttempted) {
            systemAttempted = true; requestSystemRoute(preferredId!!)
        }
        if (selection.targetId != null && playing) {
            if (pendingSince == 0L) pendingSince = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - pendingSince > 8000) failSwitch("切换未生效，仍使用原输出")
        } else if (!playing) pendingSince = 0
        if (selection.targetId == null) pendingSince = 0
        systemVolumeId = if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).singleOrNull()?.let(::canonicalId)
            }.getOrNull()?.takeIf { it in available }
        } else nativeDevices.singleOrNull()?.id
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeFixed = audio.isVolumeFixed
        handler.removeCallbacks(poll)
        if (ready && (visible || (selection.targetId != null && playing))) handler.postDelayed(poll, 500)
    }

    fun canAdjustVolume(id: Int) = ready && !volumeFixed && volumeMax > 0 && selection.canAdjustVolume(id, systemVolumeId)

    fun setVolume(id: Int, value: Float) {
        refresh()
        if (!canAdjustVolume(id)) return
        if (value.roundToInt() == volume) return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.roundToInt().coerceIn(0, volumeMax), 0) }
            .onFailure { message = "音量调整未完成，请在系统面板调整" }
        refresh()
    }

    private fun failSwitch(reason: String) {
        selection = selection.cancel(); preferredId = selection.actualId
        runCatching { player?.setPreferredAudioDevice(nativeDevices.firstOrNull { it.id == preferredId }) }
        message = reason; pendingSince = 0; systemAttempted = false
        Log.w("SonaOutput", reason)
    }

    private fun requestSystemRoute(id: Int) {
        val device = nativeDevices.firstOrNull { it.id == id } ?: return
        val bluetooth = deviceKind(device.type) == OutputKind.Bluetooth
        val candidates = (0 until router.routeCount).map(router::getRouteAt).filter {
            it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_AUDIO != 0 &&
                (it.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) == bluetooth
        }
        // The legacy default route includes wired/USB; never guess a different physical device.
        val wired = nativeDevices.any { deviceKind(it.type) == OutputKind.Headphones }
        if (!bluetooth && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && wired) return
        val route = if (bluetooth) candidates.singleOrNull { it.name.toString() == device.productName.toString() }
            else candidates.singleOrNull()
        route?.let { runCatching { router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, it) } }
    }

    private fun isMediaDevice(device: AudioDeviceInfo): Boolean = device.isSink && device.type in setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE)

    private fun deviceKind(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputKind.Phone
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputKind.Bluetooth
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET -> OutputKind.Headphones
        else -> OutputKind.Speaker
    }

    private fun deviceName(device: AudioDeviceInfo): String = when (deviceKind(device.type)) {
        OutputKind.Phone -> "手机"
        else -> device.productName.toString().ifBlank { if (deviceKind(device.type) == OutputKind.Bluetooth) "蓝牙设备" else "音频设备" }
    }

    private fun canonicalId(device: AudioDeviceInfo): Int = if (deviceKind(device.type) == OutputKind.Phone) {
        nativeDevices.firstOrNull { deviceKind(it.type) == OutputKind.Phone }?.id ?: device.id
    } else device.id
}
