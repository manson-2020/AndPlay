package com.shilapi.xcertplay

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mfi.MfiProtocolMajorResult
import com.shilapi.xcertplay.mfi.MfiSelfCheck
import com.shilapi.xcertplay.mfi.MfiSelfCheckResult
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.ui.theme.XcertplayTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var status by mutableStateOf<DiagnosticStatus>(DiagnosticStatus.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XcertplayTheme {
                val context = LocalContext.current
                var devicePath by remember { mutableStateOf("/dev/i2c-1") }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier.padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.i2c_title))
                        OutlinedTextField(
                            value = devicePath,
                            onValueChange = { devicePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.i2c_device_label)) },
                            singleLine = true,
                            enabled = status !is DiagnosticStatus.Running,
                        )
                        Button(
                            onClick = { runSelfCheck(devicePath) },
                            enabled = status !is DiagnosticStatus.Running,
                        ) {
                            Text(stringResource(R.string.i2c_run_check))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(status.message(context))
                        Text(stringResource(R.string.i2c_ch341_note))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSelfCheck(devicePath: String) {
        status = DiagnosticStatus.Running
        executor.execute {
            val next = try {
                LinuxI2cTransport.open(devicePath).use { MfiSelfCheck(it).run() }
                    .let { DiagnosticStatus.Result(it) }
            } catch (error: LinkageError) {
                DiagnosticStatus.Failure(error.message ?: "I2C native library is unavailable") // Diagnostics-only fallback text.
            } catch (error: Exception) {
                DiagnosticStatus.Failure(error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status = next
            }
        }
    }
}

private sealed class DiagnosticStatus {
    data object Idle : DiagnosticStatus()
    data object Running : DiagnosticStatus()
    data class Result(val selfCheck: MfiSelfCheckResult) : DiagnosticStatus()
    data class Failure(val message: String) : DiagnosticStatus()

    fun message(context: Context): String = when (this) {
        Idle -> context.getString(R.string.diag_idle)
        Running -> context.getString(R.string.diag_running)
        is Failure -> context.getString(R.string.diag_failed, message)
        is Result -> {
            val chip = selfCheck.chip ?: return context.getString(
                if (selfCheck.discovery.interrupted) R.string.diag_scan_interrupted else R.string.diag_found_none,
            )
            val major = when (val result = chip.protocolMajor) {
                is MfiProtocolMajorResult.Value -> "%d".format(result.major)
                is MfiProtocolMajorResult.MfiFailure -> result.error.message ?: result.error.javaClass.simpleName
                is MfiProtocolMajorResult.TransportFailure -> result.error.message ?: result.error.javaClass.simpleName
            }
            context.getString(
                R.string.diag_found_chip,
                "0x%02X".format(chip.address7Bit),
                "0x%02X".format(chip.deviceVersion),
                major,
            )
        }
    }
}
