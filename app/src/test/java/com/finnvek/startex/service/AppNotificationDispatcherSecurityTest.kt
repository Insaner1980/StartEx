package com.finnvek.startex.service

import com.finnvek.startex.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNotificationDispatcherSecurityTest {
    @Test
    fun `notification open intent explicitly targets MainActivity`() {
        val context = RuntimeEnvironment.getApplication()

        val intent = notificationOpenAppIntent(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
    }
}
