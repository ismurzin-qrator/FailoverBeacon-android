package net.qrator.failoverbeacon.failover.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.ManualScheduler
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.model.ConnectOutcome
import net.qrator.failoverbeacon.failover.model.DnsState
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.MaxAge
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnownNetworksPersistenceTest {
    private val dir = createTempDirectory().toFile()
    private val file = File(dir, "failoverbeacon-failover.json")
    private val configs = listOf(hostConfig(HOST))
    private val storage = ManualExecutor()
    private val scheduler = ManualScheduler()
    private val networks = KnownNetworks(configs, maxNetworks = 2)
    private val persistence = newPersistence(networks)
    private val state get() = networks.of(WIFI)

    private fun newPersistence(
        networks: KnownNetworks,
        hostConfigs: List<FailoverHostConfig> = configs
    ) = KnownNetworksPersistence(
        cacheDir = { dir },
        networks = networks,
        hostConfigs = hostConfigs,
        clock = { 10L },
        bodyProbeMaxAge = MaxAge({ 10L }, millis = 50),
        storage = storage,
        scheduler = scheduler
    )

    private fun answer(vararg last: Int) = state.update(HOST) { host ->
        host.withDns(DnsState().withAnswer(last.map(::ip), atMillis = 10))
    }

    private fun runSave() {
        scheduler.runAll()
        storage.runAll()
    }

    @AfterTest
    fun tearDown() {
        scheduler.shutdownNow()
        dir.deleteRecursively()
    }

    @Test
    fun `save requests share one scheduled save and a later request schedules the next`() {
        answer(1)
        persistence.saveLater()
        persistence.saveLater()
        val scheduled = scheduler.queued
        val savedBeforeRun = file.exists()

        runSave()
        persistence.saveLater()

        assertEquals(1, scheduled)
        assertFalse(savedBeforeRun)
        assertTrue(file.exists())
        assertEquals(1, scheduler.queued)
    }

    @Test
    fun `a save takes what happened after the request`() {
        persistence.saveLater()
        answer(1)
        runSave()
        val restarted = KnownNetworks(configs, maxNetworks = 2)

        newPersistence(restarted).restore()
        storage.runAll()

        assertEquals(listOf(ip(1)), restarted.of(WIFI)[HOST]?.ips)
    }

    @Test
    fun `restore and clear run on the storage thread`() {
        persistence.restore()
        persistence.clear()

        assertEquals(2, storage.queued)
    }

    @Test
    fun `without hosts a file of an earlier config is deleted and nothing is read or saved`() {
        answer(1)
        persistence.saveLater()
        runSave()
        val withoutHosts = newPersistence(KnownNetworks(emptyList(), maxNetworks = 2), emptyList())

        withoutHosts.restore()
        storage.runAll()
        withoutHosts.saveLater()

        assertFalse(file.exists())
        assertEquals(0, scheduler.queued)
    }

    @Test
    fun `nothing to save leaves no file`() {
        state
        persistence.saveLater()
        runSave()

        assertFalse(file.exists())
    }

    @Test
    fun `a change of nothing saved does not touch the file`() {
        answer(1)
        persistence.saveLater()
        runSave()
        file.delete()

        state.update(HOST) {
            it.withConnectOutcome(ip(1).hostAddress!!, ConnectOutcome.TLS_FAILED)
        }
        persistence.saveLater()
        runSave()

        assertFalse(file.exists())
    }

    @Test
    fun `an unchanged state after a restore does not touch the file`() {
        answer(1)
        persistence.saveLater()
        runSave()
        val restarted = KnownNetworks(configs, maxNetworks = 2)
        val restartedPersistence = newPersistence(restarted)
        restartedPersistence.restore()
        storage.runAll()
        file.delete()

        restarted.of(WIFI).update(HOST) {
            it.withConnectOutcome(ip(1).hostAddress!!, ConnectOutcome.CONNECTED_SECURE)
        }
        restartedPersistence.saveLater()
        runSave()

        assertFalse(file.exists())
    }

    @Test
    fun `clear deletes the file and the state, and a pending save has nothing to write`() {
        answer(1)
        persistence.saveLater()
        runSave()
        answer(2)
        persistence.saveLater()

        persistence.clear()
        storage.runAll()
        assertFalse(file.exists())
        runSave()

        assertFalse(file.exists())
        assertEquals(emptyList(), state[HOST]?.ips)
    }

    private companion object {
        const val HOST = "a.com"
        const val WIFI = "WIFI/192.0.2"
    }
}
