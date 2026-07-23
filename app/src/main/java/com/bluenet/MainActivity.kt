package com.bluenet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bluenet.client.BlueNetVpnService
import com.bluenet.databinding.ActivityMainBinding
import com.bluenet.host.HostService
import com.bluenet.utils.BluetoothUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var hostService: HostService? = null
    private var isHostBound = false

    private var vpnService: BlueNetVpnService? = null
    private var isVpnBound = false

    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private val hostServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HostService.HostBinder
            hostService = binder.getService()
            isHostBound = true
            updateHostUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostService = null
            isHostBound = false
            updateHostUi()
        }
    }

    private val vpnServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BlueNetVpnService.VpnBinder
            vpnService = binder.getService()
            isVpnBound = true
            updateClientUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            isVpnBound = false
            updateClientUi()
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startClientVpn()
        } else {
            Toast.makeText(this, "VPN Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestBluetoothPermissions()
        setupHostUi()
        setupClientUi()

        val hostIntent = Intent(this, HostService::class.java)
        bindService(hostIntent, hostServiceConnection, Context.BIND_AUTO_CREATE)

        val vpnIntent = Intent(this, BlueNetVpnService::class.java)
        bindService(vpnIntent, vpnServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun setupHostUi() {
        binding.btnToggleHost.setOnClickListener {
            val service = hostService ?: return@setOnClickListener
            if (service.isServerRunning) {
                service.stopHostServer()
                updateHostUi()
            } else {
                val intent = Intent(this, HostService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                service.startHostServer { statusText, psm ->
                    runOnUiThread {
                        binding.tvHostStatus.text = statusText
                        updateHostUi()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupClientUi() {
        pairedDevices = BluetoothUtils.getPairedDevices(this)
        val deviceNames = pairedDevices.map { "${it.name ?: "Unknown"} (${it.address})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spDevices.adapter = adapter

        binding.btnToggleClient.setOnClickListener {
            val service = vpnService ?: return@setOnClickListener
            if (service.isVpnConnected) {
                service.stopVpn()
                updateClientUi()
            } else {
                checkVpnPermissionAndConnect()
            }
        }
    }

    private fun checkVpnPermissionAndConnect() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startClientVpn()
        }
    }

    private fun startClientVpn() {
        val selectedIndex = binding.spDevices.selectedItemPosition
        if (selectedIndex < 0 || selectedIndex >= pairedDevices.size) {
            Toast.makeText(this, "Select a paired Bluetooth device", Toast.LENGTH_SHORT).show()
            return
        }

        val psmText = binding.etPsm.text.toString()
        val psm = psmText.toIntOrNull()
        if (psm == null || psm <= 0) {
            Toast.makeText(this, "Enter a valid PSM integer from Host phone", Toast.LENGTH_SHORT).show()
            return
        }

        val device = pairedDevices[selectedIndex]
        val vpnIntent = Intent(this, BlueNetVpnService::class.java)
        startService(vpnIntent)

        vpnService?.connectToHost(device.address, psm) { statusText ->
            runOnUiThread {
                binding.tvClientStatus.text = statusText
                updateClientUi()
            }
        }
    }

    private fun updateHostUi() {
        val isRunning = hostService?.isServerRunning == true
        if (isRunning) {
            binding.btnToggleHost.text = getString(R.string.btn_stop_host)
            binding.tvHostStatus.text = "Host Server Active on PSM: ${hostService?.currentPsm}"
        } else {
            binding.btnToggleHost.text = getString(R.string.btn_start_host)
            binding.tvHostStatus.text = getString(R.string.status_idle)
        }
    }

    private fun updateClientUi() {
        val isConnected = vpnService?.isVpnConnected == true
        if (isConnected) {
            binding.btnToggleClient.text = getString(R.string.btn_disconnect_client)
        } else {
            binding.btnToggleClient.text = getString(R.string.btn_connect_client)
        }
    }

    override fun onDestroy() {
        if (isHostBound) {
            unbindService(hostServiceConnection)
            isHostBound = false
        }
        if (isVpnBound) {
            unbindService(vpnServiceConnection)
            isVpnBound = false
        }
        super.onDestroy()
    }
}
