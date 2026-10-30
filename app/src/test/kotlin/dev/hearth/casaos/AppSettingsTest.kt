package dev.hearth.casaos

import dev.hearth.casaos.data.AppSettings
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.DeviceMapping
import dev.hearth.casaos.data.EnvVar
import dev.hearth.casaos.data.PortMapping
import dev.hearth.casaos.data.VolumeMapping
import dev.hearth.casaos.data.applyAppSettings
import dev.hearth.casaos.data.bytesOf
import dev.hearth.casaos.data.loadComposeYaml
import dev.hearth.casaos.data.looksSecret
import dev.hearth.casaos.data.normalized
import dev.hearth.casaos.data.parseAppSettings
import dev.hearth.casaos.data.splitImage
import dev.hearth.casaos.data.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    /** Shaped like CasaOS's own output (compose-go marshalled): long-syntax lists, `$$` escapes, memory in bytes. */
    private val casaYaml = """
        name: jellyfin
        services:
          jellyfin:
            cap_add:
              - NET_ADMIN
            container_name: jellyfin
            cpu_shares: 50
            deploy:
              resources:
                limits:
                  memory: "268435456"
                reservations:
                  devices: []
            devices:
              - /dev/dri:/dev/dri
            environment:
              PGID: "1000"
              PUID: "1000"
              DB_PASSWORD: pa${'$'}${'$'}word
              TZ: Europe/Berlin
            healthcheck:
              test: ["CMD", "curl", "-f", "http://localhost:8096"]
            image: linuxserver/jellyfin:10.8.13
            labels:
              icon: https://cdn.example/jellyfin.png
            network_mode: bridge
            ports:
              - mode: ingress
                host_ip: 0.0.0.0
                target: 8096
                published: "8097"
                protocol: tcp
              - target: 7359
                published: "7359"
                protocol: udp
            restart: unless-stopped
            volumes:
              - type: bind
                source: /DATA/AppData/jellyfin/config
                target: /config
                bind:
                  create_host_path: true
              - type: bind
                source: /DATA/Media
                target: /media
                read_only: true
            x-casaos:
              envs:
                - container: TZ
                  description:
                    en_us: Time zone
              ports:
                - container: "8096"
                  description:
                    en_us: Web UI
        x-casaos:
          author: self
          index: /
          main: jellyfin
          port_map: "8097"
          scheme: http
          icon: https://cdn.example/jellyfin.png
          title:
            en_us: Jellyfin
          tips:
            before_install:
              en_us: Read the docs
    """.trimIndent()

    private fun main(s: AppSettings) = s.services.single()

    @Test fun `reads everything the dashboard shows`() {
        val s = parseAppSettings(casaYaml)
        assertEquals("Jellyfin", s.app.title)
        assertEquals("8097", s.app.port)
        assertEquals("/", s.app.path)
        assertEquals("http", s.app.scheme)

        val j = main(s)
        assertEquals("linuxserver/jellyfin", j.image)
        assertEquals("10.8.13", j.tag)
        assertEquals("jellyfin", j.containerName)
        assertEquals("bridge", j.network)
        assertEquals(listOf(PortMapping("8097", "8096", "tcp", 0), PortMapping("7359", "7359", "udp", 1)), j.ports)
        assertEquals(
            listOf(
                VolumeMapping("/DATA/AppData/jellyfin/config", "/config", false, 0),
                VolumeMapping("/DATA/Media", "/media", true, 1),
            ),
            j.volumes,
        )
        assertEquals("Europe/Berlin", j.environment.first { it.key == "TZ" }.value)
        assertEquals(listOf(DeviceMapping("/dev/dri", "/dev/dri", 0)), j.devices)
        assertEquals("256", j.memoryMb)
        assertEquals(50, j.cpuShares)
        assertEquals("unless-stopped", j.restart)
        assertEquals(listOf("NET_ADMIN"), j.capAdd)
        assertFalse(j.privileged)
        assertEquals("Web UI", j.hints.ports["8096"])
        assertEquals("Time zone", j.hints.environment["TZ"])
    }

    @Test fun `CasaOS's doubled dollar signs show as one`() {
        val env = main(parseAppSettings(casaYaml)).environment.first { it.key == "DB_PASSWORD" }
        assertEquals("pa\$word", env.value)
    }

    @Test fun `reads hand-written short syntax`() {
        val yaml = """
            services:
              app:
                image: registry.local:5000/team/app
                ports: ["8080:80", "127.0.0.1:9000:9000/udp", 3000]
                volumes: ["/srv/data:/data:ro", "cache:/cache", "/tmp/x"]
                environment: ["A=1", "B=x=y", "C"]
                devices: ["/dev/ttyUSB0"]
                command: npm start
                mem_limit: 1g
                privileged: true
        """.trimIndent()
        val a = main(parseAppSettings(yaml))
        assertEquals("registry.local:5000/team/app", a.image)
        assertEquals("", a.tag)
        assertEquals(listOf(PortMapping("8080", "80", "tcp", 0), PortMapping("9000", "9000", "udp", 1), PortMapping("", "3000", "tcp", 2)), a.ports)
        assertEquals(
            listOf(VolumeMapping("/srv/data", "/data", true, 0), VolumeMapping("cache", "/cache", false, 1), VolumeMapping("", "/tmp/x", false, 2)),
            a.volumes,
        )
        assertEquals(listOf(EnvVar("A", "1"), EnvVar("B", "x=y"), EnvVar("C", "")), a.environment)
        assertEquals(listOf(DeviceMapping("/dev/ttyUSB0", "/dev/ttyUSB0", 0)), a.devices)
        assertEquals(listOf("npm start"), a.command)
        assertEquals("1024", a.memoryMb)
        assertTrue(a.privileged)
    }

    @Test fun `a renamed app shows its custom name`() {
        val yaml = "services: {a: {image: x}}\nx-casaos: {title: {en_us: Store name, custom: Mine}}"
        assertEquals("Mine", parseAppSettings(yaml).app.title)
    }

    @Test fun `the main container comes first`() {
        val yaml = "services:\n  db: {image: postgres}\n  web: {image: nginx}\nx-casaos: {main: web}"
        assertEquals(listOf("web", "db"), parseAppSettings(yaml).services.map { it.name })
    }

    @Test fun `bad YAML and files without services are reported, not crashed on`() {
        val e = assertThrows(CasaException.Parse::class.java) { parseAppSettings("name: x\nservices: [\n  a: 1\n") }
        assertTrue(e.message, e.message!!.startsWith("The YAML isn't valid. Line "))
        assertThrows(CasaException.Parse::class.java) { parseAppSettings("name: nothing") }
        assertThrows(CasaException.Parse::class.java) { parseAppSettings("- just\n- a list") }
    }

    @Test fun `saving without edits keeps every setting`() {
        val before = parseAppSettings(casaYaml)
        val out = applyAppSettings(casaYaml, before, before)
        assertEquals(before, parseAppSettings(out))
        assertEquals(loadComposeYaml(casaYaml), loadComposeYaml(out))
    }

    @Test fun `editing one setting leaves everything else alone`() {
        val before = parseAppSettings(casaYaml)
        val j = main(before)
        val after = before.copy(services = listOf(j.copy(tag = "10.9.0")))
        val root = loadComposeYaml(applyAppSettings(casaYaml, before, after))
        val original = loadComposeYaml(casaYaml)

        @Suppress("UNCHECKED_CAST")
        val svc = (root["services"] as Map<String, Map<String, Any?>>)["jellyfin"]!!
        assertEquals("linuxserver/jellyfin:10.9.0", svc["image"])
        // Things the form doesn't show survive: labels, healthcheck, tips, reservations.
        @Suppress("UNCHECKED_CAST")
        val origSvc = (original["services"] as Map<String, Map<String, Any?>>)["jellyfin"]!!
        assertEquals(origSvc - "image", svc - "image")
        assertEquals(original["x-casaos"], root["x-casaos"])
    }

    @Test fun `changing a port keeps its other options`() {
        val before = parseAppSettings(casaYaml)
        val j = main(before)
        val ports = j.ports.toMutableList().also { it[0] = it[0].copy(host = "9096") } + PortMapping("1900", "1900", "udp")
        val after = before.copy(services = listOf(j.copy(ports = ports)))
        val root = loadComposeYaml(applyAppSettings(casaYaml, before, after))

        @Suppress("UNCHECKED_CAST")
        val written = ((root["services"] as Map<*, *>)["jellyfin"] as Map<*, *>)["ports"] as List<Map<*, *>>
        assertEquals(mapOf("mode" to "ingress", "host_ip" to "0.0.0.0", "target" to 8096, "published" to "9096", "protocol" to "tcp"), written[0])
        assertEquals(mapOf("target" to 1900, "published" to "1900", "protocol" to "udp"), written[2])
    }

    @Test fun `removing a row drops the right entry even when an earlier one is gone`() {
        val before = parseAppSettings(casaYaml)
        val j = main(before)
        val after = before.copy(services = listOf(j.copy(volumes = listOf(j.volumes[1].copy(host = "/DATA/Movies")))))
        val root = loadComposeYaml(applyAppSettings(casaYaml, before, after))

        @Suppress("UNCHECKED_CAST")
        val written = ((root["services"] as Map<*, *>)["jellyfin"] as Map<*, *>)["volumes"] as List<Map<*, *>>
        // The remaining entry is still the read-only media volume, now pointing elsewhere.
        assertEquals(1, written.size)
        assertEquals("/DATA/Movies", written[0]["source"])
        assertEquals("/media", written[0]["target"])
        assertEquals(true, written[0]["read_only"])
    }

    @Test fun `a new folder volume asks Docker to create the folder`() {
        val before = parseAppSettings(casaYaml)
        val j = main(before)
        val after = before.copy(services = listOf(j.copy(volumes = j.volumes + VolumeMapping("/DATA/New", "/new"))))
        val root = loadComposeYaml(applyAppSettings(casaYaml, before, after))

        @Suppress("UNCHECKED_CAST")
        val written = ((root["services"] as Map<*, *>)["jellyfin"] as Map<*, *>)["volumes"] as List<Map<*, *>>
        assertEquals(mapOf("type" to "bind", "bind" to mapOf("create_host_path" to true), "source" to "/DATA/New", "target" to "/new"), written[2])
    }

    @Test fun `short-syntax entries stay short`() {
        val yaml = "services:\n  app:\n    image: x\n    ports: ['127.0.0.1:8080:80']\n    volumes: ['/a:/b:ro']\n    command: npm start"
        val before = parseAppSettings(yaml)
        val a = main(before)
        val after = before.copy(
            services = listOf(
                a.copy(
                    ports = listOf(a.ports[0].copy(host = "8081")),
                    volumes = listOf(a.volumes[0].copy(host = "/c")),
                    command = listOf("npm run serve"),
                ),
            ),
        )
        val out = applyAppSettings(yaml, before, after)
        val svc = (loadComposeYaml(out)["services"] as Map<*, *>)["app"] as Map<*, *>
        assertEquals(listOf("127.0.0.1:8081:80"), svc["ports"])
        assertEquals(listOf("/c:/b:ro"), svc["volumes"])
        // Still one string, so it still runs through a shell.
        assertEquals("npm run serve", svc["command"])
        // And it's quoted, so no YAML reader mistakes it for a number.
        assertTrue(out, out.contains("\"127.0.0.1:8081:80\""))
    }

    @Test fun `environment values are escaped again and ambiguous ones quoted`() {
        val before = parseAppSettings(casaYaml)
        val j = main(before)
        val env = j.environment + EnvVar("ENABLED", "yes") + EnvVar("COST", "\$5")
        val out = applyAppSettings(casaYaml, before, before.copy(services = listOf(j.copy(environment = env))))
        assertTrue(out, out.contains("DB_PASSWORD: pa\$\$word"))
        assertTrue(out, out.contains("ENABLED: \"yes\""))
        assertTrue(out, out.contains("COST: \$\$5"))
        // Reading it back gives the values as typed.
        assertEquals(env, main(parseAppSettings(out)).environment)
    }

    @Test fun `clearing the memory limit removes it and tidies up`() {
        val yaml = "services:\n  app:\n    image: x\n    deploy: {resources: {limits: {memory: 512M}}}"
        val before = parseAppSettings(yaml)
        val a = main(before)
        assertEquals("512", a.memoryMb)

        val cleared = loadComposeYaml(applyAppSettings(yaml, before, before.copy(services = listOf(a.copy(memoryMb = "")))))
        assertNull(((cleared["services"] as Map<*, *>)["app"] as Map<*, *>)["deploy"])

        val raised = applyAppSettings(yaml, before, before.copy(services = listOf(a.copy(memoryMb = "2048"))))
        assertEquals("2048", main(parseAppSettings(raised)).memoryMb)
        assertTrue(raised, raised.contains("memory: 2048M"))
    }

    @Test fun `a mem_limit file keeps using mem_limit`() {
        val yaml = "services:\n  app: {image: x, mem_limit: 1g}"
        val before = parseAppSettings(yaml)
        val a = main(before)
        val svc = (loadComposeYaml(applyAppSettings(yaml, before, before.copy(services = listOf(a.copy(memoryMb = "512")))))["services"] as Map<*, *>)["app"] as Map<*, *>
        assertEquals("512M", svc["mem_limit"])
        assertNull(svc["deploy"])
    }

    @Test fun `renaming keeps the store's translations`() {
        val before = parseAppSettings(casaYaml)
        val out = applyAppSettings(casaYaml, before, before.copy(app = before.app.copy(title = "Movies")))
        val title = (loadComposeYaml(out)["x-casaos"] as Map<*, *>)["title"]
        assertEquals(mapOf("en_us" to "Jellyfin", "custom" to "Movies"), title)
        assertEquals("Movies", parseAppSettings(out).app.title)
    }

    @Test fun `switches and choices are written, and cleared ones removed`() {
        val before = parseAppSettings(casaYaml)
        val j = main(before)
        val after = before.copy(services = listOf(j.copy(privileged = true, cpuShares = null, restart = "always", capAdd = emptyList())))
        val svc = (loadComposeYaml(applyAppSettings(casaYaml, before, after))["services"] as Map<*, *>)["jellyfin"] as Map<*, *>
        assertEquals(true, svc["privileged"])
        assertFalse(svc.containsKey("cpu_shares"))
        assertEquals("always", svc["restart"])
        assertFalse(svc.containsKey("cap_add"))
    }

    @Test fun `validation explains what's wrong`() {
        val ok = parseAppSettings(casaYaml)
        assertEquals(emptyList<String>(), ok.validate())

        val j = main(ok)
        val bad = ok.copy(
            services = listOf(
                j.copy(
                    image = "",
                    ports = listOf(PortMapping("70000", "80"), PortMapping("", "abc")),
                    volumes = listOf(VolumeMapping("/a", "relative")),
                    environment = listOf(EnvVar("", "value"), EnvVar("HAS SPACE", "")),
                    memoryMb = "3",
                    containerName = "-bad",
                ),
            ),
        )
        val problems = bad.validate()
        assertTrue(problems.toString(), problems.any { "Docker image" in it })
        assertTrue(problems.toString(), problems.any { "\"70000\"" in it })
        assertTrue(problems.toString(), problems.any { "\"abc\"" in it })
        assertTrue(problems.toString(), problems.any { "must start with /" in it })
        assertTrue(problems.toString(), problems.any { "needs a name" in it })
        assertTrue(problems.toString(), problems.any { "HAS SPACE" in it })
        assertTrue(problems.toString(), problems.any { "at least 6" in it })
        assertTrue(problems.toString(), problems.any { "Container name" in it })
    }

    @Test fun `empty rows are dropped before saving`() {
        val s = parseAppSettings(casaYaml)
        val j = main(s)
        val withBlanks = s.copy(services = listOf(j.copy(ports = j.ports + PortMapping("", ""), environment = j.environment + EnvVar(" ", ""))))
        assertEquals(s, withBlanks.normalized())
    }

    @Test fun `image tags split where a tag is`() {
        assertEquals("nginx" to "1.27", splitImage("nginx:1.27"))
        assertEquals("nginx" to "", splitImage("nginx"))
        assertEquals("host:5000/team/app" to "", splitImage("host:5000/team/app"))
        assertEquals("host:5000/team/app" to "v2", splitImage("host:5000/team/app:v2"))
        assertEquals("app@sha256:abc" to "", splitImage("app@sha256:abc"))
    }

    @Test fun `memory sizes read like Docker reads them`() {
        assertEquals(268435456L, bytesOf("268435456"))
        assertEquals(268435456L, bytesOf(268435456))
        assertEquals(256L * 1024 * 1024, bytesOf("256M"))
        assertEquals(1536L * 1024 * 1024, bytesOf("1.5g"))
        assertEquals(512L * 1024 * 1024, bytesOf("512MiB"))
        assertNull(bytesOf("lots"))
    }

    @Test fun `secret-looking variables are hidden, others shown`() {
        assertTrue(EnvVar("DB_PASSWORD", "x").looksSecret())
        assertTrue(EnvVar("API_KEY", "x").looksSecret())
        assertFalse(EnvVar("TZ", "Europe/Berlin").looksSecret())
        assertFalse(EnvVar("PASSWORD", "").looksSecret())
    }
}
