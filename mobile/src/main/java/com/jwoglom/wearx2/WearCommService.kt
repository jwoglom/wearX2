package com.jwoglom.wearx2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.jwoglom.pumpx2.pump.TandemError
import com.jwoglom.pumpx2.pump.bluetooth.TandemBluetoothHandler
import com.jwoglom.pumpx2.pump.bluetooth.TandemPump
import com.jwoglom.pumpx2.pump.messages.bluetooth.Characteristic
import com.jwoglom.pumpx2.pump.messages.response.authentication.CentralChallengeResponse
import com.jwoglom.pumpx2.pump.messages.response.qualifyingEvent.QualifyingEvent
import com.jwoglom.wearx2.shared.PumpMessageSerializer
import com.jwoglom.wearx2.shared.PumpQualifyingEventsSerializer
import com.jwoglom.wearx2.shared.WearCommServiceCodes
import com.jwoglom.wearx2.shared.util.DebugTree
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.HciStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber


class WearCommService : WearableListenerService(), GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var serviceLooper: Looper? = null
    private var wearCommHandler: WearCommHandler? = null

    private lateinit var mApiClient: GoogleApiClient
    private lateinit var pump: Pump
    private lateinit var tandemBTHandler: TandemBluetoothHandler

    private var lastResponseMessage: MutableMap<Pair<Characteristic, Byte>, com.jwoglom.pumpx2.pump.messages.Message> = mutableMapOf()

    private inner class Pump() : TandemPump(applicationContext) {
        var lastPeripheral: BluetoothPeripheral? = null
        var isConnected = false

        init {
            enableTconnectAppConnectionSharing()
            enableSendSharedConnectionResponseMessages()
            // before adding relyOnConnectionSharingForAuthentication(), callback issues need to be resolved
            Timber.i("Pump init")
        }

        override fun onReceiveMessage(
            peripheral: BluetoothPeripheral?,
            message: com.jwoglom.pumpx2.pump.messages.Message?
        ) {
            message?.let { lastResponseMessage.put(Pair(it.characteristic, it.opCode()), it) }
            wearCommHandler?.sendMessage("/from-pump/receive-message", PumpMessageSerializer.toBytes(message))
        }

        override fun onReceiveQualifyingEvent(
            peripheral: BluetoothPeripheral?,
            events: MutableSet<QualifyingEvent>?
        ) {
            wearCommHandler?.sendMessage("/from-pump/receive-qualifying-event", PumpQualifyingEventsSerializer.toBytes(events))
        }

        override fun onWaitingForPairingCode(
            peripheral: BluetoothPeripheral?,
            centralChallengeResponse: CentralChallengeResponse?
        ) {
            wearCommHandler?.sendMessage("/from-pump/waiting-for-pairing-code", PumpMessageSerializer.toBytes(centralChallengeResponse))
        }

        override fun onInitialPumpConnection(peripheral: BluetoothPeripheral?) {
            super.onInitialPumpConnection(peripheral)
            lastPeripheral = peripheral
        }

        override fun onPumpConnected(peripheral: BluetoothPeripheral?) {
            super.onPumpConnected(peripheral)
            lastPeripheral = peripheral
            Timber.i("service onPumpConnected")
            isConnected = true
            wearCommHandler?.sendMessage("/from-pump/pump-connected",
                peripheral?.name!!.toByteArray()
            )
        }

        override fun onPumpModel(peripheral: BluetoothPeripheral?, modelNumber: String?) {
            super.onPumpModel(peripheral, modelNumber)
            Timber.i("service onPumpModel")
            wearCommHandler?.sendMessage("/from-pump/pump-model",
                modelNumber!!.toByteArray()
            )
        }

        override fun onPumpDisconnected(
            peripheral: BluetoothPeripheral?,
            status: HciStatus?
        ): Boolean {
            Timber.i("service onPumpDisconnected")
            lastPeripheral = null
            isConnected = false
            wearCommHandler?.sendMessage("/from-pump/pump-disconnected",
                peripheral?.name!!.toByteArray()
            )
            return super.onPumpDisconnected(peripheral, status)
        }

        override fun onPumpCriticalError(peripheral: BluetoothPeripheral?, reason: TandemError?) {
            super.onPumpCriticalError(peripheral, reason)
            Timber.w("onPumpCriticalError $reason")
            wearCommHandler?.sendMessage("/from-pump/pump-critical-error",
                reason?.message!!.toByteArray()
            );
        }

        fun command(message: com.jwoglom.pumpx2.pump.messages.Message?) {
            if (lastPeripheral == null) {
                Timber.w("Not sending message because no saved peripheral yet: $message")
                return
            }

            Timber.i("Pump send command: $message")
            sendCommand(lastPeripheral, message)
        }

    }

    // Handler that receives messages from the thread
    private inner class WearCommHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                WearCommServiceCodes.INIT_PUMP_COMM.ordinal -> {
                    Timber.i("wearCommHandler init pump class")
                    pump = Pump()
                    tandemBTHandler = TandemBluetoothHandler.getInstance(applicationContext, pump)
                    while (true) {
                        try {
                            Timber.i("Starting scan...")
                            tandemBTHandler.startScan()
                        } catch (e: SecurityException) {
                            Timber.e("Waiting for BT permissions $e")
                            Thread.sleep(500)
                            continue
                        }
                        break
                    }
                }
                WearCommServiceCodes.SEND_PUMP_COMMAND.ordinal -> {
                    Timber.i("wearCommHandler send command raw: ${String(msg.obj as ByteArray)}")
                    val pumpMsg = PumpMessageSerializer.fromBytes(msg.obj as ByteArray)
                    if (this@WearCommService::pump.isInitialized && pump.isConnected) {
                        Timber.i("wearCommHandler send command: $pumpMsg")
                        pump.command(pumpMsg)
                    } else {
                        Timber.w("wearCommHandler not sending command due to pump state: $pump $pumpMsg")
                    }
                }
                WearCommServiceCodes.SEND_PUMP_COMMANDS_BULK.ordinal -> {
                    Timber.i("wearCommHandler send commands raw: ${String(msg.obj as ByteArray)}")
                    PumpMessageSerializer.fromBulkBytes(msg.obj as ByteArray).forEach {
                        if (this@WearCommService::pump.isInitialized && pump.isConnected) {
                            Timber.i("wearCommHandler send command: $it")
                            pump.command(it)
                        } else {
                            Timber.w("wearCommHandler not sending command due to pump state: $pump $it")
                        }
                    }
                }
                WearCommServiceCodes.SEND_PUMP_COMMANDS_BUST_CACHE_BULK.ordinal -> {
                    Timber.i("wearCommHandler send commands bust cache raw: ${String(msg.obj as ByteArray)}")
                    PumpMessageSerializer.fromBulkBytes(msg.obj as ByteArray).forEach {
                        if (lastResponseMessage.containsKey(Pair(it.characteristic, it.responseOpCode))) {
                            Timber.i("wearCommHandler busted cache: $it")
                            lastResponseMessage.remove(Pair(it.characteristic, it.responseOpCode))
                        }
                        if (this@WearCommService::pump.isInitialized && pump.isConnected) {
                            Timber.i("wearCommHandler send command bust cache: $it")
                            pump.command(it)
                        } else {
                            Timber.w("wearCommHandler not sending command due to pump state: $pump $it")
                        }
                    }
                }
                WearCommServiceCodes.CACHED_PUMP_COMMANDS_BULK.ordinal -> {
                    Timber.i("wearCommHandler cached pump commands raw: ${String(msg.obj as ByteArray)}")
                    PumpMessageSerializer.fromBulkBytes(msg.obj as ByteArray).forEach {
                        if (lastResponseMessage.containsKey(Pair(it.characteristic, it.responseOpCode))) {
                            val response = lastResponseMessage.get(Pair(it.characteristic, it.responseOpCode))
                            Timber.i("wearCommHandler cached hit: $response")
                            wearCommHandler?.sendMessage("/from-pump/receive-cached-message", PumpMessageSerializer.toBytes(response))
                        } else if (this@WearCommService::pump.isInitialized && pump.isConnected) {
                            Timber.i("wearCommHandler cached miss: $it")
                            pump.command(it)
                        } else {
                            Timber.w("wearCommHandler not sending cached send command due to pump state: $pump $it")
                        }
                    }
                }
            }
        }

        fun sendMessage(path: String, message: ByteArray) {
            Timber.i("service sendMessage: $path ${String(message)}")
            Wearable.NodeApi.getConnectedNodes(mApiClient).setResultCallback { nodes ->
                Timber.i("service sendMessage nodes: ${nodes.nodes}")
                nodes.nodes.forEach { node ->
                    Wearable.MessageApi.sendMessage(mApiClient, node.id, path, message)
                        .setResultCallback { result ->
                            if (result.status.isSuccess) {
                                Timber.i("service message sent: $path ${String(message)} to: $node")
                            } else {
                                Timber.w("service sendMessage callback: ${result.status} for: $path ${String(message)}")
                            }
                        }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.uprootAll()
        Timber.plant(DebugTree("WCS"))
        Timber.d("service onCreate")

        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.  We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        HandlerThread("PumpCommServiceThread", Process.THREAD_PRIORITY_FOREGROUND).apply {
            start()
            Timber.d("service thread start")

            mApiClient = GoogleApiClient.Builder(applicationContext)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this@WearCommService)
                .addOnConnectionFailedListener(this@WearCommService)
                .build()

            mApiClient.connect()

            // Get the HandlerThread's Looper and use it for our Handler
            serviceLooper = looper
            wearCommHandler = WearCommHandler(looper)

            var notification = createNotification()
            startForeground(1, notification)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Timber.i("service messageReceived: ${messageEvent.path} ${String(messageEvent.data)}")
        when (messageEvent.path) {
            "/to-phone/start-activity" -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            "/to-phone/is-pump-connected" -> {
                if (this::pump.isInitialized && pump.isConnected && pump.lastPeripheral != null) {
                    wearCommHandler?.sendMessage("/from-pump/pump-connected",
                        pump.lastPeripheral?.name!!.toByteArray()
                    )
                }
            }
            "/to-pump/command" -> {
                sendPumpCommMessage(messageEvent.data)
            }
            "/to-pump/commands" -> {
                sendPumpCommMessages(messageEvent.data)
            }
            "/to-pump/commands-bust-cache" -> {
                sendPumpCommMessagesBustCache(messageEvent.data)
            }
            "/to-pump/cached-commands" -> {
                handleCachedCommandsRequest(messageEvent.data)
            }
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Timber.i("WearCommService onStartCommand $intent $flags $startId")
        Toast.makeText(this, "service starting", Toast.LENGTH_SHORT).show()

        // For each start request, send a message to start a job and deliver the
        // start ID so we know which request we're stopping when we finish the job
        wearCommHandler?.obtainMessage()?.also { msg ->
            msg.what = WearCommServiceCodes.INIT_PUMP_COMM.ordinal
            wearCommHandler?.sendMessage(msg)
        }

        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(
            applicationContext, this.javaClass
        )
        restartServiceIntent.setPackage(packageName)
        startService(restartServiceIntent)
        Toast.makeText(this, "WearX2 service removed", Toast.LENGTH_SHORT).show()
        super.onTaskRemoved(rootIntent)
    }

    private fun sendPumpCommMessage(pumpMsgBytes: ByteArray) {
        wearCommHandler?.obtainMessage()?.also { msg ->
            msg.what = WearCommServiceCodes.SEND_PUMP_COMMAND.ordinal
            msg.obj = pumpMsgBytes
            wearCommHandler?.sendMessage(msg)
        }
    }
    private fun sendPumpCommMessages(pumpMsgBytes: ByteArray) {
        wearCommHandler?.obtainMessage()?.also { msg ->
            msg.what = WearCommServiceCodes.SEND_PUMP_COMMANDS_BULK.ordinal
            msg.obj = pumpMsgBytes
            wearCommHandler?.sendMessage(msg)
        }
    }
    private fun sendPumpCommMessagesBustCache(pumpMsgBytes: ByteArray) {
        wearCommHandler?.obtainMessage()?.also { msg ->
            msg.what = WearCommServiceCodes.SEND_PUMP_COMMANDS_BUST_CACHE_BULK.ordinal
            msg.obj = pumpMsgBytes
            wearCommHandler?.sendMessage(msg)
        }
    }
    private fun handleCachedCommandsRequest(rawBytes: ByteArray) {
        wearCommHandler?.obtainMessage()?.also { msg ->
            msg.what = WearCommServiceCodes.CACHED_PUMP_COMMANDS_BULK.ordinal
            msg.obj = rawBytes
            wearCommHandler?.sendMessage(msg)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Toast.makeText(this, "WearX2 service destroyed", Toast.LENGTH_SHORT).show()
    }

    override fun onConnected(bundle: Bundle?) {
        Timber.i("service onConnected $bundle")
    }

    override fun onConnectionSuspended(id: Int) {
        Timber.i("service onConnectionSuspended: $id")
    }

    override fun onConnectionFailed(result: ConnectionResult) {
        Timber.i("service onConnectionFailed: $result")
    }


    private fun createNotification(): Notification {
        val notificationChannelId = "WearX2 Background Notification"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;
        val channel = NotificationChannel(
            notificationChannelId,
            "Endless Service notifications channel",
            NotificationManager.IMPORTANCE_LOW
        ).let {
            it.description = "Endless Service channel"
            it.vibrationPattern = LongArray(0)
            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, FLAG_IMMUTABLE)
        }

        val builder: Notification.Builder = Notification.Builder(
            this,
            notificationChannelId
        )

        return builder
            .setContentTitle("WearX2")
            .setContentText("WearX2 is running")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("WearX2 is running")
            .setPriority(Notification.PRIORITY_MIN) // for under android 26 compatibility
            .build()
    }
}