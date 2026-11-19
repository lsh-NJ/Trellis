package dev.trellis.sync

/**
 * 同步引擎：一次 [sync] 对所有已配置的域做 pull → 合并 → push。
 *
 * 约定（对应 docs/multi-device-sync.md §3.5）：
 * - 远端 head 合并：对每个 key 取所有设备 head 里 `(hlc)` 最大的；
 * - 本地用 baseline（上次同步后双方认可的版本）判断「谁改了」；
 * - 两边都改：文件/记录域保留本地版本为主，把远端版本落成冲突副本；
 * - 删除用墓碑（head.deleted=true）传播，不物理删远端对象。
 */
class SyncEngine(
    private val remote: SyncTransport,
    private val store: SyncStore,
    private val clock: HlcClock,
    private val domains: List<SyncDomain>,
    private val platform: String = "unknown",
) {

    private val deviceId: String = store.deviceId()
    private var deviceRegistered = false

    suspend fun sync(): SyncReport {
        val state = store.loadState()
        val errors = mutableListOf<String>()
        var pulled = 0
        var pushed = 0
        var conflicts = 0

        runCatching { registerDevice() }.onFailure {
            errors += "注册设备失败：${it.message ?: it::class.simpleName}"
        }

        for (domain in domains) {
            val domainState = state.domains.getOrPut(domain.id) { DomainState() }
            val result = try {
                syncDomain(domain, domainState)
            } catch (t: Throwable) {
                SyncReport(errors = listOf("${domain.id}：${t.message ?: t::class.simpleName}"))
            }
            pulled += result.pulled
            pushed += result.pushed
            conflicts += result.conflicts
            errors += result.errors
        }

        store.saveState(state.copy(lastSyncAt = System.currentTimeMillis()))

        val report = SyncReport(pulled, pushed, conflicts, errors)
        if (pulled + pushed + conflicts > 0 || errors.isNotEmpty()) {
            store.log("同步：${report.summary()}")
        }
        return report
    }

    private suspend fun registerDevice() {
        if (deviceRegistered) return
        val path = "devices/$deviceId.json"
        if (remote.get(path) == null) {
            remote.put(
                path,
                encodeDeviceInfo(deviceId, store.deviceName(), platform, System.currentTimeMillis()),
            )
        }
        deviceRegistered = true
    }

    private suspend fun syncDomain(domain: SyncDomain, domainState: DomainState): SyncReport {
        val remoteHeads = readRemoteHeads(domain.id)
        remoteHeads.values.forEach { clock.observe(it.hlc) }

        val local = domain.scanLocal()
        val ownHeadPath = "heads/${domain.id}/$deviceId.json"
        val ownHeads = mutableMapOf<String, ObjectHead>()
        remote.get(ownHeadPath)?.let { decodeDeviceHeads(it) }?.let { ownHeads.putAll(it.objects) }
        var ownHeadsDirty = false

        var pulled = 0
        var pushed = 0
        var conflicts = 0

        suspend fun push(key: String, bytes: ByteArray?): ObjectHead {
            val head = if (bytes == null) {
                ObjectHead(clock.next(), "", 0, deleted = true)
            } else {
                val sha = sha256Hex(bytes)
                ensureBlob(domain.id, sha, bytes)
                ObjectHead(clock.next(), sha, bytes.size.toLong(), deleted = false)
            }
            ownHeads[key] = head
            ownHeadsDirty = true
            // 推送成功即视为「双方已认可的最新版本」，否则下次同步会把本地删除误判成首次拉取。
            domainState.baseline[key] = head
            return head
        }

        suspend fun applyRemote(key: String, head: ObjectHead) {
            val bytes = if (head.deleted) null else fetchBlob(domain.id, head)
            domain.applyRemote(key, bytes, head.deleted)
            domainState.baseline[key] = head
        }

        val keys = buildSet {
            addAll(local.keys)
            addAll(remoteHeads.keys)
            addAll(domainState.baseline.keys)
        }

        for (key in keys) {
            val bytes = local[key]
            val localSha = bytes?.let(::sha256Hex)
            val base = domainState.baseline[key]
            val remoteHead = remoteHeads[key]

            val localChanged = if (bytes != null) {
                base == null || base.deleted || base.sha256 != localSha
            } else {
                base != null && !base.deleted
            }
            val remoteChanged = remoteHead != null && (base == null || remoteHead.hlc != base.hlc)
            if (!localChanged && !remoteChanged) continue

            // 远端没有这个 key：本地新增或本地删除（墓碑）。
            if (remoteHead == null) {
                when {
                    bytes != null && localChanged -> {
                        push(key, bytes)
                        pushed++
                    }
                    bytes != null -> continue
                    localChanged -> {
                        push(key, null)
                        pushed++
                    }
                }
                continue
            }

            // 远端已删除。
            if (remoteHead.deleted) {
                when {
                    bytes == null -> domainState.baseline[key] = remoteHead
                    !localChanged -> {
                        applyRemote(key, remoteHead)
                        pulled++
                    }
                    else -> {
                        // 本地改过、远端删了：保留本地并复活，远端删不作为事实覆盖本地内容。
                        val copy = store.writeConflict(domain.id, key, remoteHead.hlc, null, true)
                        store.recordConflict(domain.id, key, remoteHead.hlc, remoteHead.sha256, true, copy)
                        push(key, bytes)
                        conflicts++
                        pushed++
                    }
                }
                continue
            }

            // 内容一致（不管 hlc 谁新）：只更新 baseline，不产生流量。
            if (bytes != null && remoteHead.sha256 == localSha) {
                domainState.baseline[key] = remoteHead
                continue
            }

            // 本地没有文件。
            if (bytes == null) {
                if (localChanged) {
                    if (remoteChanged) {
                        // 本地删、远端改：恢复远端，并留一份冲突记录（本地删除没有内容可留）。
                        applyRemote(key, remoteHead)
                        val remoteBytes = fetchBlob(domain.id, remoteHead)
                        val copy = store.writeConflict(domain.id, key, remoteHead.hlc, remoteBytes, false)
                        store.recordConflict(domain.id, key, remoteHead.hlc, remoteHead.sha256, false, copy)
                        pulled++
                        conflicts++
                    } else {
                        // 远端没变，本地删除生效。
                        push(key, null)
                        pushed++
                    }
                } else {
                    applyRemote(key, remoteHead)
                    pulled++
                }
                continue
            }

            // 本地有文件、远端也有文件、内容不同。
            if (!localChanged) {
                applyRemote(key, remoteHead)
                pulled++
            } else if (!remoteChanged) {
                push(key, bytes)
                pushed++
            } else {
                // 两边都改：保留本地为主，远端落冲突副本，然后把本地推上去（新 HLC）。
                val remoteBytes = fetchBlob(domain.id, remoteHead)
                val copy = store.writeConflict(domain.id, key, remoteHead.hlc, remoteBytes, false)
                store.recordConflict(domain.id, key, remoteHead.hlc, remoteHead.sha256, false, copy)
                push(key, bytes)
                conflicts++
                pushed++
            }
        }

        if (ownHeadsDirty) {
            remote.put(ownHeadPath, encodeDeviceHeads(DeviceHeads(deviceId = deviceId, objects = ownHeads)))
        }

        return SyncReport(pulled, pushed, conflicts, emptyList())
    }

    private suspend fun readRemoteHeads(domainId: String): Map<String, ObjectHead> {
        val files = remote.list("heads/$domainId/")
        if (files.isEmpty()) return emptyMap()
        val merged = mutableMapOf<String, ObjectHead>()
        for (file in files) {
            val bytes = remote.get(file.path) ?: continue
            val heads = decodeDeviceHeads(bytes) ?: continue
            heads.objects.forEach { (key, head) ->
                val current = merged[key]
                if (current == null || head.hlc > current.hlc) merged[key] = head
            }
        }
        return merged
    }

    private suspend fun ensureBlob(domainId: String, sha: String, bytes: ByteArray) {
        val path = "blobs/$domainId/$sha"
        if (remote.get(path) == null) remote.put(path, bytes)
    }

    private suspend fun fetchBlob(domainId: String, head: ObjectHead): ByteArray {
        val path = "blobs/$domainId/${head.sha256}"
        val bytes = remote.get(path)
            ?: throw IllegalStateException("远端缺少 blob：$path（头指向 ${head.sha256}）")
        val actual = sha256Hex(bytes)
        check(actual == head.sha256) { "blob 校验失败：$path（期望 ${head.sha256}，实际 $actual）" }
        return bytes
    }
}
