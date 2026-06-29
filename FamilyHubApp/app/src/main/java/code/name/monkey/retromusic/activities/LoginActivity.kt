package code.name.monkey.retromusic.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.LoginBody
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.RegisterBody
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.SyncTuneSession
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class LoginActivity : AppCompatActivity() {

    private val TAG = "LoginActivity"
    private val api: SyncTuneApi by inject()

    private lateinit var etNickname: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnRegister: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_login)

        etNickname = findViewById(R.id.et_nickname)
        etPassword = findViewById(R.id.et_password)
        btnLogin = findViewById(R.id.btn_login)
        btnRegister = findViewById(R.id.btn_register)
        progress = findViewById(R.id.progress)

        btnLogin.setOnClickListener { doLogin() }
        btnRegister.setOnClickListener { doRegister() }
    }

    private fun doLogin() {
        val nickname = etNickname.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""
        if (nickname.isEmpty() || password.isEmpty()) {
            showError("Nickname and password required")
            return
        }
        Log.i(TAG, "doLogin: nickname=$nickname")
        setLoading(true)
        lifecycleScope.launch {
            runCatching { api.login(LoginBody(nickname, password)) }
                .onSuccess { resp ->
                    Log.i(TAG, "login success: id=${resp.id} nickname=${resp.nickname}")
                    SyncTuneSession.save(resp.id, resp.nickname, resp.token)
                    goToMain()
                }
                .onFailure {
                    Log.e(TAG, "login error: ${it.message}")
                    setLoading(false)
                    showError("Login failed: ${it.message}")
                }
        }
    }

    private fun doRegister() {
        val nickname = etNickname.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""
        if (nickname.isEmpty() || password.isEmpty()) {
            showError("Nickname and password required")
            return
        }
        if (password.length < 4) {
            showError("Password must be at least 4 characters")
            return
        }
        Log.i(TAG, "doRegister: nickname=$nickname")
        setLoading(true)
        lifecycleScope.launch {
            runCatching { api.register(RegisterBody(nickname, password)) }
                .onSuccess { resp ->
                    Log.i(TAG, "register success: id=${resp.id} nickname=${resp.nickname}")
                    SyncTuneSession.save(resp.id, resp.nickname, resp.token)
                    goToMain()
                }
                .onFailure {
                    Log.e(TAG, "register error: ${it.message}")
                    setLoading(false)
                    showError("Register failed: ${it.message}")
                }
        }
    }

    private fun goToMain() {
        Log.i(TAG, "goToMain")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
        btnRegister.isEnabled = !loading
    }

    private fun showError(msg: String) {
        Snackbar.make(btnLogin, msg, Snackbar.LENGTH_LONG).show()
    }
}
