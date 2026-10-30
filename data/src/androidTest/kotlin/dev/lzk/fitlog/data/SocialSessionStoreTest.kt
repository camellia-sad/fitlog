package dev.lzk.fitlog.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialSessionStoreTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val namespace="social-session-test"
    @After fun clear() { SocialSessionStore(context,namespace).clear() }
    @Test fun secretsAreEncryptedAndRecoveredAcrossStoreInstances() {
        val store=SocialSessionStore(context,namespace)
        store.clear()
        store.write("""{"access_token":"test-only-access","refresh_token":"test-only-refresh"}""")
        val raw=context.getSharedPreferences("$namespace.social",Context.MODE_PRIVATE).getString("session",null)!!
        assertFalse(raw.contains("test-only"))
        assertEquals(store.read(),SocialSessionStore(context,namespace).read())
    }
    @Test fun tamperedCiphertextIsDiscardedAndLogoutRemovesSession() {
        val store=SocialSessionStore(context,namespace)
        store.write("test-only")
        context.getSharedPreferences("$namespace.social",Context.MODE_PRIVATE).edit().putString("session","invalid").commit()
        assertNull(store.read())
        store.write("test-only")
        store.clear()
        assertNull(SocialSessionStore(context,namespace).read())
    }
}
