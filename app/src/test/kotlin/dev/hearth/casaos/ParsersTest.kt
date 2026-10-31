package dev.hearth.casaos

import dev.hearth.casaos.data.AppState
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.DriveHealth
import dev.hearth.casaos.data.DriveKind
import dev.hearth.casaos.data.appState
import dev.hearth.casaos.data.normalizeBaseUrl
import dev.hearth.casaos.data.parseApps
import dev.hearth.casaos.data.parseDrives
import dev.hearth.casaos.data.parseFolder
import dev.hearth.casaos.data.parseUtilization
import dev.hearth.casaos.data.webUrl
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test fun `normalizeBaseUrl adds http and strips trailing junk`() {
        assertEquals("http://192.168.1.10", normalizeBaseUrl("192.168.1.10"))
        assertEquals("http://192.168.1.10", normalizeBaseUrl("  http://192.168.1.10/  "))
        assertEquals("http://casaos.local:8080", normalizeBaseUrl("casaos.local:8080/some/path"))
        assertEquals("https://nas.example.com", normalizeBaseUrl("https://nas.example.com:443"))
        assertEquals("http://nas.example.com:8443", normalizeBaseUrl("http://nas.example.com:8443"))
    }

    @Test fun `normalizeBaseUrl rejects garbage`() {
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("http://"))
        assertNull(normalizeBaseUrl("not a host"))
    }

    @Test fun `utilization parses a typical payload`() {
        val s = parseUtilization(
            json(
                """{"cpu":{"percent":12.5,"num":4,"temperature":47,"power":{},"model":"intel"},
                   "mem":{"total":8000000000,"available":4000000000,"used":3000000000,"free":1,"usedPercent":37.5},
                   "net":[{"name":"eth0","bytesSent":100,"bytesRecv":300,"state":"up","time":1},
                          {"name":"wlan0","bytesSent":10,"bytesRecv":20,"state":"down","time":1}],
                   "sys_disk":{"size":1000,"avail":400,"health":true,"used":600}}""",
            ),
            nowMs = 5,
        )
        assertEquals(12.5, s.cpuPercent, 0.0)
        assertEquals(4, s.cpuCores)
        assertEquals(47, s.cpuTempC)
        assertEquals(37.5, s.memPercent, 0.0)
        assertEquals(320L, s.netRxBytes)
        assertEquals(110L, s.netTxBytes)
        assertEquals(60.0, s.disk!!.percent, 0.0)
    }

    @Test fun `drives join volumes to their drive, system first and usb last`() {
        val drives = parseDrives(
            json(
                """{"disks":[
                     {"name":"sdb","size":4000000000000,"model":"WDC WD40EFRX ","health":"true","temperature":38,"disk_type":"HDD","path":"/dev/sdb"},
                     {"name":"nvme0n1","size":1000000000000,"model":"","health":"false","temperature":0,"disk_type":"SSD","path":"/dev/nvme0n1"}],
                   "storage":[
                     {"mount_point":"/","size":"64000000000","avail":"40000000000","type":"ext4","path":"/dev/sda2","drive_name":"System"},
                     {"mount_point":"/media/sdb1","size":4000000000000,"avail":1000000000000,"type":"ext4","path":"/dev/sdb1","drive_name":"sdb"}],
                   "avail":[]}""",
            ),
            json(
                """[{"name":"sdc","size":128000000000,"model":"SanDisk","avail":100,
                     "children":[{"name":"sdc1","size":128000000000,"avail":28000000000,"mount_point":"/media/sdc1"}]},
                    {"name":"sda","size":64000000000,"model":"Boot stick","children":[]}]""",
            ),
        )
        assertEquals(listOf("System", "sdb", "nvme0n1", "sdc"), drives.map { it.name }) // sda is the system drive
        val system = drives[0]
        assertEquals(DriveKind.System, system.kind)
        assertEquals(24_000_000_000L, system.volumes.single().usedBytes) // sizes sent as strings
        val hdd = drives[1]
        assertEquals("WDC WD40EFRX", hdd.model)
        assertEquals(DriveKind.Hdd, hdd.kind)
        assertEquals(DriveHealth.Healthy, hdd.health)
        assertEquals(38, hdd.temperatureC)
        assertEquals(75.0, hdd.volumes.single().percent, 0.0)
        val ssd = drives[2]
        assertNull(ssd.model)
        assertEquals(DriveKind.Ssd, ssd.kind)
        assertEquals(DriveHealth.Failing, ssd.health)
        assertNull(ssd.temperatureC)
        assertTrue(ssd.volumes.isEmpty())
        val usb = drives[3]
        assertEquals(DriveKind.Usb, usb.kind)
        assertEquals(DriveHealth.Unknown, usb.health)
        assertEquals("/media/sdc1", usb.volumes.single().mountPoint)
    }

    @Test fun `drives tolerate missing sections`() {
        assertTrue(parseDrives(null, null).isEmpty())
        assertTrue(parseDrives(json("""{"disks":null}"""), json("""{"oops":1}""")).isEmpty())
        val d = parseDrives(json("""{"disks":[{"name":"sda"}]}"""), null).single()
        assertEquals(DriveHealth.Unknown, d.health)
        assertEquals(0L, d.sizeBytes)
    }

    @Test fun `utilization tolerates missing sections and zero temperature`() {
        val s = parseUtilization(json("""{"cpu":{"percent":3,"temperature":0},"mem":{"total":100,"used":25}}"""), 0)
        assertNull(s.cpuTempC)
        assertNull(s.disk)
        assertEquals(25.0, s.memPercent, 0.0) // derived when usedPercent is absent
        assertEquals(0L, s.netRxBytes)
    }

    @Test fun `utilization rejects a non-object payload`() {
        assertThrows(CasaException.Parse::class.java) { parseUtilization(json("\"oops\""), 0) }
    }

    private val appsJson = """
        {
          "jellyfin": {
            "status": "running", "update_available": true,
            "store_info": {"title": {"en_us": "Jellyfin", "zh_cn": "x"}, "tagline": {"en_us": "Media"},
                           "icon": "https://cdn.example/jf.png", "index": "/web", "port_map": "8096", "category": "Media"},
            "compose": {"services": {}}
          },
          "custom-thing": {
            "status": "exited",
            "compose": {"services": {"web": {"ports": [{"target": 80, "published": "9000"}]}}}
          },
          "broken": "not-an-object"
        }"""

    @Test fun `apps parse store apps and custom compose apps`() {
        val apps = parseApps(json(appsJson))
        assertEquals(listOf("custom-thing", "Jellyfin"), apps.map { it.name }) // sorted case-insensitively, junk skipped

        val jf = apps.first { it.id == "jellyfin" }
        assertEquals(AppState.Running, jf.state)
        assertTrue(jf.updateAvailable)
        assertEquals("8096", jf.webPort)
        assertEquals("/web", jf.webPath)
        assertEquals("https://cdn.example/jf.png", jf.iconUrl)
        assertEquals("http://192.168.1.10:8096/web", jf.webUrl("http://192.168.1.10"))

        val custom = apps.first { it.id == "custom-thing" }
        assertEquals(AppState.Stopped, custom.state)
        assertEquals("9000", custom.webPort) // falls back to the first published compose port
        assertFalse(custom.updateAvailable)
        assertNull(custom.iconUrl)
    }

    @Test fun `apps carry their main container's image`() {
        val apps = parseApps(
            json(
                """{
                  "a": {"status": "running", "store_info": {"main": "app"},
                        "compose": {"services": {"db": {"image": "postgres:16"}, "app": {"image": "linuxserver/jellyfin:10.9.1"}}}},
                  "b": {"status": "running", "compose": {"x-casaos": {"main": "web"}, "services": {"db": {"image": "redis"}, "web": {"image": "nginx:1.27"}}}},
                  "c": {"status": "running", "compose": {"services": {"only": {"image": "ghcr.io/o/x:2"}}}},
                  "d": {"status": "running"}
                }""",
            ),
        ).associateBy { it.id }
        assertEquals("linuxserver/jellyfin:10.9.1", apps.getValue("a").image)
        assertEquals("nginx:1.27", apps.getValue("b").image)
        assertEquals("ghcr.io/o/x:2", apps.getValue("c").image)
        assertNull(apps.getValue("d").image)
    }

    @Test fun `an app renamed in its settings shows the new name`() {
        val apps = parseApps(json("""{"jf": {"status": "running", "store_info": {"title": {"en_us": "Jellyfin", "custom": "Movies"}}}}"""))
        assertEquals("Movies", apps.single().name)
    }

    @Test fun `webUrl is null without a port and ignores the server port`() {
        val app = parseApps(json("""{"x":{"status":"running"}}""")).single()
        assertNull(app.webUrl("http://10.0.0.2"))
        val withPort = parseApps(json("""{"x":{"status":"running","store_info":{"port_map":"3000"}}}""")).single()
        assertEquals("http://10.0.0.2:3000/", withPort.webUrl("http://10.0.0.2:8080"))
    }

    @Test fun `appState maps CasaOS statuses`() {
        assertEquals(AppState.Running, appState("running"))
        assertEquals(AppState.Running, appState("Up 3 hours"))
        assertEquals(AppState.Restarting, appState("restarting"))
        assertEquals(AppState.Stopped, appState("exited"))
        assertEquals(AppState.Stopped, appState("stopped"))
        assertEquals(AppState.Unknown, appState(""))
        assertEquals(AppState.Unknown, appState("???"))
    }

    @Test fun `folder listing puts directories first then sorts by name`() {
        val listing = parseFolder(
            "/DATA",
            json(
                """{"content":[
                  {"name":"b.txt","is_dir":false,"size":5,"path":"/DATA/b.txt","modified":"2026-01-02T03:04:05Z"},
                  {"name":"Zeta","is_dir":true,"path":"/DATA/Zeta"},
                  {"name":"a.txt","is_dir":false,"size":1},
                  {"name":"alpha","is_dir":true,"path":"/DATA/alpha"},
                  {"size":9}
                ],"total":5}""",
            ),
        )
        assertEquals(listOf("alpha", "Zeta", "a.txt", "b.txt"), listing.entries.map { it.name })
        assertEquals("/DATA/a.txt", listing.entries[2].path) // synthesized when the server omits it
    }
}
