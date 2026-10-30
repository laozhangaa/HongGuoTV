package com.hongguotv.core

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PlaybackDeviceSessionTest {
    private val http=OkHttpClient()

    @Test fun playbackAndPrefetchShareOneRegistrationEvenWhenTheyStartTogether() {
        val registrations=AtomicInteger()
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val session=PlaybackDeviceSession {
            registrations.incrementAndGet(); entered.countDown()
            check(release.await(2,TimeUnit.SECONDS))
            DeviceRegister.Registered("123","456")
        }
        val workers=Executors.newFixedThreadPool(2)
        try {
            val first=workers.submit<PlaybackDeviceSession.Profile> { session.profile(http) }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val next=workers.submit<PlaybackDeviceSession.Profile> { session.profile(http) }
            release.countDown()
            assertSame(first.get(2,TimeUnit.SECONDS),next.get(2,TimeUnit.SECONDS))
            assertEquals(1,registrations.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun failedRegistrationCanBeRetriedAndNeverFallsBackToTheFixedDevice() {
        val registrations=AtomicInteger()
        val session=PlaybackDeviceSession {
            if(registrations.incrementAndGet()==1) null else DeviceRegister.Registered("123","456")
        }
        assertThrows(PlaybackDeviceRegistrationException::class.java) { session.profile(http) }
        assertEquals("123",session.profile(http).values["device_id"])
        assertEquals(2,registrations.get())
    }

    @Test fun simultaneousEmptyResponsesReplaceTheFailedProfileOnlyOnce() {
        val registrations=AtomicInteger()
        val session=PlaybackDeviceSession {
            val n=registrations.incrementAndGet()
            DeviceRegister.Registered("${100+n}","${200+n}")
        }
        val failed=session.profile(http)
        val workers=Executors.newFixedThreadPool(2)
        val start=CountDownLatch(1)
        try {
            val requests=(1..2).map { workers.submit<PlaybackDeviceSession.Profile?> { start.await(); session.recoverAfterEmpty(http,failed) } }
            start.countDown()
            val recovered=requests.first().get(2,TimeUnit.SECONDS)
            assertNotSame(failed,recovered)
            assertSame(recovered,requests.last().get(2,TimeUnit.SECONDS))
            assertSame(recovered,session.profile(http))
            assertEquals(2,registrations.get())
        } finally { start.countDown(); workers.shutdownNow() }
    }

    @Test fun cancelledRecoveryKeepsTheCurrentSession() {
        val registrations=AtomicInteger()
        val session=PlaybackDeviceSession { registrations.incrementAndGet(); DeviceRegister.Registered("123","456") }
        val failed=session.profile(http)
        val scope=RequestScope()
        assertThrows(InterruptedIOException::class.java) { scope.run { scope.cancel(); session.recoverAfterEmpty(http,failed) } }
        assertSame(failed,session.profile(http))
        assertEquals(1,registrations.get())
    }

    @Test fun malformedRegistrationIsNotCached() {
        for(id in listOf("0","","-1","123x","1".repeat(31))) {
            val session=PlaybackDeviceSession { DeviceRegister.Registered(id,"456") }
            assertThrows(PlaybackDeviceRegistrationException::class.java) { session.profile(http) }
        }
    }

    @Test fun cancellationDoesNotRegisterOrPublishAnIdentity() {
        var registrations=0
        val session=PlaybackDeviceSession { registrations++; DeviceRegister.Registered("123","456") }
        val scope=RequestScope()
        assertThrows(InterruptedIOException::class.java) { scope.run { scope.cancel(); session.profile(http) } }
        assertEquals(0,registrations)
        assertEquals("123",session.profile(http).values["device_id"])
        assertEquals(1,registrations)
    }
}
