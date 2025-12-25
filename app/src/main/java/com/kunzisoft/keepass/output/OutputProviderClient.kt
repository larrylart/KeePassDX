package com.kunzisoft.keepass.output

import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.kunzisoft.keepass.settings.PreferencesUtil
import org.keepassdx.output.IOutputCredentialsService
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import android.os.Handler
import android.os.Looper

private const val TAG = "KDX-OutputProvider"

class OutputProviderClient(private val context: Context) {

    private var service: IOutputCredentialsService? = null
    private var isBinding = false
    private var boundComponent: ComponentName? = null

    // Queue requests that arrive before we’re connected
    private val pending = CopyOnWriteArrayList<() -> Unit>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IOutputCredentialsService.Stub.asInterface(binder)
            isBinding = false
            boundComponent = name

            // Flush queued sends
            val toRun = pending.toList()
            pending.clear()
            toRun.forEach { it.invoke() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            boundComponent = null
        }
    }

	////////////////////////////////////////////////////////////////
	fun sendWithResult(
		mode: String,
		username: String?,
		password: String?,
		otp: String?,
		entryTitle: String?,
		entryUuid: String?,
		onResult: (rc: Int) -> Unit
	) {
		// always report back on main thread
		fun post(rc: Int) {
			Handler(Looper.getMainLooper()).post { onResult(rc) }
		}

		if (!PreferencesUtil.isOutputProviderEnabled(context)) {
			Log.d(TAG, "Output provider disabled")
			post(1)
			return
		}

		val comp = PreferencesUtil.getOutputProviderComponentName(context)
		if (comp == null) {
			Log.d(TAG, "No output provider selected")
			post(2)
			return
		}

		fun doSend(s: IOutputCredentialsService) {
			val requestId = UUID.randomUUID().toString()
			val rc = try {
				s.sendPayload(requestId, mode, username, password, otp, entryTitle, entryUuid)
			} catch (t: Throwable) {
				Log.e(TAG, "sendPayload failed: ${t.message}", t)
				99
			}
			Log.d(TAG, "sendPayload rc=$rc mode=$mode")
			post(rc)
		}

		val s = service
		if (s != null && boundComponent == comp) {
			doSend(s)
			return
		}

		pending.add {
			val s2 = service
			if (s2 != null) doSend(s2) else post(98)
		}

		if (!isBinding) {
			isBinding = true
			bindTo(comp)
		}
	}


    private fun bindTo(component: ComponentName) {
        // Optional: if we were bound to something else, unbind first
        try {
            if (boundComponent != null) context.unbindService(conn)
        } catch (_: Throwable) { /* ignore */ }

        // Verification is controlled by your KeePassDX toggle
        val verify = PreferencesUtil.isOutputProviderVerifyEnabled(context)
        if (verify) {
            if (!isProviderTrusted(context.packageManager, component.packageName)) {
                Log.w(TAG, "Provider signature not trusted: ${component.packageName}")
                isBinding = false
                pending.clear()
                return
            }
        }

        val i = Intent().apply { this.component = component }
        val ok = context.bindService(i, conn, Context.BIND_AUTO_CREATE)
        if (!ok) {
            Log.w(TAG, "bindService failed to $component")
            isBinding = false
            pending.clear()
        }
    }

    private fun callSend(
        s: IOutputCredentialsService,
        mode: String,
        username: String?,
        password: String?,
        otp: String?,
        entryTitle: String?,
        entryUuid: String?
    ) {
        val requestId = UUID.randomUUID().toString()
        try {
            val rc = s.sendPayload(requestId, mode, username, password, otp, entryTitle, entryUuid)
            Log.d(TAG, "sendPayload rc=$rc mode=$mode")
        } catch (t: Throwable) {
            Log.e(TAG, "sendPayload failed: ${t.message}", t)
            // Optionally: you can set your “disabled by error” flag here
        }
    }

    ////////////////////////////////////////////////////////////////
    // Cert pinning check. When verification is ON, only allow your BluKeyborg signing cert(s).
    // When verification is OFF, this method is never called.
    ////////////////////////////////////////////////////////////////
    private fun isProviderTrusted(pm: PackageManager, pkg: String): Boolean {
        val sha = getSigningCertSha256(pm, pkg) ?: return false
        // TODO: replace with your BluKeyborg, inputstick cert hash(es)
        val allowed = setOf(
            "REPLACE_WITH_BLUKKEYBORG_CERT_SHA256_HEX"
        )
        return allowed.contains(sha)
    }

	////////////////////////////////////////////////////////////////
	private fun getSigningCertSha256(pm: PackageManager, packageName: String): String? {
		return try {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
				val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
				val si = pi.signingInfo ?: return null

				val signer = if (si.hasMultipleSigners()) {
					si.apkContentsSigners.firstOrNull()
				} else {
					si.signingCertificateHistory.firstOrNull()
				} ?: return null

				val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
				digest.joinToString("") { b -> "%02x".format(b) }
			} else {
				@Suppress("DEPRECATION")
				val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
				@Suppress("DEPRECATION")
				val sig = pi.signatures?.firstOrNull() ?: return null

				val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
				digest.joinToString("") { b -> "%02x".format(b) }
			}
		} catch (_: Throwable) {
			null
		}
	}

    fun release() {
        pending.clear()
        try { context.unbindService(conn) } catch (_: Throwable) {}
        service = null
        boundComponent = null
        isBinding = false
    }
}
