package com.bnyro.clock.social.data

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bnyro.clock.App
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.SharedSoundUploadRequest
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class SharedSoundWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = (if (inputData.getBoolean("upload", false)) uploads else downloads).withPermit { withContext(Dispatchers.IO) {
        val soundId = inputData.getString("sound_id") ?: return@withContext Result.failure()
        val server = inputData.getString("server") ?: return@withContext Result.failure()
        val identity = DeviceIdentityStore.loadOrCreate(applicationContext, server)
        val configured = Preferences.instance.getString(SocialPreferences.serverUrlKey, SocialRepository.DEFAULT_SERVER_URL)
        if (configured != server || identity.id != inputData.getString("identity")) return@withContext Result.success()
        val api = SocialApi(server, identity)
        val repository = (applicationContext as App).container.socialRepository
        val dao = SocialDatabase.getDatabase(applicationContext).socialDao()
        val operationId = inputData.getString("operation_id")
        val operation = operationId?.let { dao.getOperation(it) }
        val source = operation?.audioSourcePath?.let(::File) ?: File(applicationContext.filesDir, "sound-uploads/$soundId.source")
        val encoded = File(applicationContext.filesDir, "sound-uploads/$soundId.flac")
        val metadata = File(applicationContext.filesDir, "sound-uploads/$soundId.json")
        try {
            if (inputData.getBoolean("upload", false)) {
                if (operation == null || operation.audioSourcePath == null) return@withContext Result.success()
                if (operation.state in setOf("rejected", "reviewed") && operation.rejectionCode != null) {
                    dao.completeAudioOperation(operation.operationId)
                    source.delete()
                    encoded.delete()
                    metadata.delete()
                    return@withContext Result.failure()
                }
                if (!encoded.exists() || !metadata.exists()) {
                    val processed = SharedSoundProcessor(applicationContext).process(source.toUri()) {}
                    try {
                        val temporary = File(encoded.parentFile, "$soundId.flac.part")
                        processed.file.inputStream().use { input ->
                            java.io.FileOutputStream(temporary).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        check(temporary.renameTo(encoded))
                        val manifest = json.encodeToString(SharedSoundUploadRequest(
                            requireNotNull(inputData.getString("title")), processed.sha256, processed.file.length(),
                            processed.durationMs, soundId, requireNotNull(inputData.getString("membership_id"))
                        ))
                        val atomic = AtomicFile(metadata)
                        val output = atomic.startWrite()
                        try {
                            output.write(manifest.toByteArray())
                            atomic.finishWrite(output)
                        } catch (error: Exception) {
                            atomic.failWrite(output)
                            throw error
                        }
                    } finally {
                        processed.file.delete()
                    }
                }
                repository.synchronize()
                val currentOperation = dao.getOperation(operation.operationId)
                if (currentOperation == null || currentOperation.rejectionCode != null || DeviceIdentityStore.loadOrCreate(applicationContext, server).id != identity.id) {
                    dao.completeAudioOperation(operation.operationId)
                    source.delete()
                    encoded.delete()
                    metadata.delete()
                    return@withContext Result.failure()
                }
                val groupId = requireNotNull(inputData.getString("group_id"))
                api.executeOperation(PendingOperation(
                    operationId = UUID.nameUUIDFromBytes("upload:$soundId".toByteArray()).toString(),
                    scopeId = groupId, kind = "sound", targetId = soundId, method = "POST",
                    path = "/v1/groups/$groupId/sounds/uploads", payload = metadata.readText(),
                    optimisticState = null, rawSavedAt = 0, effectiveSavedAt = 0
                ))
                val status = api.getSound(soundId).status
                if (status in setOf("failed", "deleting")) {
                    dao.completeAudioOperation(operation.operationId)
                    source.delete()
                    encoded.delete()
                    metadata.delete()
                    return@withContext Result.failure()
                }
                if (status == "pending") {
                    api.uploadSound(api.getSoundUpload(soundId), encoded)
                    api.executeOperation(PendingOperation(
                        operationId = UUID.nameUUIDFromBytes("complete:$soundId".toByteArray()).toString(),
                        scopeId = groupId, kind = "sound", targetId = soundId, method = "POST",
                        path = "/v1/sounds/$soundId/complete", payload = null,
                        optimisticState = null, rawSavedAt = 0, effectiveSavedAt = 0
                    ))
                }
                SharedSoundStore(applicationContext).keep(soundId, encoded)
                dao.completeAudioOperation(operation.operationId)
                source.delete()
                encoded.delete()
                metadata.delete()
            } else {
                if (api.getSound(soundId).status != "ready" || withTimeoutOrNull(60_000) {
                    SharedSoundStore(applicationContext).cache(soundId, api)
                } == null) return@withContext Result.retry()
            }
            repository.refreshSharedState()
            Result.success()
        } catch (error: SocialApiException) {
            if (error.status in setOf(400, 403, 404, 409)) {
                operationId?.let { dao.completeAudioOperation(it) }
                source.delete()
                encoded.delete()
                metadata.delete()
                Result.failure()
            } else Result.retry()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    } }

    companion object {
        private val uploads = Semaphore(1)
        private val downloads = Semaphore(2)
        private val json = Json { encodeDefaults = true }
        fun enqueue(context: Context, soundId: String) {
            val server = Preferences.instance.getString(SocialPreferences.serverUrlKey, SocialRepository.DEFAULT_SERVER_URL) ?: SocialRepository.DEFAULT_SERVER_URL
            WorkManager.getInstance(context).enqueueUniqueWork("jay_sound_download_$soundId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SharedSoundWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(Data.Builder().putString("sound_id", soundId).putString("server", server)
                        .putString("identity", DeviceIdentityStore.loadOrCreate(context, server).id).build())
                    .build())
        }

        fun upload(context: Context, operation: PendingOperation) {
            val payload = Json.parseToJsonElement(requireNotNull(operation.payload)).jsonObject
            val sound = payload.getValue("sound").jsonObject
            val soundId = sound.getValue("sound_id").jsonPrimitive.content
            val server = Preferences.instance.getString(SocialPreferences.serverUrlKey, SocialRepository.DEFAULT_SERVER_URL) ?: SocialRepository.DEFAULT_SERVER_URL
            WorkManager.getInstance(context).enqueueUniqueWork("jay_sound_upload_$soundId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SharedSoundWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(Data.Builder().putString("sound_id", soundId).putString("server", server)
                        .putString("identity", DeviceIdentityStore.loadOrCreate(context, server).id)
                        .putBoolean("upload", true).putString("group_id", operation.scopeId)
                        .putString("operation_id", operation.operationId)
                        .putString("membership_id", payload.getValue("membership_id").jsonPrimitive.content)
                        .putString("title", sound.getValue("title").jsonPrimitive.content).build())
                    .build())
        }
    }
}
