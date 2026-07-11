package ee.lauluekraan

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {

    private lateinit var startPanel: LinearLayout
    private lateinit var sharePanel: ScrollView
    private lateinit var statusText: TextView
    private lateinit var roomCodeText: TextView
    private lateinit var viewerCountText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var startButton: MaterialButton

    private var socketClient: SocketClient? = null
    private var pendingRoomId: String? = null
    private val serverUrl by lazy { getString(R.string.server_url) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && pendingRoomId != null) {
            showSharingUI(pendingRoomId!!)
            ScreenShareService.frameSender = { image ->
                socketClient?.sendFrame(image)
            }
            val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_DATA, result.data)
                putExtra(ScreenShareService.EXTRA_ROOM_ID, pendingRoomId)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = getString(R.string.sharing_active)
        } else {
            socketClient?.stopSharing()
            resetUI()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        beginSharingFlow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startPanel = findViewById(R.id.startPanel)
        sharePanel = findViewById(R.id.sharePanel)
        statusText = findViewById(R.id.statusText)
        roomCodeText = findViewById(R.id.roomCodeText)
        viewerCountText = findViewById(R.id.viewerCountText)
        qrImage = findViewById(R.id.qrImage)
        startButton = findViewById(R.id.startButton)

        startButton.setOnClickListener { requestPermissionsAndStart() }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener { stopSharing() }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        beginSharingFlow()
    }

    private fun beginSharingFlow() {
        startButton.isEnabled = false
        statusText.text = getString(R.string.connecting)

        socketClient?.disconnect()
        socketClient = SocketClient(
            serverUrl = serverUrl,
            onViewerCount = { count ->
                runOnUiThread {
                    viewerCountText.text = getString(R.string.viewers, count)
                }
            },
            onDisconnected = {
                runOnUiThread {
                    statusText.text = "Ühendus katkenud"
                }
            },
        )

        socketClient?.connect {
            socketClient?.createRoom { roomId ->
                runOnUiThread {
                    pendingRoomId = roomId
                    requestScreenCapture()
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun showSharingUI(roomId: String) {
        startPanel.visibility = View.GONE
        sharePanel.visibility = View.VISIBLE
        roomCodeText.text = roomId

        val viewerUrl = "$serverUrl/view/$roomId"
        qrImage.setImageBitmap(generateQrBitmap(viewerUrl, 512))
    }

    private fun generateQrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun stopSharing() {
        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        }
        startService(stopIntent)
        ScreenShareService.frameSender = null
        socketClient?.stopSharing()
        socketClient?.disconnect()
        socketClient = null
        pendingRoomId = null
        resetUI()
    }

    private fun resetUI() {
        startPanel.visibility = View.VISIBLE
        sharePanel.visibility = View.GONE
        startButton.isEnabled = true
        statusText.text = ""
        viewerCountText.text = "0 vaatajat"
    }

    override fun onDestroy() {
        if (isFinishing) {
            ScreenShareService.frameSender = null
        }
        super.onDestroy()
    }
}
