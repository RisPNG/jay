package com.bnyro.clock.social.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.PendingSocialActivity
import com.bnyro.clock.social.domain.ScopeSyncItem
import com.bnyro.clock.social.domain.StagedResource
import com.bnyro.clock.social.domain.SyncClockState
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.social.domain.SyncScopeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID

class SocialSynchronization(
    private val context: Context,
    private val database: SocialDatabase
) {
    private val dao = database.socialDao()

    suspend fun saveOperation(
        scopeId: String,
        kind: String,
        targetId: String,
        method: String,
        path: String,
        payload: JsonObject?,
        optimisticState: JsonObject?,
        ordered: Boolean = true,
        dependencyId: String? = null,
        operationId: String = UUID.randomUUID().toString(),
        audioSourcePath: String? = null,
        capturedSavedAt: Long? = null
    ): PendingOperation = database.withTransaction {
        dao.getOperation(operationId)?.let { return@withTransaction it }
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        val clock = dao.getClock()
        val effective = if (capturedSavedAt != null) capturedSavedAt + (clock?.let { it.serverMillis - it.wallMillis } ?: 0) else maxOf(
            when {
                clock == null -> wall
                clock.bootCount == boot -> clock.serverMillis + elapsed - clock.elapsedMillis
                else -> wall + clock.serverMillis - clock.wallMillis
            },
            (clock?.lastSavedMillis ?: 0) + 1
        )
        dao.putClock(clock?.copy(lastSavedMillis = maxOf(clock.lastSavedMillis, effective)) ?: SyncClockState(
            serverMillis = wall, wallMillis = wall, elapsedMillis = elapsed,
            bootCount = boot, lastSavedMillis = effective
        ))
        val savedAt = JsonPrimitive(Instant.ofEpochMilli(effective).toString())
        val body = payload?.let { if (ordered) JsonObject(it + ("saved_at" to savedAt)) else it }
        val optimistic = optimisticState?.let {
            if (ordered) JsonObject(it + mapOf("saved_at" to savedAt, "save_id" to JsonPrimitive(operationId))) else it
        }
        val operation = PendingOperation(
            operationId = operationId, scopeId = scopeId, kind = kind, targetId = targetId,
            method = method, path = path, payload = body?.toString(),
            optimisticState = optimistic?.toString(), rawSavedAt = capturedSavedAt ?: wall,
            effectiveSavedAt = effective, dependencyId = dependencyId, audioSourcePath = audioSourcePath,
            observedRevision = dao.getScopes().firstOrNull { it.scopeId == scopeId }?.revision ?: 0
        )
        operation.copy(sequence = dao.enqueueOperation(operation))
    }

    suspend fun flushOperations(api: SocialApi) {
        for (operation in dao.getPendingOperations()) {
            val dependency = operation.dependencyId?.let { dao.getOperation(it) }
            if (dependency?.state == "rejected") {
                dao.resolveOperation(operation.operationId, "rejected", "dependency_rejected")
                continue
            }
            if (dependency?.state == "pending") continue
            try {
                val response = api.executeOperation(operation)
                dao.resolveOperation(operation.operationId, "accepted", response = response)
            } catch (error: SocialApiException) {
                if (error.status == 429 || error.status >= 500 || error.status == 401) throw error
                database.withTransaction {
                    dao.resolveOperation(operation.operationId, "rejected", error.code)
                    if (error.code == "clock_invalid") {
                        dao.getClock()?.let { dao.putClock(it.copy(lastSavedMillis = 0)) }
                    }
                }
            }
        }
    }

    suspend fun synchronizeScope(api: SocialApi, route: String): List<ScopeSyncItem> {
        while (true) {
            val previous = dao.getScope(route)
            val startElapsed = SystemClock.elapsedRealtime()
            val startWall = System.currentTimeMillis()
            val page = try {
                api.synchronizeScope(route, previous?.stagingCursor ?: previous?.cursor)
            } catch (error: SocialApiException) {
                if (error.code !in setOf("cursor_expired", "cursor_invalid")) throw error
                database.withTransaction {
                    if (previous != null) {
                        dao.clearStaging(previous.scopeId)
                        dao.putScope(previous.copy(cursor = null, stagingCursor = null, stagingMode = null))
                    }
                }
                continue
            }
            val endElapsed = SystemClock.elapsedRealtime()
            database.withTransaction {
                val clock = dao.getClock()
                val observed = page.items.mapNotNull {
                    ((it.data["saved_at"] ?: it.data["preferences_saved_at"]) as? JsonPrimitive)?.content?.let { savedAt -> java.time.OffsetDateTime.parse(savedAt).toInstant().toEpochMilli() }
                }.maxOrNull() ?: 0
                dao.putClock(SyncClockState(
                    serverMillis = java.time.OffsetDateTime.parse(page.serverTime).toInstant().toEpochMilli(),
                    wallMillis = startWall + (endElapsed - startElapsed) / 2,
                    elapsedMillis = (startElapsed + endElapsed) / 2,
                    bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0),
                    lastSavedMillis = maxOf(clock?.lastSavedMillis ?: 0, observed)
                ))
                if (previous?.stagingMode != page.mode || previous.stagingCursor == null) {
                    dao.clearStaging(page.scopeId)
                }
                dao.stageResources(page.items.map {
                    StagedResource(page.scopeId, it.kind, it.key, it.action, it.data.toString())
                })
                if (!page.hasMore) {
                    if (page.mode == "snapshot") dao.clearScopeResources(page.scopeId)
                    for (resource in dao.getStagedResources(page.scopeId)) {
                        if (resource.action == "delete") {
                            dao.deleteResource(resource.scopeId, resource.kind, resource.key)
                        } else if (resource.kind == "activity" && page.mode == "delta") {
                            dao.enqueueActivity(PendingSocialActivity(resource.scopeId, resource.key, resource.representation))
                        } else if (resource.kind != "activity") {
                            dao.putResources(listOf(SyncedResource(resource.scopeId, resource.kind, resource.key, resource.representation)))
                        }
                    }
                    dao.clearStaging(page.scopeId)
                }
                dao.putScope(SyncScopeState(
                    route, page.scopeId,
                    cursor = if (page.hasMore) previous?.cursor else page.nextCursor,
                    stagingCursor = if (page.hasMore) page.nextCursor else null,
                    stagingMode = if (page.hasMore) page.mode else null,
                    revision = if (page.hasMore) previous?.revision ?: 0 else page.throughRevision
                ))
            }
            if (!page.hasMore) return dao.getPendingActivity().filter { it.scopeId == page.scopeId }.map {
                ScopeSyncItem("activity", it.key, "upsert", Json.parseToJsonElement(it.representation).jsonObject, 0, 0)
            }
        }
    }

    suspend fun projectedResources(): List<SyncedResource> {
        val resources = dao.getResources().associateBy { Triple(it.scopeId, it.kind, it.key) }.toMutableMap()
        for (operation in dao.getProjectedOperations().sortedWith(
            compareBy<PendingOperation> { it.method == "DELETE" }.thenBy { it.effectiveSavedAt }.thenBy { it.operationId }
        )) {
            val key = Triple(operation.scopeId, operation.kind, operation.targetId)
            if (operation.optimisticState == null) {
                if (operation.method == "DELETE") resources.remove(key)
            } else {
                resources[key] = SyncedResource(operation.scopeId, operation.kind, operation.targetId, operation.optimisticState)
                if (operation.kind == "group" && operation.method == "POST") {
                    val group = Json.parseToJsonElement(operation.optimisticState).jsonObject
                    val membershipId = group.getValue("membership_id")
                    val membership = JsonObject(mapOf(
                        "id" to membershipId, "group_id" to JsonPrimitive(operation.targetId),
                        "scope_id" to JsonPrimitive(operation.scopeId), "role" to JsonPrimitive("leader"),
                        "notify_membership" to JsonPrimitive(true), "notify_administrative" to JsonPrimitive(true)
                    ))
                    resources[Triple(operation.scopeId, "membership", membershipId.toString())] =
                        SyncedResource(operation.scopeId, "membership", membershipId.toString(), membership.toString())
                }
            }
        }
        return resources.values.toList()
    }
}
