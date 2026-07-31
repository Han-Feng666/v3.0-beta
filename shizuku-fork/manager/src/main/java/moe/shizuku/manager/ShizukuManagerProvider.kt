package moe.shizuku.manager

import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ShizukuManagerProvider : ShizukuProvider() {

    companion object {
        private const val TAG = "SManagerProvider"
        private const val EXTRA_BINDER = "com.HanFeng.shizuku.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    }

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null && method != ShizukuProvider.METHOD_SEND_BINDER && method != METHOD_GET_BINDER) return null

        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                val inputBundle = extras ?: Bundle.EMPTY
                inputBundle.classLoader = BinderContainer::class.java.classLoader

                val token = inputBundle.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val binder = inputBundle.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

                val countDownLatch = CountDownLatch(1)
                var reply: Bundle? = Bundle()

                val listener = object : Shizuku.OnBinderReceivedListener {

                    override fun onBinderReceived() {
                        try {
                            Shizuku.attachUserService(binder, bundleOf(
                                USER_SERVICE_ARG_TOKEN to token
                            ))
                            val currentBinder = Shizuku.getBinder() ?: run {
                                Log.w(TAG, "attachUserService $token: binder null after attach")
                                reply = null
                                Shizuku.removeBinderReceivedListener(this)
                                countDownLatch.countDown()
                                return
                            }
                            reply?.putParcelable(EXTRA_BINDER, BinderContainer(currentBinder))
                        } catch (e: Throwable) {
                            Log.e(TAG, "attachUserService $token", e)
                            reply = null
                        }

                        Shizuku.removeBinderReceivedListener(this)

                        countDownLatch.countDown()
                    }
                }

                Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

                return try {
                    countDownLatch.await(5, TimeUnit.SECONDS)
                    reply
                } catch (e: TimeoutException) {
                    Log.e(TAG, "Binder not received in 5s", e)
                    null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "sendUserService", e)
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }
}
