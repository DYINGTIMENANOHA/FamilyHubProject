package code.name.monkey.retromusic.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.LoginBody
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.SyncTuneSession
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import retrofit2.HttpException

class LoginActivity : AppCompatActivity() {

    private val TAG = "LoginActivity"
    private val api: SyncTuneApi by inject()

    private lateinit var etNickname: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_login)

        etNickname = findViewById(R.id.et_nickname)
        etPassword = findViewById(R.id.et_password)
        btnLogin = findViewById(R.id.btn_login)
        progress = findViewById(R.id.progress)

        btnLogin.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val nickname = etNickname.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""
        if (nickname.isEmpty() || password.isEmpty()) {
            showError("Nickname and password required")
            return
        }
        Log.i(TAG, "doLogin: nickname=$nickname deviceId=${SyncTuneSession.deviceId}")
        setLoading(true)
        lifecycleScope.launch {
            val body = LoginBody(
                nickname = nickname,
                password = password,
                device_id = SyncTuneSession.deviceId,
                device_name = deviceName(),
                platform = "android",
            )
            runCatching { api.login(body) }
                .onSuccess { resp ->
                    Log.i(TAG, "login success: id=${resp.id} nickname=${resp.nickname}")
                    SyncTuneSession.save(
                        id = resp.id,
                        nick = resp.nickname,
                        tok = resp.token,
                        deviceIdFromServer = resp.device_id,
                        maxDevicesFromServer = resp.max_devices,
                    )
                    goToMain()
                }
                .onFailure {
                    Log.e(TAG, "login error: ${it.message}")
                    setLoading(false)
                    showError(loginErrorMessage(it))
                }
        }
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }
    }

    private fun loginErrorMessage(error: Throwable): String {
        if (error is HttpException && error.code() == 403) {
            return "Device limit reached or account disabled"
        }
        if (error is HttpException && error.code() == 401) {
            return "Invalid nickname or password"
        }
        return "Login failed: ${error.message}"
    }

    private fun goToMain() {
        Log.i(TAG, "goToMain")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
    }

    private fun showError(msg: String) {
        Snackbar.make(btnLogin, msg, Snackbar.LENGTH_LONG).show()
    }
}
